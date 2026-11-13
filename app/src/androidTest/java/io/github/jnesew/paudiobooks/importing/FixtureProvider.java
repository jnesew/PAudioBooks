package io.github.jnesew.paudiobooks.importing;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.content.res.AssetFileDescriptor;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class FixtureProvider extends ContentProvider {
    private static volatile java.util.concurrent.CountDownLatch controlledRelease = new java.util.concurrent.CountDownLatch(0);
    private static volatile boolean controlledEntered;

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        switch (method) {
            case "metadata-seed": BatchDocumentsProvider.seedMetadata(getContext(),arg,extras.getByteArray("bytes")); break;
            case "batch-grant": case "batch-revoke": case "batch-fail-b": case "linked-grant": case "linked-revoke": case "linked-file-grant": case "linked-rescan":
                BatchDocumentsProvider.configure(getContext(), method, arg);
                break;
            case "arm-open":
                if (controlledRelease.getCount() != 0) throw new IllegalStateException("Fixture already armed");
                controlledEntered = false;
                controlledRelease = new java.util.concurrent.CountDownLatch(1);
                break;
            case "release-open": controlledRelease.countDown(); break;
            case "open-state": result.putBoolean("entered", controlledEntered); break;
            default: throw new IllegalArgumentException("Unknown fixture method");
        }
        return result;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal signal) {
        if (uri.toString().contains("blocking-cancel")) {
            if (signal != null) signal.setOnCancelListener(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            try { Thread.sleep(2400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (uri.toString().contains("/tree/slow/") || uri.toString().contains("slow-file")) {
            for (int i = 0; i < 40; i++) {
                if (signal != null) signal.throwIfCanceled();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (uri.toString().contains("companion-folder")) {
            MatrixCursor cursor = new MatrixCursor(projection);
            addRow(cursor, projection, "track10.mp3", "10-track.mp3", "audio/mpeg", 200000L);
            addRow(cursor, projection, "track2.wav", "2-track.wav", "audio/x-wav", 300000L);
            addRow(cursor, projection, "cover.jpg", "cover.jpg", "image/jpeg", 10000L);
            addRow(cursor, projection, "notes.txt", "notes.txt", "text/plain", 1000L);
            return cursor;
        }
        if (uri.toString().contains("repeated-folder")) {
            MatrixCursor cursor = new MatrixCursor(projection);
            addRow(cursor, projection, "doc-repeated-track", "01.mp3", "audio/mpeg", 200000L);
            addRow(cursor, projection, "doc-repeated-track", "01.mp3", "audio/mpeg", 200000L);
            addRow(cursor, projection, "doc-repeated-track", "01.mp3", "audio/mpeg", 200000L);
            return cursor;
        }
        if (uri.toString().contains("same-parent-duplicates")) {
            java.util.List<String> segments = uri.getPathSegments();
            String parentId = "same-parent-duplicates";
            int docIdx = segments.indexOf("document");
            if (docIdx >= 0 && docIdx + 1 < segments.size()) {
                parentId = segments.get(docIdx + 1);
            }
            MatrixCursor cursor = new MatrixCursor(projection);
            if ("same-parent-duplicates".equals(parentId)) {
                addRow(cursor, projection, "doc-sp-part1-a", "Part 1", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "doc-sp-part1-b", "Part 1", DocumentsContract.Document.MIME_TYPE_DIR, null);
            } else if ("doc-sp-part1-a".equals(parentId)) {
                addRow(cursor, projection, "doc-sp-a-01.mp3", "01.mp3", "audio/mpeg", 200000L);
            } else if ("doc-sp-part1-b".equals(parentId)) {
                addRow(cursor, projection, "doc-sp-b-01.mp3", "01.mp3", "audio/mpeg", 200000L);
            }
            return cursor;
        }
        if (uri.toString().contains("hierarchy-folder")) {
            java.util.List<String> segments = uri.getPathSegments();
            String parentId = "hierarchy-folder";
            int docIdx = segments.indexOf("document");
            if (docIdx >= 0 && docIdx + 1 < segments.size()) {
                parentId = segments.get(docIdx + 1);
            }
            MatrixCursor cursor = new MatrixCursor(projection);
            if ("hierarchy-folder".equals(parentId)) {
                addRow(cursor, projection, "doc-series", "Series", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "doc-multidisc", "MultiDisc", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "doc-sibling1", "Author 1", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "doc-sibling2", "Author 2", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "cover.jpg", "cover.jpg", "image/jpeg", 10000L);
            } else if ("doc-series".equals(parentId)) {
                addRow(cursor, projection, "doc-series-bookA", "Book A", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "doc-series-bookB", "Book B", DocumentsContract.Document.MIME_TYPE_DIR, null);
            } else if ("doc-series-bookA".equals(parentId)) {
                addRow(cursor, projection, "doc-series-bookA-01.wav", "01.wav", "audio/x-wav", 300000L);
            } else if ("doc-series-bookB".equals(parentId)) {
                addRow(cursor, projection, "doc-series-bookB-01.mp3", "01.mp3", "audio/mpeg", 200000L);
            } else if ("doc-multidisc".equals(parentId)) {
                addRow(cursor, projection, "doc-cd1", "CD 1", DocumentsContract.Document.MIME_TYPE_DIR, null);
                addRow(cursor, projection, "doc-cd2", "CD 2", DocumentsContract.Document.MIME_TYPE_DIR, null);
            } else if ("doc-cd1".equals(parentId)) {
                addRow(cursor, projection, "doc-cd1-01-start.mp3", "01-start.mp3", "audio/mpeg", 200000L);
                addRow(cursor, projection, "doc-cd1-02-middle.wav", "02-middle.wav", "audio/x-wav", 300000L);
            } else if ("doc-cd2".equals(parentId)) {
                addRow(cursor, projection, "doc-cd2-01-continue.mp3", "01-continue.mp3", "audio/mpeg", 200000L);
                addRow(cursor, projection, "doc-cd2-02-end.wav", "02-end.wav", "audio/x-wav", 300000L);
            } else if ("doc-sibling1".equals(parentId)) {
                addRow(cursor, projection, "doc-sibling1-special", "Special Edition", DocumentsContract.Document.MIME_TYPE_DIR, null);
            } else if ("doc-sibling2".equals(parentId)) {
                addRow(cursor, projection, "doc-sibling2-special", "Special Edition", DocumentsContract.Document.MIME_TYPE_DIR, null);
            } else if ("doc-sibling1-special".equals(parentId)) {
                addRow(cursor, projection, "doc-sibling1-01-author1.mp3", "01-author1.mp3", "audio/mpeg", 200000L);
            } else if ("doc-sibling2-special".equals(parentId)) {
                addRow(cursor, projection, "doc-sibling2-01-author2.wav", "01-author2.wav", "audio/x-wav", 300000L);
            }
            return cursor;
        }
        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] values = new Object[projection.length];
        boolean mislabeled = uri.toString().contains("mislabeled");
        for (int i = 0; i < projection.length; i++) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(projection[i])) values[i] = "file";
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(projection[i])) values[i] = "Book.mp3";
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(projection[i])) values[i] = mislabeled ? "application/octet-stream" : "audio/mpeg";
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(projection[i])) values[i] = 150000L;
        }
        cursor.addRow(values);
        return cursor;
    }
    private void addRow(MatrixCursor cursor, String[] projection, String id, String name, String mime, Long size) {
        Object[] values = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(projection[i])) values[i] = id;
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(projection[i])) values[i] = name;
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(projection[i])) values[i] = mime;
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(projection[i])) values[i] = size;
        }
        cursor.addRow(values);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeType, Bundle opts, CancellationSignal signal) throws FileNotFoundException {
        if ("controlled-open".equals(uri.getLastPathSegment()) || "blocking-open-cancel".equals(uri.getLastPathSegment())) return openAssetFile(uri, "r", signal);
        return super.openTypedAssetFile(uri, mimeType, opts, signal);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        if ("controlled-open".equals(uri.getLastPathSegment())) {
            controlledEntered = true;
            try {
                // Deliberately ignore CancellationSignal, but bound the fixture if its test fails.
                if (!controlledRelease.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    throw new FileNotFoundException("Controlled fixture release timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("Controlled fixture interrupted");
            }
            return new AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        if ("blocking-open-cancel".equals(uri.getLastPathSegment())) {
            if (signal != null) signal.setOnCancelListener(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            try { Thread.sleep(2400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new FileNotFoundException("Synthetic blocked source");
        }
        return super.openAssetFile(uri, mode, signal);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        if ("denied".equals(uri.getLastPathSegment())) throw new SecurityException("Denied fixture");
        if ("ejected".equals(uri.getLastPathSegment())) throw new FileNotFoundException("Ejected source device");
        try {
            if ("mid-write-fault".equals(uri.getLastPathSegment())) {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try (FileOutputStream out = new FileOutputStream(pipe[1].getFileDescriptor())) {
                        byte[] chunk = new byte[8192];
                        out.write(chunk);
                        out.flush();
                        pipe[1].closeWithError("Injected mid-write stream fault");
                    } catch (Exception ignored) { }
                }).start();
                return pipe[0];
            }
            if ("oversized".equals(uri.getLastPathSegment())) {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (FileOutputStream out = new FileOutputStream(pipe[1].getFileDescriptor())) {
                        byte[] chunk = new byte[65536];
                        for (int i = 0; i < 257; i++) out.write(chunk);
                    } catch (Exception ignored) { } finally { try { pipe[1].close(); } catch (IOException ignored) { } }
                }).start();
                return pipe[0];
            }
            if ("slow".equals(uri.getLastPathSegment())) {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> { try { Thread.sleep(2000); pipe[1].close(); } catch (Exception ignored) {} }).start();
                return pipe[0];
            }
            String seg = uri.getLastPathSegment();
            if (seg != null && (seg.endsWith(".mp3") || seg.contains("mp3") || "track10.mp3".equals(seg))) {
                File file = new File(getContext().getFilesDir(), "fixture-track10.mp3");
                if (!file.exists()) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        for (int i = 0; i < 200000; i++) out.write((i + 10) % 251);
                    }
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (seg != null && (seg.endsWith(".wav") || seg.contains("wav") || "track2.wav".equals(seg))) {
                File file = new File(getContext().getFilesDir(), "fixture-track2.wav");
                if (!file.exists()) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        for (int i = 0; i < 300000; i++) out.write((i + 2) % 251);
                    }
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if ("cover.jpg".equals(uri.getLastPathSegment())) {
                File file = new File(getContext().getFilesDir(), "fixture-cover.jpg");
                if (!file.exists()) {
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
                    }
                    bmp.recycle();
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if ("notes.txt".equals(uri.getLastPathSegment())) {
                File file = new File(getContext().getFilesDir(), "fixture-notes.txt");
                if (!file.exists()) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write("Audiobook notes and description".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            File file = new File(getContext().getFilesDir(), "import-test-source");
            if (!file.exists()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    for (int i = 0; i < 150_000; i++) out.write(i % 251);
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
}
