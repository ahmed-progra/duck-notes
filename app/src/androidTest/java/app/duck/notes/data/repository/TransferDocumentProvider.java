package app.duck.notes.data.repository;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.os.CancellationSignal;
import android.content.res.AssetFileDescriptor;

/** Synthetic documents cross a real provider/UID boundary; no target-app test seam supplies bytes. */
public final class TransferDocumentProvider extends ContentProvider {
    public static final String AUTHORITY = "app.duck.notes.test.documents";
    private int opened;
    private int deleted;
    private volatile CountDownLatch release = new CountDownLatch(1);
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { deleted++; return 1; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            Context owner = Objects.requireNonNull(getContext());
            Uri base = Uri.parse("content://" + AUTHORITY + "/");
            Bundle result = new Bundle();
            if ("grant".equals(method)) owner.grantUriPermission("app.duck.notes", base,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            if ("revoke".equals(method)) owner.revokeUriPermission(base, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ("reset".equals(method)) { opened = 0; deleted = 0; release = new CountDownLatch(1); }
            if ("release".equals(method)) release.countDown();
            if ("source".equals(method)) Files.write(new File(owner.getCacheDir(), "transfer-source").toPath(), Objects.requireNonNull(extras).getByteArray("bytes"));
            if ("output".equals(method)) result.putByteArray("bytes", Files.readAllBytes(new File(owner.getCacheDir(), "transfer-output").toPath()));
            if ("reset_shared".equals(method)) {
                for (String name : new String[]{"received-export", "received-export.part", "received-type", "received-error"}) Files.deleteIfExists(new File(owner.getCacheDir(), name).toPath());
            }
            if ("shared".equals(method)) {
                File received = new File(owner.getCacheDir(), "received-export");
                File error = new File(owner.getCacheDir(), "received-error");
                if (received.isFile()) {
                    result.putByteArray("bytes", Files.readAllBytes(received.toPath()));
                    result.putString("type", new String(Files.readAllBytes(new File(owner.getCacheDir(), "received-type").toPath()), java.nio.charset.StandardCharsets.UTF_8));
                }
                if (error.isFile()) result.putString("error", new String(Files.readAllBytes(error.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            }
            result.putInt("opened", opened);
            result.putInt("deleted", deleted);
            return result;
        } catch (IOException failure) { throw new IllegalStateException("Synthetic document unavailable", failure); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        return new AssetFileDescriptor(openFile(uri, mode, signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mime, Bundle options, CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri, "r", signal);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        opened++;
        Context owner = Objects.requireNonNull(getContext());
        String path = uri.getLastPathSegment();
        if ("blocked".equals(path)) {
            if (signal == null) throw new IllegalStateException("Synthetic provider did not receive Android cancellation signal");
            try { while (!release.await(25, TimeUnit.MILLISECONDS)) { if (signal != null) signal.throwIfCanceled(); } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Synthetic open interrupted"); }
        }
        if ("interrupted".equals(path) || "blockedstream".equals(path) || "partial".equals(path)) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try {
                        if ("partial".equals(path)) {
                            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) { input.read(new byte[16]); pipe[0].closeWithError("Synthetic partial write"); }
                        } else {
                            if ("blockedstream".equals(path)) release.await(15, TimeUnit.SECONDS);
                            pipe[1].closeWithError("Synthetic source interrupted");
                        }
                    } catch (Exception ignored) { }
                }).start();
                return "partial".equals(path) ? pipe[1] : pipe[0];
            } catch (IOException failure) { throw new FileNotFoundException("Synthetic pipe failed"); }
        }
        if ("full".equals(path)) return ParcelFileDescriptor.open(new File("/dev/full"), ParcelFileDescriptor.MODE_WRITE_ONLY);
        if ("fail".equals(path)) throw new FileNotFoundException("Synthetic provider unavailable");
        File target = new File(owner.getCacheDir(), "r".equals(mode) ? "transfer-source" : "transfer-output");
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }
}
