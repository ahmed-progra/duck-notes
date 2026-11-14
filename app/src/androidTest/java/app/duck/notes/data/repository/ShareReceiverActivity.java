package app.duck.notes.data.repository;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Local-only chooser target. Its separate UID must receive an actual Android read grant. */
public final class ShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null || !"app.duck.notes.exports".equals(uri.getAuthority()) || getIntent().getClipData() == null ||
                !uri.equals(getIntent().getClipData().getItemAt(0).getUri())) throw new IllegalStateException("Synthetic share URI missing");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Synthetic share unreadable");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 2 * 1024 * 1024) throw new IllegalStateException("Synthetic share too large");
                    bytes.write(buffer, 0, count);
                }
            }
            boolean denied = false;
            try (android.os.ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "w")) { }
            catch (SecurityException expected) { denied = true; }
            if (!denied) throw new IllegalStateException("Synthetic viewer received write access");
            Files.write(new File(getCacheDir(), "received-type").toPath(), String.valueOf(getIntent().getType()).getBytes(StandardCharsets.UTF_8));
            File pending = new File(getCacheDir(), "received-export.part");
            Files.write(pending.toPath(), bytes.toByteArray());
            Files.move(pending.toPath(), new File(getCacheDir(), "received-export").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            try { Files.write(new File(getCacheDir(), "received-error").toPath(), failure.getClass().getName().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
        } finally { finish(); }
    }
}
