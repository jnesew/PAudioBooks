package io.github.jnesew.paudiobooks.importing;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test APK only: real local PCM/artwork and a real SAF tree for batch journeys. */
public final class BatchDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "io.github.jnesew.paudiobooks.test.batchdocuments";
    private static final String TARGET = "io.github.jnesew.paudiobooks";
    private static final String[] ROOT_COLUMNS = { "root_id", "document_id", "title", "flags", "mime_types" };
    private static final String[] DOC_COLUMNS = { "document_id", "_display_name", "mime_type", "_size", "flags" };

    static void seedMetadata(Context context, String id, byte[] bytes) {
        if (!id.matches("metadata-seeded-(chapters|credits|cover)") || bytes==null || bytes.length>256*1024 || bytes.length<10) throw new IllegalArgumentException("Invalid synthetic metadata seed");
        if (bytes[0]!='I' || bytes[1]!='D' || bytes[2]!='3') throw new IllegalArgumentException("Expected generated ID3 fixture");
        int oldSize=((bytes[6]&127)<<21)|((bytes[7]&127)<<14)|((bytes[8]&127)<<7)|(bytes[9]&127);
        if (oldSize+10>bytes.length) throw new IllegalArgumentException("Truncated synthetic ID3");
        int padding=3*1024*1024, size=oldSize+padding;
        byte[] header=java.util.Arrays.copyOf(bytes,10);
        header[6]=(byte)((size>>21)&127); header[7]=(byte)((size>>14)&127); header[8]=(byte)((size>>7)&127); header[9]=(byte)(size&127);
        File file=new File(context.getCacheDir(),"synthetic-batch-"+id);
        try (FileOutputStream out=new FileOutputStream(file)) {
            out.write(header); out.write(bytes,10,oldSize); out.write(new byte[padding]); out.write(bytes,10+oldSize,bytes.length-10-oldSize);
        } catch (IOException error) { throw new IllegalStateException("Synthetic metadata seed failed"); }
    }

    @Override public boolean onCreate() { return true; }

    /** Called through the existing unprivileged fixture provider in its owning test UID. */
    static void configure(Context context, String method, String arg) {
        if (method.equals("batch-fail-b")) {
            context.getSharedPreferences("synthetic_batch_provider", 0).edit().putBoolean("fail-b", "true".equals(arg)).commit();
            return;
        }
        if (method.equals("linked-rescan")) {
            String[] parts=arg.split("\\|",-1);
            if (parts.length!=2 || !parts[0].matches("linked-test-rescan-[a-z0-9-]+-root")) throw new IllegalArgumentException("Invalid scan fixture");
            int mode=Integer.parseInt(parts[1]);
            if (mode<0 || mode>3) throw new IllegalArgumentException("Invalid scan mode");
            context.getSharedPreferences("synthetic_batch_provider",0).edit().putInt("scan:"+parts[0],mode).commit();
            if (mode==2) {
                File file=new File(context.getCacheDir(),"synthetic-batch-scan-b:"+parts[0]);
                if (!file.isFile()) throw new IllegalStateException("Expected existing source before change");
                try (java.io.RandomAccessFile changed=new java.io.RandomAccessFile(file,"rw")) { changed.setLength(32046); }
                catch (IOException e) { throw new IllegalStateException("Synthetic change failed"); }
            }
            return;
        }
        if (method.equals("linked-file-grant")) {
            Uri uri=Uri.parse(arg);
            if (!AUTHORITY.equals(uri.getAuthority()) || !DocumentsContract.isTreeUri(uri) || !DocumentsContract.getTreeDocumentId(uri).startsWith("linked-test-")) throw new IllegalArgumentException("Not a scoped synthetic source");
            context.grantUriPermission(TARGET,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            return;
        }
        if (method.startsWith("linked-")) {
            String root = arg != null && arg.matches("linked-test-[a-z0-9-]+-root") ? arg : "linked-root";
            Uri uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY,root);
            if (method.equals("linked-grant")) context.grantUriPermission(TARGET,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            else context.revokeUriPermission(TARGET,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return;
        }
        for (String root : new String[]{"batch-root", "mixed-root"}) {
            Uri uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, root);
            if (method.equals("batch-grant")) context.grantUriPermission(TARGET, uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            else if (method.equals("batch-revoke")) context.revokeUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else throw new IllegalArgumentException("Unknown batch fixture action");
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = c.newRow();
        put(row, c, "root_id", "synthetic-batch"); put(row, c, "document_id", "batch-root");
        put(row, c, "title", "Synthetic batch books");
        put(row, c, "flags", DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        put(row, c, "mime_types", "audio/wav\nimage/png");
        row = c.newRow();
        put(row,c,"root_id","synthetic-linked"); put(row,c,"document_id","linked-root");
        put(row,c,"title","Synthetic linked books");
        put(row,c,"flags",DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        put(row,c,"mime_types","audio/wav\nimage/png");
        return c;
    }

    private String[] children(String id) throws FileNotFoundException {
        if (id.startsWith("linked-test-rescan-") && id.endsWith("-root")) return new String[]{"scan-book:"+id,"book-b"};
        if (id.startsWith("scan-book:")) {
            String root=id.substring("scan-book:".length());
            int mode=getContext().getSharedPreferences("synthetic_batch_provider",0).getInt("scan:"+root,0);
            if (mode==3) throw new FileNotFoundException("Synthetic interrupted folder");
            return mode==0 ? new String[]{"scan-a:"+root,"scan-b:"+root} : new String[]{"scan-b:"+root,"scan-c:"+root};
        }
        if (id.startsWith("linked-test-life-") && id.endsWith("-root")) return new String[]{"linked-life-book"};
        if (id.startsWith("linked-test-fair-") && id.endsWith("-root")) return new String[]{"linked-fair-book"};
        if (id.startsWith("linked-test-tagged-") && id.endsWith("-root")) return new String[]{"linked-tagged-book"};
        if (id.startsWith("linked-test-metadata-") && id.endsWith("-root")) return new String[]{"linked-metadata-book"};
        if (id.startsWith("linked-test-cost-") && id.endsWith("-root")) return new String[]{"linked-cost-book"};
        if (id.startsWith("linked-test-playback-") && id.endsWith("-root")) return new String[]{"linked-book-a","linked-book-b"};
        if (id.startsWith("linked-test-") && id.endsWith("-root")) return new String[]{"book-a","book-b"};
        switch (id) {
            case "batch-root": return new String[]{"book-a", "book-b"};
            case "linked-root": return new String[]{"linked-book-a","linked-book-b"};
            case "linked-life-book": return new String[]{"linked-life-a","linked-life-b"};
            case "linked-fair-book": return new String[]{"metadata-limit-0","metadata-limit-1","metadata-limit-2","metadata-limit-3","metadata-small"};
            case "linked-tagged-book": return new String[]{"metadata-seeded-chapters","metadata-seeded-credits","metadata-seeded-cover"};
            case "linked-metadata-book": return new String[]{"metadata-small","metadata-large"};
            case "linked-cost-book": return new String[]{"linked-large"};
            case "linked-book-a": return new String[]{"linked-long-a"};
            case "linked-book-b": return new String[]{"linked-long-b"};
            case "book-a": return new String[]{"a-02", "a-01", "cover-a"};
            case "book-b": return new String[]{"b-01", "cover-b"};
            case "mixed-root": return new String[]{"root-audio", "disc-1", "disc-2"};
            case "disc-1": return new String[]{"d1-01"};
            case "disc-2": return new String[]{"d2-01"};
            default: throw new FileNotFoundException();
        }
    }

    private static String name(String id) throws FileNotFoundException {
        if (id.matches("metadata-limit-[0-3]")) return "0"+id.substring(id.length()-1)+"-limit.wav";
        if (id.startsWith("scan-book:")) return "Scanned book";
        if (id.startsWith("scan-a:")) return "01.wav";
        if (id.startsWith("scan-b:")) return "02.wav";
        if (id.startsWith("scan-c:")) return "00-new.wav";
        if (id.startsWith("linked-test-") && id.endsWith("-root")) return "Synthetic isolated linked books";
        switch (id) {
            case "batch-root": return "Synthetic batch books";
            case "linked-root": return "Synthetic linked books";
            case "linked-cost-book": return "Synthetic cost fixture";
            case "metadata-seeded-chapters": return "03-chapters.mp3";
            case "metadata-seeded-credits": return "04-credits.mp3";
            case "metadata-seeded-cover": return "05-cover.mp3";
            case "linked-life-book": return "Synthetic linked lifecycle";
            case "linked-life-a": return "01.wav";
            case "linked-life-b": return "02.wav";
            case "linked-fair-book": return "Deferred fairness fixtures";
            case "linked-tagged-book": return "Deferred tagged fixtures";
            case "linked-metadata-book": return "Deferred metadata fixtures";
            case "metadata-small": return "01-3MiB-junk.wav";
            case "metadata-large": return "02-72MiB-junk.wav";
            case "linked-large": return "64MiB.wav";
            case "linked-book-a": return "Linked A";
            case "linked-book-b": return "Linked B";
            case "linked-long-a": case "linked-long-b": return "01.wav";
            case "book-a": return "Book A";
            case "book-b": return "Book B";
            case "mixed-root": return "Mixed root";
            case "disc-1": return "CD1";
            case "disc-2": return "CD2";
            case "cover-a": case "cover-b": return "cover.png";
            case "a-02": return "02.wav";
            case "a-01": case "b-01": case "d1-01": case "d2-01": return "01.wav";
            case "root-audio": return "Root.wav";
            default: throw new FileNotFoundException();
        }
    }

    private static boolean directory(String id) {
        return id.startsWith("scan-book:") || id.startsWith("linked-test-") && id.endsWith("-root") || id.startsWith("linked-book-") || id.equals("linked-life-book") || id.equals("linked-fair-book") || id.equals("linked-tagged-book") || id.equals("linked-metadata-book") || id.equals("linked-cost-book") || id.startsWith("book-") || id.startsWith("disc-") || id.equals("batch-root") || id.equals("mixed-root") || id.equals("linked-root");
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_COLUMNS : projection); add(c, id); return c;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        for (String id : children(parent)) add(c, id);
        return c;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        if (parent.equals(child)) return true;
        try {
            for (String id : children(parent)) if (id.equals(child) || (directory(id) && isChildDocument(id, child))) return true;
        } catch (FileNotFoundException ignored) { }
        return false;
    }

    private void add(MatrixCursor c, String id) throws FileNotFoundException {
        MatrixCursor.RowBuilder row = c.newRow();
        put(row, c, "document_id", id); put(row, c, "_display_name", name(id));
        put(row, c, "mime_type", directory(id) ? DocumentsContract.Document.MIME_TYPE_DIR : id.startsWith("cover-") ? "image/png" : "audio/wav");
        put(row, c, "_size", directory(id) ? null : file(id).length());
        put(row, c, "flags", 0);
    }
    private static void put(MatrixCursor.RowBuilder row, MatrixCursor c, String key, Object value) {
        if (c.getColumnIndex(key) >= 0) row.add(key, value);
    }

    private synchronized File file(String id) throws FileNotFoundException {
        // Version only the oversized fixtures: install-r preserves the previous
        // 18MiB cache, which no longer exceeds the current inspection ceiling.
        boolean oversized = id.equals("metadata-large") || id.matches("metadata-limit-[0-3]");
        File file = new File(getContext().getCacheDir(), "synthetic-batch-" + (oversized ? "v2-" : "") + id);
        if (file.isFile()) return file;
        if (id.equals("metadata-small") || id.equals("metadata-large") || id.matches("metadata-limit-[0-3]")) {
            // Generated PCM with a large legal RIFF JUNK chunk before fmt/data.
            // The 3MiB file exceeds addition's 2MiB budget; 72MiB exceeds the per-pass 64MiB cap.
            int junk=(id.equals("metadata-small") ? 3 : 72)*1024*1024;
            ByteBuffer prefix=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            prefix.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(junk+32044)
                .put("WAVEJUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(junk);
            ByteBuffer audio=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            audio.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(32000);
            try (java.io.RandomAccessFile output=new java.io.RandomAccessFile(file,"rw")) {
                output.write(prefix.array()); output.seek(20L+junk); output.write(audio.array()); output.setLength(52L+junk+32000);
            } catch (IOException error) { file.delete(); throw new FileNotFoundException("Synthetic deferred fixture unavailable"); }
            return file;
        }
        if (id.equals("linked-large")) {
            int size=64*1024*1024;
            ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size+36)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size);
            try (java.io.RandomAccessFile output=new java.io.RandomAccessFile(file,"rw")) {
                output.write(header.array()); output.setLength(size+44L);
            } catch (IOException error) { file.delete(); throw new FileNotFoundException("Synthetic sparse fixture unavailable"); }
            return file;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (id.startsWith("cover-")) {
                Bitmap bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
                try { bitmap.eraseColor(id.equals("cover-a") ? 0xffbc3228 : 0xff267d47); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                finally { bitmap.recycle(); }
            } else {
                int size = id.startsWith("linked-life-") ? 1920000 : id.startsWith("linked-long-") ? 480000 : id.equals("b-01") ? 48000 : 32000;
                ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size + 36)
                    .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                    .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(size);
                out.write(header.array()); byte[] pcm = new byte[size];
                pcm[0] = (byte) id.hashCode(); pcm[1] = (byte) (id.hashCode() >> 8); out.write(pcm);
            }
        } catch (IOException error) { file.delete(); throw new FileNotFoundException("Synthetic file unavailable"); }
        return file;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation) throws FileNotFoundException {
        name(id);
        if (!mode.equals("r") || directory(id)) throw new FileNotFoundException();
        if (cancellation != null) cancellation.throwIfCanceled();
        if (id.equals("b-01") && getContext().getSharedPreferences("synthetic_batch_provider", 0).getBoolean("fail-b", false)) {
            throw new FileNotFoundException("Synthetic Book B is unavailable");
        }
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
