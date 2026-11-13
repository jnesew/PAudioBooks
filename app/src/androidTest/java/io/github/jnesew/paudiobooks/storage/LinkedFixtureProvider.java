package io.github.jnesew.paudiobooks.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test APK only. Each test owns the seed; reads never mutate its bytes. */
public final class LinkedFixtureProvider extends ContentProvider {
    private volatile CountDownLatch release = new CountDownLatch(0);
    private volatile boolean entered;
    private volatile long size;
    private android.os.HandlerThread proxyThread;
    @Override public boolean onCreate() { proxyThread = new android.os.HandlerThread("synthetic-linked-proxy"); proxyThread.start(); return true; }
    private File source() { return new File(getContext().getFilesDir(), "linked-range-fixture"); }
    private File source(Uri uri) {
        String id = uri.getLastPathSegment();
        return id != null && id.matches("format-[a-f0-9-]{36}")
            ? new File(getContext().getCacheDir(), id) : source();
    }
    private long length(Uri uri) { return source(uri).equals(source()) ? size : source(uri).length() - 40; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            switch (method) {
                case "seed-format": {
                    java.util.UUID.fromString(arg);
                    Uri uri = Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture/format-" + arg);
                    String name = extras.getString("name");
                    if (name == null || !name.matches("[a-z0-9.-]{1,80}")) throw new IllegalArgumentException("Invalid fixture name");
                    ParcelFileDescriptor descriptor = extras.getParcelable("descriptor");
                    if (descriptor == null) throw new IllegalArgumentException("Missing fixture descriptor");
                    // Descriptor transport avoids Binder's byte-array transaction limit. Each
                    // fixture has its own backing file and an intentionally nonzero AFD range.
                    try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                         FileOutputStream output = new FileOutputStream(source(uri))) {
                        output.write(new byte[17]); byte[] buffer = new byte[32768]; long total = 0; int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > 8L * 1024 * 1024) throw new java.io.IOException("Fixture exceeds test bound");
                            output.write(buffer, 0, count);
                        }
                        if (total == 0) throw new java.io.IOException("Empty fixture");
                        output.write(new byte[23]);
                    }
                    getContext().getSharedPreferences("linked_format_fixtures",0).edit().putString(arg,name).commit();
                    getContext().grantUriPermission("io.github.jnesew.paudiobooks",uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    break;
                }
                case "seed-sparse-format": {
                    java.util.UUID.fromString(arg);
                    Uri uri=Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture/format-"+arg);
                    ParcelFileDescriptor fd=extras.getParcelable("descriptor");
                    long start=extras.getLong("moovOffset");
                    if (fd==null || fd.getStatSize()<=Integer.MAX_VALUE || fd.getStatSize()>3L*1024*1024*1024 || start<65536 || fd.getStatSize()-start>32L*1024*1024) throw new IllegalArgumentException("Invalid sparse test fixture");
                    long size=fd.getStatSize();
                    try (java.io.FileInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);
                         java.io.RandomAccessFile output=new java.io.RandomAccessFile(source(uri),"rw")) {
                        output.setLength(0);output.write(new byte[17]);
                        byte[] buffer=new byte[32768];long remaining=65536;
                        while (remaining>0) { int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new java.io.IOException("Truncated fixture");output.write(buffer,0,n);remaining-=n; }
                        input.getChannel().position(start);output.seek(17+start);remaining=size-start;
                        while (remaining>0) { int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new java.io.IOException("Truncated fixture");output.write(buffer,0,n);remaining-=n; }
                        output.write(new byte[23]);
                    }
                    getContext().getSharedPreferences("linked_format_fixtures",0).edit().putString(arg,"long-index.m4b").commit();
                    getContext().grantUriPermission("io.github.jnesew.paudiobooks",uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    break;
                }
                case "discard-format": {
                    java.util.UUID.fromString(arg);
                    Uri uri = Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture/format-" + arg);
                    getContext().revokeUriPermission("io.github.jnesew.paudiobooks",uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    source(uri).delete();
                    getContext().getSharedPreferences("linked_format_fixtures",0).edit().remove(arg).commit();
                    break;
                }
                case "seed":
                    byte[] bytes = extras.getByteArray("bytes");
                    try (FileOutputStream stream = new FileOutputStream(source())) {
                        stream.write(new byte[17]); stream.write(bytes); stream.write(new byte[23]);
                    }
                    size = bytes.length; break;
                case "arm":
                    if (release.getCount() != 0) throw new IllegalStateException("Already armed");
                    entered = false; release = new CountDownLatch(1); break;
                case "release": release.countDown(); break;
                case "state": out.putBoolean("entered", entered); break;
                default: throw new IllegalArgumentException("Unknown test action");
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Fixture setup failed"); }
        return out;
    }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
        if ("denied".equals(uri.getLastPathSegment())) throw new SecurityException("PRIVATE_PROVIDER_URI:" + uri);
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i=0; i<columns.length; i++) {
            if (OpenableColumns.SIZE.equals(columns[i])) row[i] = length(uri);
            else if (OpenableColumns.DISPLAY_NAME.equals(columns[i]) && uri.getLastPathSegment().startsWith("format-"))
                row[i] = getContext().getSharedPreferences("linked_format_fixtures",0).getString(uri.getLastPathSegment().substring(7),"fixture.wav");
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(columns[i])) row[i] = 1000L;
        }
        cursor.addRow(row); return cursor;
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri,mode,null);
    }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mime, Bundle opts, CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri,"r",signal);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            if ("blocked".equals(uri.getLastPathSegment())) {
                entered = true;
                if (!release.await(15, TimeUnit.SECONDS)) throw new FileNotFoundException("Test release deadline");
            }
            if ("blocked-read".equals(uri.getLastPathSegment())) {
                final android.os.storage.StorageManager manager = getContext().getSystemService(android.os.storage.StorageManager.class);
                ParcelFileDescriptor fd = manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,new android.os.ProxyFileDescriptorCallback() {
                    @Override public long onGetSize() { return source().length(); }
                    @Override public int onRead(long offset,int count,byte[] data) throws android.system.ErrnoException {
                        entered = true;
                        try {
                            if (!release.await(15,TimeUnit.SECONDS)) throw new java.io.IOException();
                            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(source(),"r")) {
                                file.seek(offset); int actual=file.read(data,0,count); return Math.max(0,actual);
                            }
                        } catch (Exception e) { throw new android.system.ErrnoException("synthetic read",android.system.OsConstants.EIO); }
                    }
                    @Override public void onRelease() { }
                },new android.os.Handler(proxyThread.getLooper()));
                return new AssetFileDescriptor(fd,17,size);
            }
            if ("pipe".equals(uri.getLastPathSegment())) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); pipe[1].close();
                return new AssetFileDescriptor(pipe[0],0,AssetFileDescriptor.UNKNOWN_LENGTH);
            }
            long declared = "overflow".equals(uri.getLastPathSegment()) ? Long.MAX_VALUE : length(uri);
            return new AssetFileDescriptor(ParcelFileDescriptor.open(source(uri),ParcelFileDescriptor.MODE_READ_ONLY),17,declared);
        } catch (Exception e) { throw new FileNotFoundException("Fixture open failed"); }
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] args) { throw new UnsupportedOperationException(); }
}
