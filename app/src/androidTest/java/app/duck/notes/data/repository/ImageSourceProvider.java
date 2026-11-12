package app.duck.notes.data.repository;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Separate-UID test provider: Java avoids the target-only Kotlin runtime stripped from test APKs. */
public final class ImageSourceProvider extends ContentProvider {
    public static final String AUTHORITY = "app.duck.notes.test.images";
    private volatile CountDownLatch opened = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            Context owner = Objects.requireNonNull(getContext());
            Uri base = Uri.parse("content://" + AUTHORITY + "/");
            switch (method) {
                case "grant": owner.grantUriPermission("app.duck.notes", base,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); break;
                case "revoke": owner.revokeUriPermission(base, Intent.FLAG_GRANT_READ_URI_PERMISSION); break;
                case "reset":
                    opened = new CountDownLatch(1); release = new CountDownLatch(1);
                    File fixture = new File(owner.getCacheDir(), "provider-image.png");
                    if (fixture.exists() && !fixture.delete()) throw new IllegalStateException("Synthetic image reset failed");
                    break;
                case "release": release.countDown(); break;
            }
            Bundle result = new Bundle();
            result.putBoolean("opened", opened.getCount() == 0);
            return result;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            if ("blocked".equals(uri.getLastPathSegment())) {
                opened.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Synthetic provider was not released");
            }
            if ("interrupted".equals(uri.getLastPathSegment())) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try { pipe[1].closeWithError("Synthetic interrupted image stream"); }
                    catch (IOException ignored) { }
                }).start();
                return pipe[0];
            }
            File source = new File(Objects.requireNonNull(getContext()).getCacheDir(), "provider-image.png");
            if (!source.isFile()) {
                Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
                try (FileOutputStream output = new FileOutputStream(source)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Synthetic image failed");
                } finally { bitmap.recycle(); }
            }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FileNotFoundException("Synthetic provider interrupted");
        } catch (IOException failure) {
            throw new FileNotFoundException("Synthetic provider unavailable");
        }
    }
}
