package io.github.jnesew.paudiobooks.portability;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Synthetic, test-package-only provider; never reads the target application's library. */
public final class TransferFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        // ContentProvider's default typed-read path calls this overload.
        return openAssetFile(uri, mode, null);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        if ("denied".equals(uri.getLastPathSegment())) throw new SecurityException("content://private/source");
        if ("slow-open".equals(uri.getLastPathSegment())) {
            try { Thread.sleep(600); } catch (InterruptedException ignored) { }
        }
        try {
            File file = new File(getContext().getCacheDir(), "transfer-fixture.json");
            if (!file.exists()) try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                output.write("{\"version\":2,\"books\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode)), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (java.io.IOException error) { throw new FileNotFoundException("Fixture unavailable"); }
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
