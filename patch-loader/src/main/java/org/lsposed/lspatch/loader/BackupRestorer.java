package org.lsposed.lspatch.loader;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.lsposed.lspatch.share.Constants;

/**
 * Unpacks an embedded backup archive ({@code assets/lspatch/backup.zip}) once on first launch
 * of an Integrated patched APK.
 */
public final class BackupRestorer {

    private static final String TAG = "LSPatch-Backup";

    private BackupRestorer() {}

    public static void restoreEmbeddedBackupIfNeeded(Context context, JSONObject config) {
        if (context == null || config == null) return;
        String sourceDir = context.getApplicationInfo() != null ? context.getApplicationInfo().sourceDir : null;
        if (sourceDir == null) return;

        try (ZipFile apkZip = new ZipFile(sourceDir)) {
            ZipEntry backupEntry = apkZip.getEntry(Constants.BACKUP_ASSET_PATH);
            if (backupEntry == null) return;

            File lspatchDir = new File(context.getNoBackupFilesDir(), "lspatch");
            if (!lspatchDir.exists()) lspatchDir.mkdirs();

            File marker = new File(lspatchDir, "backup_restored_" + backupEntry.getCrc());
            if (marker.exists()) {
                return;
            }

            Log.i(TAG, "Unpacking embedded backup archive (crc=" + backupEntry.getCrc() + ")...");
            File dataDir = new File(context.getApplicationInfo().dataDir);
            boolean srEnabled = config.optBoolean("storageRedirectEnabled", false);
            String containerTpl = config.optString("containerPath", "Containers/{pkg}");
            if (containerTpl.isEmpty()) containerTpl = "Containers/{pkg}";
            String resolvedContainer = containerTpl.replace("{pkg}", context.getPackageName());
            File containerRoot = resolvedContainer.startsWith("/")
                    ? new File(resolvedContainer)
                    : new File(Environment.getExternalStorageDirectory(), resolvedContainer);

            File internalTarget = srEnabled ? new File(containerRoot, "internal") : dataDir;
            File externalTarget = srEnabled
                    ? new File(containerRoot, "external/Android/data/" + context.getPackageName())
                    : context.getExternalFilesDir(null) != null
                            ? context.getExternalFilesDir(null).getParentFile()
                            : null;

            String internalCanonical = internalTarget.getCanonicalPath() + File.separator;
            String externalCanonical = externalTarget != null ? externalTarget.getCanonicalPath() + File.separator : null;

            int count = 0;
            try (InputStream is = apkZip.getInputStream(backupEntry);
                    ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry ze;
                byte[] buf = new byte[16384];
                while ((ze = zis.getNextEntry()) != null) {
                    String name = ze.getName();
                    if (name == null || name.equals("backup_meta.json")) {
                        zis.closeEntry();
                        continue;
                    }
                    File outFile = null;
                    String rootCanonical = null;
                    if (name.startsWith("internal/")) {
                        String rel = name.substring("internal/".length());
                        if (!rel.isEmpty() && !rel.startsWith("cache/") && !rel.startsWith("code_cache/")) {
                            outFile = new File(internalTarget, rel);
                            rootCanonical = internalCanonical;
                        }
                    } else if (name.startsWith("external/") && externalTarget != null) {
                        String rel = name.substring("external/".length());
                        if (!rel.isEmpty()) {
                            outFile = new File(externalTarget, rel);
                            rootCanonical = externalCanonical;
                        }
                    }
                    if (outFile == null || rootCanonical == null) {
                        zis.closeEntry();
                        continue;
                    }
                    if (!outFile.getCanonicalPath().startsWith(rootCanonical)) {
                        zis.closeEntry();
                        continue;
                    }
                    if (ze.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int r;
                            while ((r = zis.read(buf)) != -1) {
                                fos.write(buf, 0, r);
                            }
                        }
                        count++;
                    }
                    zis.closeEntry();
                }
            }
            marker.createNewFile();
            Log.i(TAG, "Restored " + count + " file(s) from embedded backup");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to restore embedded backup", t);
        }
    }
}
