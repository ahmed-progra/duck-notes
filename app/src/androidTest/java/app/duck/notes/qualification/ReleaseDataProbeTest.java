package app.duck.notes.qualification;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Opt-in snapshot, not a startup/security guard. Real signed apps and UI seed the data first.
 * No app implementation classes are linked, so the same signed test APK can inspect older versions.
 */
@SdkSuppress(minSdkVersion = 35)
public final class ReleaseDataProbeTest {
    @Test public void createSharedSyntheticImage() throws Exception {
        String label = InstrumentationRegistry.getArguments().getString("snapshot_label");
        assumeTrue("Opt-in signed-upgrade fixture", label != null);
        assertTrue(Objects.requireNonNull(label).matches("[a-zA-Z0-9-]{1,48}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "duck-v2-" + label + ".jpg");
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DuckV2Qualification/");
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        android.net.Uri uri = Objects.requireNonNull(context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values));
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(20, 30, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.rgb(215, 73, 76));
        try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, Objects.requireNonNull(output)));
        } finally { bitmap.recycle(); }
        try (android.os.ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
            @SuppressWarnings("deprecation") android.media.ExifInterface exif = new android.media.ExifInterface(Objects.requireNonNull(descriptor).getFileDescriptor());
            exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2030:01:02 12:34:56");
            exif.setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
            exif.saveAttributes();
        }
        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
        assertEquals(1, context.getContentResolver().update(uri, values, null, null));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = Objects.requireNonNull(input).read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        report(context, label, new JSONObject().put("uri", uri.toString()).put("sha256", hex(digest.digest())));
    }

    @Test public void captureExistingApplicationData() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String label = arguments.getString("snapshot_label");
        assumeTrue("Opt-in signed-upgrade inspection", label != null);
        assertTrue(Objects.requireNonNull(label).matches("[a-zA-Z0-9-]{1,48}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("app.duck.notes", context.getPackageName());
        assertEquals(context.getApplicationInfo().uid, Process.myUid());
        JSONObject result = new JSONObject();
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        result.put("versionName", info.versionName).put("versionCode", info.getLongVersionCode());
        result.put("certificateSha256", hex(MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray())));
        File database = context.getDatabasePath("duck.db");
        assertTrue("Positive control: the real database is missing", database.isFile());
        JSONObject tables = new JSONObject();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            for (String table : new String[] {"notes", "attachments", "tags", "note_tags", "note_links"}) {
                JSONArray rows = new JSONArray();
                try (Cursor cursor = db.rawQuery("SELECT * FROM " + table + " ORDER BY 1, 2", null)) {
                    while (cursor.moveToNext()) {
                        JSONObject row = new JSONObject();
                        for (int i = 0; i < cursor.getColumnCount(); i++) {
                            Object value;
                            switch (cursor.getType(i)) {
                                case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                                case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(i); break;
                                case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(i); break;
                                case Cursor.FIELD_TYPE_BLOB: value = android.util.Base64.encodeToString(cursor.getBlob(i), android.util.Base64.NO_WRAP); break;
                                default: value = cursor.getString(i);
                            }
                            row.put(cursor.getColumnName(i), value);
                        }
                        rows.put(row);
                    }
                }
                tables.put(table, rows);
            }
        }
        assertTrue("Positive control: the snapshot contains no notes", tables.getJSONArray("notes").length() > 0);
        JSONObject images = new JSONObject();
        JSONArray rows = tables.getJSONArray("attachments");
        File attachmentRoot = new File(context.getFilesDir(), "attachments").getCanonicalFile();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String location = row.getString("uri");
            File file = location.startsWith("/") ? new File(location) : new File(context.getFilesDir(), location);
            file = file.getCanonicalFile();
            assertEquals("Attachment escapes owned storage", attachmentRoot, file.getParentFile());
            assertEquals("Attachment identity mismatch", row.getString("id"), file.getName());
            assertTrue("Referenced attachment is missing", file.isFile());
            images.put(row.getString("id"), hash(file));
        }
        File preferences = new File(context.getFilesDir(), "datastore/settings.preferences_pb");
        result.put("tables", tables).put("imageSha256", images);
        result.put("settingsSha256", preferences.isFile() ? hash(preferences) : JSONObject.NULL);
        report(context, label, result);
    }

    private static void report(Context context, String label, JSONObject result) throws Exception {
        File directory = Objects.requireNonNull(context.getExternalFilesDir("qualification"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, label + ".json");
        Files.write(output.toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
        Bundle report = new Bundle();
        report.putString("snapshot_file", output.getAbsolutePath());
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
}
