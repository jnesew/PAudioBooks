package io.github.jnesew.paudiobooks.importing;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test APK only: a bounded local SAF source for real native copy interruption. */
public final class SlowDocumentsProvider extends DocumentsProvider {
    private static final long AUDIO_BYTES = 16L * 1024 * 1024;
    private static final String[] ROOT_COLUMNS = { DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES };
    private static final String[] DOC_COLUMNS = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS };

    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        put(row, cursor, DocumentsContract.Root.COLUMN_ROOT_ID, "synthetic-interruption");
        put(row, cursor, DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root");
        put(row, cursor, DocumentsContract.Root.COLUMN_TITLE, "Synthetic import fault");
        put(row, cursor, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
        put(row, cursor, DocumentsContract.Root.COLUMN_MIME_TYPES, "audio/wav");
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (!id.equals("root") && !id.equals("audio")) throw new FileNotFoundException();
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        addDocument(cursor, id);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException();
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        addDocument(cursor, "audio");
        return cursor;
    }
    private static void put(MatrixCursor.RowBuilder row, MatrixCursor cursor, String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
    }
    private static void addDocument(MatrixCursor cursor, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        put(row, cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        put(row, cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME, id.equals("root") ? "Synthetic import fault" : "Slow synthetic.wav");
        put(row, cursor, DocumentsContract.Document.COLUMN_MIME_TYPE, id.equals("root") ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/wav");
        put(row, cursor, DocumentsContract.Document.COLUMN_SIZE, id.equals("root") ? null : AUDIO_BYTES + 44);
        put(row, cursor, DocumentsContract.Document.COLUMN_FLAGS, 0);
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation) throws FileNotFoundException {
        if (!id.equals("audio") || !mode.equals("r")) throw new FileNotFoundException();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                    header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) AUDIO_BYTES + 36)
                        .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                        .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                        .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) AUDIO_BYTES);
                    out.write(header.array());
                    byte[] chunk = new byte[64 * 1024];
                    for (long written = 0; written < AUDIO_BYTES; written += chunk.length) {
                        if (cancellation != null) cancellation.throwIfCanceled();
                        out.write(chunk);
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) { /* Expected pipe close after native app termination. */ }
            }, "synthetic-slow-document").start();
            return pipe[0];
        } catch (IOException error) { throw new FileNotFoundException("Cannot create synthetic stream"); }
    }
}
