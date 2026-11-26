package org.lsposed.lspatch.loader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.JsonReader;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.lsposed.lspatch.share.Constants;

/**
 * Authenticated ContentProvider bundled in meta-loader (base APK dex) so LSPatch Manager
 * can cleanly back up and restore internal app data (/data/data/<pkg>) without root,
 * even when Storage Redirection is disabled.
 */
public class LSPatchBackupProvider extends ContentProvider {

    private static final String TAG = "LSPatch-BackupProvider";

    private static final Set<String> SKIP_TOP_DIRS =
            new HashSet<>(Arrays.asList("cache", "code_cache", "lib"));

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) {
            throw new IllegalStateException("Context is null");
        }
        verifyCaller(ctx);

        Bundle result = new Bundle();
        if ("ping".equals(method)) {
            result.putBoolean("ok", true);
            result.putString("dataDir", ctx.getApplicationInfo().dataDir);
            return result;
        }
        if ("listInternalDirs".equals(method)) {
            java.util.ArrayList<String> dirs = new java.util.ArrayList<>();
            File dataDir = new File(ctx.getApplicationInfo().dataDir);
            File[] children = dataDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (SKIP_TOP_DIRS.contains(child.getName())) continue;
                    dirs.add(child.getName());
                }
            }
            java.util.Collections.sort(dirs);
            result.putBoolean("ok", true);
            result.putStringArrayList("dirs", dirs);
            return result;
        }

        if (extras == null) {
            throw new IllegalArgumentException("Missing extras");
        }
        ParcelFileDescriptor pfd = extras.getParcelable("pfd");
        if (pfd == null) {
            throw new IllegalArgumentException("Missing pfd");
        }

        try {
            if ("backup".equals(method)) {
                java.util.ArrayList<String> includeDirs = extras.getStringArrayList("includeDirs");
                int count = performBackup(ctx, pfd, includeDirs);
                result.putBoolean("ok", true);
                result.putInt("fileCount", count);
                return result;
            } else if ("restore".equals(method)) {
                int count = performRestore(ctx, pfd);
                result.putBoolean("ok", true);
                result.putInt("fileCount", count);
                return result;
            }
            throw new IllegalArgumentException("Unknown method: " + method);
        } catch (Throwable t) {
            Log.e(TAG, "BackupProvider call failed: " + method, t);
            result.putBoolean("ok", false);
            result.putString("error", t.getMessage());
            return result;
        }
    }

    private void verifyCaller(Context ctx) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == 0 || uid == 2000) {
            return;
        }
        String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) {
            throw new SecurityException("Unauthorized UID: " + uid);
        }
        String configuredManager = readConfiguredManagerPackage(ctx);
        for (String pkg : pkgs) {
            if (Constants.MANAGER_PACKAGE_NAME.equals(pkg)
                    || "org.expirt.lspatch".equals(pkg)
                    || "io.github.expirt.lspatch".equals(pkg)
                    || "org.lsposed.lspatch".equals(pkg)
                    || (configuredManager != null && configuredManager.equals(pkg))) {
                return;
            }
        }
        throw new SecurityException("Unauthorized caller package for UID " + uid);
    }

    private String readConfiguredManagerPackage(Context ctx) {
        try (var is = ctx.getAssets().open("lspatch/config.json");
                var reader = new JsonReader(new InputStreamReader(is))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("managerPackageName".equals(name)) {
                    return reader.nextString();
                }
                reader.skipValue();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int performBackup(Context ctx, ParcelFileDescriptor pfd, java.util.List<String> includeDirs)
            throws Exception {
        ApplicationInfo ai = ctx.getApplicationInfo();
        File dataDir = new File(ai.dataDir);
        Set<String> includeSet = (includeDirs != null && !includeDirs.isEmpty()) ? new HashSet<>(includeDirs) : null;
        int[] count = new int[] {0};
        try (ParcelFileDescriptor autoClose = pfd;
                FileOutputStream fos = new FileOutputStream(autoClose.getFileDescriptor());
                ZipOutputStream zos = new ZipOutputStream(fos)) {
            File[] children = dataDir.listFiles();
            if (children != null) {
                byte[] buf = new byte[16384];
                for (File child : children) {
                    if (SKIP_TOP_DIRS.contains(child.getName())) continue;
                    if (includeSet != null && !includeSet.contains(child.getName())) continue;
                    zipRecursive(child, "internal/" + child.getName(), zos, buf, count);
                }
            }
            zos.finish();
        }
        Log.i(TAG, "performBackup: wrote " + count[0] + " files for " + ctx.getPackageName());
        return count[0];
    }

    private void zipRecursive(File file, String entryPath, ZipOutputStream zos, byte[] buf, int[] count)
            throws Exception {
        if (entryPath.equals("internal/no_backup/lspatch") || entryPath.startsWith("internal/no_backup/lspatch/")) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null || children.length == 0) {
                zos.putNextEntry(new ZipEntry(entryPath + "/"));
                zos.closeEntry();
                return;
            }
            for (File c : children) {
                zipRecursive(c, entryPath + "/" + c.getName(), zos, buf, count);
            }
        } else if (file.isFile()) {
            zos.putNextEntry(new ZipEntry(entryPath));
            try (FileInputStream fis = new FileInputStream(file)) {
                int r;
                while ((r = fis.read(buf)) != -1) {
                    zos.write(buf, 0, r);
                }
            }
            zos.closeEntry();
            count[0]++;
        }
    }

    private int performRestore(Context ctx, ParcelFileDescriptor pfd) throws Exception {
        ApplicationInfo ai = ctx.getApplicationInfo();
        File dataDir = new File(ai.dataDir);
        String canonicalRoot = dataDir.getCanonicalPath() + File.separator;

        // Clean existing mutable internal data while preserving runtime/cache/lspatch state
        File[] existing = dataDir.listFiles();
        if (existing != null) {
            for (File child : existing) {
                if (SKIP_TOP_DIRS.contains(child.getName())) continue;
                if ("no_backup".equals(child.getName())) {
                    File[] nbChildren = child.listFiles();
                    if (nbChildren != null) {
                        for (File nb : nbChildren) {
                            if (!"lspatch".equals(nb.getName())) {
                                deleteRecursive(nb);
                            }
                        }
                    }
                    continue;
                }
                deleteRecursive(child);
            }
        }

        int count = 0;
        try (ParcelFileDescriptor autoClose = pfd;
                FileInputStream fis = new FileInputStream(autoClose.getFileDescriptor());
                ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry ze;
            byte[] buf = new byte[16384];
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                if (name == null || !name.startsWith("internal/")) {
                    zis.closeEntry();
                    continue;
                }
                String rel = name.substring("internal/".length());
                if (rel.isEmpty()
                        || rel.startsWith("cache/")
                        || rel.startsWith("code_cache/")
                        || rel.startsWith("lib/")
                        || rel.startsWith("no_backup/lspatch")) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(dataDir, rel);
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
                    zis.closeEntry();
                    continue;
                }
                if (ze.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
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
        Log.i(TAG, "performRestore: restored " + count + " files for " + ctx.getPackageName());
        return count;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
