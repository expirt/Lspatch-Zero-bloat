package org.lsposed.patch;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.EMBEDDED_MODULES_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.LOADER_DEX_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.PROXY_APP_COMPONENT_FACTORY;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.google.gson.Gson;
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.NodeValue;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.io.FilenameUtils;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.share.LSPConfig;
import org.lsposed.lspatch.share.PatchConfig;
import org.lsposed.patch.microg.MicroGPatcher;
import org.lsposed.patch.util.ApkSignatureHelper;
import org.lsposed.patch.util.Logger;
import org.lsposed.patch.util.ManifestParser;
import pxb.android.axml.AxmlParser;
import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;

/**
 * Turns an apk into one that loads Xposed modules, without root.
 *
 * The principle is unchanged and is the whole of why LSPatch works: the original apk is kept intact
 * *inside* the patched one, as a nested zip at {@link Constants#ORIGINAL_APK_ASSET_PATH}, and the
 * manifest is rewritten so Android instantiates LSPatch's own {@code AppComponentFactory} first. At
 * runtime that factory brings the framework up and then hands control to the original application,
 * which loads from the nested copy and so sees itself byte-for-byte as its author shipped it. Every
 * entry that does not need changing is a *link* into the nested copy rather than a second copy of
 * the bytes, which is what keeps a patched apk close to the size of the original.
 *
 * What has changed is the shape. This used to be a single 150-line method on the command-line entry
 * point, which meant the engine could only be driven by parsing argv and every failure mode was a
 * {@code throw} from somewhere in the middle of it. The steps below are the same steps in the same
 * order, named, and reported through {@link Logger#stage} so a caller can show progress without
 * pattern-matching the log text.
 */
public final class ApkPatcher {

    private static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";

    /**
     * The native library is carried per-architecture under assets, never under {@code lib/}.
     *
     * An x86 device running an arm build through the native bridge would try to load the wrong one
     * out of {@code lib/} and crash; keeping them in assets means the loader picks by itself.
     */
    private static final Set<String> ARCHES =
            new LinkedHashSet<>(Arrays.asList("armeabi-v7a", "arm64-v8a", "x86", "x86_64"));

    /**
     * Page-aligned entries.
     *
     * The native library and the nested original both have to be mappable straight out of the apk,
     * which requires them to start on a 4 KiB boundary.
     */
    private static final ZFileOptions Z_FILE_OPTIONS = new ZFileOptions()
            .setAlignmentRule(AlignmentRules.compose(
                    AlignmentRules.constantForSuffix(".so", 16384), AlignmentRules.constantForSuffix(".arsc", 4)));

    private final Logger logger;
    private final PatchSpec spec;

    public ApkPatcher(Logger logger, PatchSpec spec) {
        this.logger = logger;
        this.spec = spec;
        logger.verbose = spec.verbose();
    }

    /**
     * Patches every apk in the spec and returns what was written, in the order given.
     *
     * A multi-apk app is patched as a set because its splits have to agree: only the apk carrying
     * the application element is rewritten, and the rest are repacked so that they stay installable
     * alongside it.
     */
    public List<File> patch() throws IOException {
        List<File> outputs = new ArrayList<>();
        File outputDir = spec.outputDir();
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new PatchException("Cannot create output directory " + outputDir);
        }
        int index = 0;
        int total = spec.apks().size();
        for (File apk : spec.apks()) {
            index++;
            File src = apk.getAbsoluteFile();
            File output = outputFileFor(src);
            if (output.exists() && !spec.forceOverwrite()) {
                throw new PatchException(output + " exists. Use --force to overwrite");
            }
            logger.i("Processing " + src + " -> " + output);
            patchOne(src, output, index, total);
            outputs.add(output);
        }
        return outputs;
    }

    /**
     * Where a given input apk's result is written.
     *
     * {@link Locale#ROOT}, not the default locale: the version code is formatted into the file name,
     * and a locale with its own digits (Arabic-Indic, say) would produce a name the manager's own
     * suffix matching no longer recognises.
     */
    private File outputFileFor(File src) {
        String name = String.format(
                Locale.ROOT,
                "%s-%d-lspatched.apk",
                FilenameUtils.getBaseName(src.getName()),
                LSPConfig.instance.VERSION_CODE);
        return new File(spec.outputDir(), name).getAbsoluteFile();
    }

    private void patchOne(File srcApkFile, File outputFile, int index, int total) throws IOException {
        if (!srcApkFile.exists()) {
            throw new PatchException("The source apk file does not exist: " + srcApkFile);
        }

        // The output is opened read-write and grown in place, so anything already there would be
        // treated as a partially built apk rather than replaced.
        if (outputFile.exists() && !outputFile.delete()) {
            throw new PatchException("Cannot overwrite " + outputFile);
        }

        logger.d("apk path: " + srcApkFile);
        logger.stage(Logger.Stage.READING, index, total);
        logger.i("Parsing original apk...");

        try (ZFile dstZFile = ZFile.openReadWrite(outputFile, Z_FILE_OPTIONS);
                ZFile srcZFile = ZFile.openReadOnly(srcApkFile)) {

            registerSigner(dstZFile, index, total);

            String originalSignature = readOriginalSignature(srcApkFile);
            ManifestParser.Pair manifest = readManifest(srcZFile);

            // Only the apk carrying the application element gets the loader; a split that declares
            // no appComponentFactory has nothing to hook, so it is repacked and left otherwise
            // untouched. A lone apk is never treated this way, whatever it is called.
            boolean isSplit = spec.apks().size() > 1
                    && srcApkFile.getName().startsWith("split_")
                    && manifest.appComponentFactory == null;
            if (isSplit) {
                packSplit(srcZFile, dstZFile, index, total);
                return;
            }

            PatchConfig config = new PatchConfig(
                    spec.useManager(),
                    spec.debuggable(),
                    spec.manifestOverrides().versionCode,
                    spec.sigBypassLevel(),
                    originalSignature,
                    manifest.appComponentFactory,
                    true,
                    spec.manifestOverrides().addedPermissions.toArray(new String[0]),
                    spec.manifestOverrides().injectDocumentsProvider,
                    spec.manifestOverrides().gmsReplacementPackage,
                    spec.manifestOverrides().declareFakeSignature,
                    spec.useManager() ? spec.managerPackageName() : null,
                    spec.manifestOverrides().storageRedirectEnabled,
                    spec.manifestOverrides().containerPath,
                    spec.manifestOverrides().storageSpoofing,
                    spec.manifestOverrides().redirectInternal,
                    spec.manifestOverrides().redirectExternal,
                    spec.manifestOverrides().redirectDatabases,
                    spec.manifestOverrides().redirectPreferences,
                    spec.manifestOverrides().redirectConstructors,
                    spec.manifestOverrides().redirectLogging,
                    spec.manifestOverrides().useMicroG,
                    spec.manifestOverrides().microGVendor,
                    spec.manifestOverrides().keepAbis.isEmpty()
                            ? null
                            : spec.manifestOverrides().keepAbis.toArray(new String[0]),
                    spec.manifestOverrides().sslUnpinning,
                    spec.manifestOverrides().crashDebugger,
                    spec.manifestOverrides().customRules.isEmpty()
                            ? null
                            : spec.manifestOverrides().customRules.toArray(new String[0]),
                    spec.manifestOverrides().newPackageName,
                    spec.manifestOverrides().accessibilityDummy,
                    spec.manifestOverrides().features);
            byte[] configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);

            rewriteManifest(
                    srcZFile,
                    dstZFile,
                    configBytes,
                    manifest.packageName,
                    manifest.minSdkVersion,
                    originalSignature,
                    index,
                    total);
            injectLoader(srcZFile, dstZFile, index, total);
            if (!spec.useManager()) {
                addLoaderPayload(dstZFile);
                embedModules(dstZFile, index, total);
            }
            if (spec.manifestOverrides().storageRedirectEnabled
                    || (spec.manifestOverrides().features != null
                            && spec.manifestOverrides().features.needsNativeHooks())) {
                addStoragePayload(dstZFile);
            }
            if (spec.manifestOverrides().embeddedBackupPath != null) {
                addEmbeddedBackup(dstZFile, spec.manifestOverrides().embeddedBackupPath);
            }
            copyRemainingEntries(srcZFile, dstZFile);

            if (spec.manifestOverrides().useMicroG) {
                MicroGPatcher.process(dstZFile, spec.manifestOverrides().microGVendor, logger);
            }

            if (spec.manifestOverrides().appIconPath != null) {
                replaceAppIcon(srcZFile, dstZFile, spec.manifestOverrides().appIconPath);
            }

            dstZFile.realign();

            // Everything after this point -- deflation, realignment, v2 signing -- happens as the
            // zip is closed, and emits nothing for tens of seconds on a large app. A caller showing
            // progress has to know that this is where the silence is.
            logger.stage(Logger.Stage.WRITING, index, total);
            logger.i("Writing apk...");
        }
        logger.stage(Logger.Stage.DONE, index, total);
        logger.i("Done. Output APK: " + outputFile.getAbsolutePath());
    }

    /** Registers the v2 signer, so the finished zip is signed as it is closed. */
    private void registerSigner(ZFile dstZFile, int index, int total) throws IOException {
        logger.stage(Logger.Stage.SIGNING, index, total);
        logger.i(
                spec.keystore().isBuiltIn()
                        ? "Register apk signer with default keystore..."
                        : "Register apk signer with custom keystore...");
        try {
            KeyStore keyStore = spec.keystore().load(getClass().getClassLoader());
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                    spec.keystore().alias(),
                    new KeyStore.PasswordProtection(
                            spec.keystore().aliasPassword().toCharArray()));
            if (entry == null) {
                throw new PatchException("No key named '" + spec.keystore().alias() + "' in the keystore");
            }
            new SigningExtension(SigningOptions.builder()
                            .setMinSdkVersion(28)
                            .setV2SigningEnabled(true)
                            .setCertificates((X509Certificate[]) entry.getCertificateChain())
                            .setKey(entry.getPrivateKey())
                            .build())
                    .register(dstZFile);
        } catch (PatchException e) {
            throw e;
        } catch (Exception e) {
            throw new PatchException("Failed to register signer", e);
        }
    }

    /**
     * The original app's signature, recorded so the loader can report it back to the app.
     *
     * Only read when signature bypass, a Google Play services redirect or the fake signature is on: a
     * patched apk is signed with LSPatch's key, and an app that checks its own signature -- or a
     * replacement reporting the app to Google -- would otherwise notice. Nothing needs it when all are off.
     */
    private String readOriginalSignature(File srcApkFile) throws IOException {
        if (spec.sigBypassLevel() <= Constants.SIGBYPASS_LV_DISABLE
                && spec.manifestOverrides().gmsReplacementPackage == null
                && !spec.manifestOverrides().declareFakeSignature
                && !spec.manifestOverrides().useMicroG) {
            return null;
        }
        String signature = ApkSignatureHelper.getApkSignInfo(srcApkFile.getAbsolutePath());
        if (signature == null || signature.isEmpty()) {
            throw new PatchException("Could not read the original apk's signature");
        }
        logger.d("Original signature\n" + signature);
        return signature;
    }

    private ManifestParser.Pair readManifest(ZFile srcZFile) throws IOException {
        StoredEntry manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
        if (manifestEntry == null) {
            throw new PatchException("Provided file is not a valid apk");
        }
        try (InputStream is = manifestEntry.open()) {
            ManifestParser.Pair pair = ManifestParser.parseManifestFile(is);
            if (pair == null) {
                throw new PatchException("Failed to parse AndroidManifest.xml");
            }
            if (PROXY_APP_COMPONENT_FACTORY.equals(pair.appComponentFactory)) {
                logger.i("Source APK is already patched; recovering true appComponentFactory...");
                StoredEntry configEntry = srcZFile.get(CONFIG_ASSET_PATH);
                String recoveredFactory = null;
                if (configEntry != null) {
                    try (InputStream cfgIs = configEntry.open()) {
                        byte[] cfgBytes = cfgIs.readAllBytes();
                        com.google.gson.JsonObject obj = new Gson()
                                .fromJson(
                                        new String(cfgBytes, StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                        if (obj != null
                                && obj.has("appComponentFactory")
                                && !obj.get("appComponentFactory").isJsonNull()) {
                            String f = obj.get("appComponentFactory").getAsString();
                            if (f != null && !f.isEmpty() && !f.equals(PROXY_APP_COMPONENT_FACTORY)) {
                                recoveredFactory = f;
                            }
                        }
                    } catch (Throwable t) {
                        logger.d("Failed to read previous config.json: " + t.getMessage());
                    }
                }
                pair.appComponentFactory = recoveredFactory;
            }
            logger.d("original appComponentFactory class: " + pair.appComponentFactory);
            logger.d("original minSdkVersion: " + pair.minSdkVersion);
            return pair;
        }
    }

    /** Repacks a split unchanged, dropping only the signatures that no longer apply to it. */
    private void packSplit(ZFile srcZFile, ZFile dstZFile, int index, int total) throws IOException {
        logger.stage(Logger.Stage.PACKING_SPLIT, index, total);
        logger.i("Packing split apk...");
        dstZFile.mergeFrom(
                srcZFile,
                name -> dstZFile.get(name) != null || isSignatureEntry(name) || name.equals(ORIGINAL_APK_ASSET_PATH));
    }

    /**
     * Points the manifest at LSPatch's component factory and records the patch config in it.
     *
     * The config is written twice on purpose: into the manifest as metadata, where the *manager* can
     * read it from an installed app without opening the apk, and into an asset, where the *loader*
     * reads it at startup without a package manager.
     */
    private void rewriteManifest(
            ZFile srcZFile,
            ZFile dstZFile,
            byte[] configBytes,
            String packageName,
            int minSdkVersion,
            String originalSignature,
            int index,
            int total)
            throws IOException {
        logger.stage(Logger.Stage.REWRITING, index, total);
        logger.i("Patching apk...");
        String metadata = Base64.getEncoder().encodeToString(configBytes);
        StoredEntry manifestEntry = Objects.requireNonNull(srcZFile.get(ANDROID_MANIFEST_XML));
        try (InputStream is = new ByteArrayInputStream(
                modifyManifestFile(manifestEntry.open(), metadata, packageName, minSdkVersion, originalSignature))) {
            dstZFile.add(ANDROID_MANIFEST_XML, is);
        } catch (IOException e) {
            throw new PatchException("Error when modifying manifest", e);
        }

        logger.i("Adding config...");
        try (InputStream is = new ByteArrayInputStream(configBytes)) {
            dstZFile.add(CONFIG_ASSET_PATH, is);
        } catch (IOException e) {
            throw new PatchException("Error when saving config", e);
        }
    }

    /**
     * Adds the metaloader dex as the companion dex after the last dex file.
     */
    private void injectLoader(ZFile srcZFile, ZFile dstZFile, int index, int total) throws IOException {
        logger.stage(Logger.Stage.INJECTING, index, total);
        logger.i("Adding metaloader dex...");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(Constants.META_LOADER_DEX_ASSET_PATH)) {
            if (is == null) throw new PatchException("The metaloader dex is missing from this build");
            int maxDex = 0;
            for (StoredEntry entry : srcZFile.entries()) {
                String name = entry.getCentralDirectoryHeader().getName();
                if (isDexEntry(name)) {
                    if (isMetaloaderDex(entry)) {
                        logger.i("Found previously injected metaloader dex (" + name + "), skipping");
                        continue;
                    }
                    if (name.equals("classes.dex")) {
                        if (maxDex < 1) maxDex = 1;
                    } else {
                        try {
                            int idx = Integer.parseInt(name.substring(7, name.length() - 4));
                            if (idx > maxDex) maxDex = idx;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            int stubDexIndex = maxDex + 1;
            String stubDexName = stubDexIndex == 1 ? "classes.dex" : "classes" + stubDexIndex + ".dex";
            dstZFile.add(stubDexName, is);
        } catch (PatchException e) {
            throw e;
        } catch (IOException e) {
            throw new PatchException("Error when adding dex", e);
        }
    }

    /** The framework itself: the loader dex and its native library, for a manager-free patch. */
    private void addLoaderPayload(ZFile dstZFile) throws IOException {
        logger.i("Adding loader dex...");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(LOADER_DEX_ASSET_PATH)) {
            if (is == null) throw new PatchException("The loader dex is missing from this build");
            dstZFile.add(LOADER_DEX_ASSET_PATH, is);
        } catch (PatchException e) {
            throw e;
        } catch (IOException e) {
            throw new PatchException("Error when adding assets", e);
        }

        logger.i("Adding native lib...");
        for (String arch : ARCHES) {
            if (!spec.manifestOverrides().keepAbis.isEmpty()
                    && !spec.manifestOverrides().keepAbis.contains(arch)) {
                logger.d("Skipping loader native lib for stripped arch: " + arch);
                continue;
            }
            String entryName = "assets/lspatch/so/" + arch + "/liblspatch.so";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(entryName)) {
                if (is == null) throw new PatchException("Missing native library for " + arch);
                // Stored, not deflated: it is mapped straight out of the apk at runtime.
                dstZFile.add(entryName, is, false);
            } catch (PatchException e) {
                throw e;
            } catch (IOException e) {
                throw new PatchException("Error when adding native lib for " + arch, e);
            }
            logger.d("added " + entryName);

            String storageEntryName = "assets/lspatch/so/" + arch + "/liblspatch_storage.so";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(storageEntryName)) {
                if (is != null) {
                    dstZFile.add(storageEntryName, is, false);
                    logger.d("added " + storageEntryName);
                }
            } catch (Throwable t) {
                logger.d("Failed to add " + storageEntryName + ": " + t.getMessage());
            }
        }
    }

    private void addStoragePayload(ZFile dstZFile) throws IOException {
        logger.i("Adding storage spoofing native lib...");
        for (String arch : ARCHES) {
            if (!spec.manifestOverrides().keepAbis.isEmpty()
                    && !spec.manifestOverrides().keepAbis.contains(arch)) {
                logger.d("Skipping storage native lib for stripped arch: " + arch);
                continue;
            }
            String storageEntryName = "assets/lspatch/so/" + arch + "/liblspatch_storage.so";
            if (dstZFile.get(storageEntryName) != null) {
                continue;
            }
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(storageEntryName)) {
                if (is != null) {
                    dstZFile.add(storageEntryName, is, false);
                    logger.d("added " + storageEntryName);
                }
            } catch (Throwable t) {
                logger.d("Failed to add " + storageEntryName + ": " + t.getMessage());
            }
        }
    }

    /** Embeds a backup archive into integrated APKs for automatic restore on first launch. */
    private void addEmbeddedBackup(ZFile dstZFile, String backupPath) throws IOException {
        File backupFile = new File(backupPath);
        if (!backupFile.exists() || !backupFile.isFile()) {
            logger.e("Embedded backup file not found: " + backupPath);
            return;
        }
        logger.i("Embedding backup data: " + backupFile.getName());
        try (InputStream is = new java.io.FileInputStream(backupFile)) {
            dstZFile.add(Constants.BACKUP_ASSET_PATH, is, false);
        } catch (IOException e) {
            throw new PatchException("Error when embedding backup archive", e);
        }
    }

    /**
     * Bakes each module apk in under its own package name.
     *
     * The entry name is the module's package, which is what the loader enumerates by -- so a module
     * appearing twice cannot be represented, and the first one wins. A module that cannot be read is
     * reported and skipped rather than failing the patch, because one bad module apk should not cost
     * the user the whole build.
     */
    private void embedModules(ZFile dstZFile, int index, int total) {
        if (spec.modules().isEmpty()) return;
        logger.stage(Logger.Stage.EMBEDDING, index, total);
        logger.i("Embedding modules...");
        Set<String> embedded = new LinkedHashSet<>();
        for (File module : spec.modules()) {
            try (ZFile apk = ZFile.openReadOnly(module);
                    InputStream fileIs = new java.io.FileInputStream(module);
                    InputStream xmlIs = Objects.requireNonNull(apk.get(ANDROID_MANIFEST_XML))
                            .open()) {
                ManifestParser.Pair manifest = Objects.requireNonNull(ManifestParser.parseManifestFile(xmlIs));
                String packageName = manifest.packageName;
                if (!embedded.add(packageName)) {
                    logger.e("  - " + packageName + " given more than once, keeping the first");
                    continue;
                }
                logger.i("  - " + packageName);
                dstZFile.add(EMBEDDED_MODULES_ASSET_PATH + packageName + ".apk", fileIs);
            } catch (NullPointerException | IOException e) {
                logger.e(module + " does not exist or is not a valid apk file.");
            }
        }
    }

    /**
     * Copies every remaining entry from the source APK directly into the destination APK,
     * preserving compression and alignment without embedding origin.apk.
     */
    private void copyRemainingEntries(ZFile srcZFile, ZFile dstZFile) throws IOException {
        logger.d("Merging remaining entries from original apk...");
        List<String> keepAbis = spec.manifestOverrides().keepAbis;
        dstZFile.mergeFrom(srcZFile, name -> {
            if (dstZFile.get(name) != null
                    || isSignatureEntry(name)
                    || name.equals(ANDROID_MANIFEST_XML)
                    || name.equals(ORIGINAL_APK_ASSET_PATH)
                    || name.startsWith("assets/lspatch/")
                    || (isDexEntry(name) && isMetaloaderDex(srcZFile.get(name)))) {
                return true;
            }
            if (!keepAbis.isEmpty() && name.startsWith("lib/")) {
                int firstSlash = name.indexOf('/');
                int secondSlash = name.indexOf('/', firstSlash + 1);
                if (secondSlash > firstSlash) {
                    String abi = name.substring(firstSlash + 1, secondSlash);
                    if (!keepAbis.contains(abi)) {
                        logger.d("Stripping native lib for excluded ABI (" + abi + "): " + name);
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static boolean isMetaloaderDex(StoredEntry entry) {
        if (entry == null) return false;
        long size = entry.getCentralDirectoryHeader().getUncompressedSize();
        if (size > 65536 || size <= 0) return false;
        try (InputStream is = entry.open()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) size);
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            String content = new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
            return content.contains("Lorg/lsposed/lspatch/metaloader/LSPAppComponentFactoryStub;");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDexEntry(String name) {
        return name.startsWith("classes") && name.endsWith(".dex");
    }

    private static boolean isSignatureEntry(String name) {
        return name.startsWith("META-INF/")
                && (name.endsWith(".SF")
                        || name.endsWith(".MF")
                        || name.endsWith(".RSA")
                        || name.endsWith(".DSA")
                        || name.endsWith(".EC")
                        || name.startsWith("META-INF/SIG-"));
    }

    private byte[] modifyManifestFile(
            InputStream is, String metadata, String packageName, int minSdkVersion, String originalSignature)
            throws IOException {
        ModificationProperty property = new ModificationProperty();

        // The loader is built against 28; an app declaring less would be refused the APIs it uses.
        if (minSdkVersion < 28) {
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, 28));
        }
        property.addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, spec.debuggable()));
        property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
        property.addMetaData(new ModificationProperty.MetaData("lspatch", metadata));
        String effectivePackageName = (spec.manifestOverrides().newPackageName != null
                && !spec.manifestOverrides().newPackageName.isEmpty())
                ? spec.manifestOverrides().newPackageName
                : packageName;
        addBackupProvider(property, effectivePackageName);
        applyManifestOverrides(property, packageName, originalSignature);
        // TODO: replace query_all with queries -> manager
        if (spec.useManager()) {
            property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream in = is) {
            new ManifestEditor(in, os, property).processManifest();
        }
        os.flush();
        byte[] manifestBytes = os.toByteArray();
        if (spec.manifestOverrides().useMicroG) {
            String microGPkg = spec.manifestOverrides().microGVendor + ".android.gms";
            manifestBytes = renameC2dmForGmsReplacement(manifestBytes, microGPkg);
            manifestBytes = addQueriesForMicroG(manifestBytes, microGPkg);
        } else if (spec.manifestOverrides().gmsReplacementPackage != null) {
            manifestBytes = renameC2dmForGmsReplacement(manifestBytes, spec.manifestOverrides().gmsReplacementPackage);
        }
        return manifestBytes;
    }

    /**
     * Applies the caller's chosen manifest overrides on top of the loader's own rewrites.
     *
     * Each is set only when the caller asked for it, so an app the user did not want changed keeps
     * every attribute exactly as its author wrote it. A version-code override decides what the
     * installer may replace the app with; a label override replaces the launcher name; a target-SDK
     * override changes the compatibility behaviours the platform applies; the two booleans flip
     * install-time and network policy an app otherwise fixes against a module's needs.
     */
    private void applyManifestOverrides(ModificationProperty property, String packageName, String originalSignature)
            throws IOException {
        ManifestOverrides o = spec.manifestOverrides();
        if (o.isEmpty()) return;
        String effectivePackageName = packageName;
        if (o.newPackageName != null && !o.newPackageName.isEmpty()) {
            effectivePackageName = o.newPackageName;
            logger.i("Override packageName (cloning): " + packageName + " -> " + o.newPackageName);
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.PACKAGE, o.newPackageName).setNamespace(null));
            final String oldPkg = packageName;
            final String newPkg = o.newPackageName;
            property.setAuthorityMapper(oldAuth -> {
                if (oldAuth == null) return null;
                if (oldAuth.contains(oldPkg)) {
                    return oldAuth.replace(oldPkg, newPkg);
                }
                return newPkg + "." + oldAuth;
            });
            property.setPermissionMapper((type, permission) -> {
                if (permission == null) return null;
                if (permission.contains(oldPkg)) {
                    return permission.replace(oldPkg, newPkg);
                }
                return permission;
            });
        }
        if (o.versionCode != null) {
            logger.i("Override versionCode: " + o.versionCode);
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, o.versionCode));
        }
        if (o.label != null) {
            logger.i("Override label: " + o.label);
            property.addApplicationAttribute(new AttributeItem(NodeValue.Application.LABEL, o.label));
        }
        if (o.targetSdkVersion != null) {
            logger.i("Override targetSdkVersion: " + o.targetSdkVersion);
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.TARGET_SDK_VERSION, o.targetSdkVersion));
        }
        if (o.extractNativeLibs != null) {
            logger.i("Override extractNativeLibs: " + o.extractNativeLibs);
            property.addApplicationAttribute(
                    new AttributeItem(NodeValue.Application.EXTRACTNATIVELIBS, o.extractNativeLibs));
        }
        if (o.usesCleartextTraffic != null) {
            logger.i("Override usesCleartextTraffic: " + o.usesCleartextTraffic);
            property.addApplicationAttribute(new AttributeItem("usesCleartextTraffic", o.usesCleartextTraffic));
        }
        // The editor keys uses-permission by name and drops a name the manifest already carries, so
        // handing it one the app declares is harmless -- the added set need not be filtered first.
        for (String permission : o.addedPermissions) {
            logger.i("Add permission: " + permission);
            property.addUsesPermission(permission);
        }
        if (o.injectDocumentsProvider) {
            addDocumentsProvider(property, effectivePackageName);
        }
        if (o.gmsReplacementPackage != null) {
            if (!Constants.isGmsReplacement(o.gmsReplacementPackage)) {
                throw new PatchException(o.gmsReplacementPackage + " cannot stand in for "
                        + Constants.REAL_GMS_PACKAGE_NAME + "; give the package of an installed replacement");
            }
            addGmsReplacementSupport(property, o.gmsReplacementPackage, originalSignature);
        }
        if (o.useMicroG) {
            String microGPkg = o.microGVendor + ".android.gms";
            logger.i("MicroG integration enabled: declaring signature metadata for " + microGPkg);
            if (originalSignature != null && !originalSignature.isEmpty()) {
                property.addMetaData(new ModificationProperty.MetaData(
                        microGPkg + Constants.GMS_REPLACEMENT_SIGNATURE_METADATA_SUFFIX, sha1Hex(originalSignature)));
            }
            property.addMetaData(new ModificationProperty.MetaData("app.revanced.MICROG_PACKAGE_NAME", microGPkg));
            property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");
        }
        if (o.storageRedirectEnabled) {
            logger.i("Storage redirection enabled: ensuring MANAGE_EXTERNAL_STORAGE permission");
            property.addUsesPermission("android.permission.MANAGE_EXTERNAL_STORAGE");
        }
        if (o.crashDebugger) {
            logger.i("Crash debugger enabled: ensuring POST_NOTIFICATIONS permission");
            property.addUsesPermission("android.permission.POST_NOTIFICATIONS");
        }
        if (o.declareFakeSignature) {
            logger.i("Add fake signature");
            property.addMetaData(new ModificationProperty.MetaData("fake-signature", originalSignature));
            property.addUsesPermission("android.permission.FAKE_PACKAGE_SIGNATURE");
        }
        if (o.accessibilityDummy) {
            addAccessibilityDummyService(property);
        }
    }

    /**
     * Declares the app's original certificate to the chosen replacement, which otherwise sees LSPatch's.
     *
     * A replacement reads {@code <its package>.SPOOFED_PACKAGE_SIGNATURE} from the calling app's manifest --
     * the certificate's SHA-1 in lower-case hex -- and needs no ROM support. The key is built from the package so
     * any such build is addressable, not one particular vendor.
     */
    private void addGmsReplacementSupport(
            ModificationProperty property, String gmsReplacementPackage, String originalSignature) {
        logger.i("Declare the original signature to " + gmsReplacementPackage);
        property.addMetaData(new ModificationProperty.MetaData(
                gmsReplacementPackage + Constants.GMS_REPLACEMENT_SIGNATURE_METADATA_SUFFIX,
                sha1Hex(originalSignature)));
    }

    /**
     * Renames every {@code com.google.android.c2dm.*} string in the manifest to the chosen replacement's
     * namespace, so the pushes it delivers reach the app. Returns the manifest unchanged when none was chosen,
     * and when the one chosen kept Google's C2DM names, which are then already the right ones.
     *
     * A replacement that renamed them looks the app's push receiver up by its own RECEIVE action, and the
     * receiver only
     * accepts a sender holding {@code com.google.android.c2dm.permission.SEND} -- a signature permission of
     * Google Play services that no other build can hold -- so both the intent-filter action and the permissions
     * have to move with it. The manifest editor maps permissions but never descends into intent filters, so this
     * is a pass of its own over every attribute. The target namespace is derived from the package name, so this
     * works for any such build; one whose package yields no namespace is left alone and reported.
     */
    private byte[] renameC2dmForGmsReplacement(byte[] manifest, String gmsReplacementPackage) throws IOException {
        if (gmsReplacementPackage == null) return manifest;
        String targetPrefix = Constants.gmsReplacementC2dmPrefix(gmsReplacementPackage);
        if (targetPrefix == null) {
            logger.i("Push names left alone: " + gmsReplacementPackage + " keeps Google's C2DM namespace");
            return manifest;
        }
        logger.i("Rename C2DM names to " + targetPrefix + "*");
        AxmlWriter writer = new AxmlWriter();
        new AxmlReader(manifest).accept(new AxmlVisitor(writer) {
            @Override
            public NodeVisitor child(String ns, String name) {
                return new C2dmRenamer(super.child(ns, name), targetPrefix);
            }
        });
        return writer.toByteArray();
    }

    private static final class C2dmRenamer extends NodeVisitor {
        private final String targetPrefix;

        C2dmRenamer(NodeVisitor nv, String targetPrefix) {
            super(nv);
            this.targetPrefix = targetPrefix;
        }

        @Override
        public NodeVisitor child(String ns, String name) {
            return new C2dmRenamer(super.child(ns, name), targetPrefix);
        }

        @Override
        public void attr(String ns, String name, int resourceId, int type, Object obj) {
            if (obj instanceof String && ((String) obj).startsWith(Constants.GOOGLE_C2DM_PREFIX)) {
                obj = targetPrefix + ((String) obj).substring(Constants.GOOGLE_C2DM_PREFIX.length());
            }
            super.attr(ns, name, resourceId, type, obj);
        }
    }

    private byte[] addQueriesForMicroG(byte[] manifest, String microGPkg) throws IOException {
        AxmlWriter writer = new AxmlWriter();
        final boolean[] hasQueries = new boolean[] {false};
        final boolean[] hasPackage = new boolean[] {false};

        AxmlReader reader = new AxmlReader(manifest);
        reader.accept(new AxmlVisitor(writer) {
            @Override
            public NodeVisitor child(String ns, String name) {
                if ("manifest".equals(name)) {
                    return new NodeVisitor(super.child(ns, name)) {
                        @Override
                        public NodeVisitor child(String childNs, String childName) {
                            if ("queries".equals(childName)) {
                                hasQueries[0] = true;
                                return new NodeVisitor(super.child(childNs, childName)) {
                                    @Override
                                    public NodeVisitor child(String pkgNs, String pkgName) {
                                        return new NodeVisitor(super.child(pkgNs, pkgName)) {
                                            @Override
                                            public void attr(
                                                    String aNs, String aName, int resourceId, int type, Object obj) {
                                                if (microGPkg.equals(obj)) {
                                                    hasPackage[0] = true;
                                                }
                                                super.attr(aNs, aName, resourceId, type, obj);
                                            }
                                        };
                                    }

                                    @Override
                                    public void end() {
                                        if (!hasPackage[0]) {
                                            NodeVisitor pkgNode = super.child(null, "package");
                                            pkgNode.attr(
                                                    "http://schemas.android.com/apk/res/android",
                                                    "name",
                                                    0x01010003,
                                                    NodeVisitor.TYPE_STRING,
                                                    microGPkg);
                                            pkgNode.end();
                                            hasPackage[0] = true;
                                        }
                                        super.end();
                                    }
                                };
                            }
                            return super.child(childNs, childName);
                        }

                        @Override
                        public void end() {
                            if (!hasQueries[0]) {
                                NodeVisitor queriesNode = super.child(null, "queries");
                                NodeVisitor pkgNode = queriesNode.child(null, "package");
                                pkgNode.attr(
                                        "http://schemas.android.com/apk/res/android",
                                        "name",
                                        0x01010003,
                                        NodeVisitor.TYPE_STRING,
                                        microGPkg);
                                pkgNode.end();
                                queriesNode.end();
                                hasQueries[0] = true;
                            }
                            super.end();
                        }
                    };
                }
                return super.child(ns, name);
            }
        });
        return writer.toByteArray();
    }

    /** SHA-1 of a certificate given as hex, as lower-case hex. */
    static String sha1Hex(String certificateHex) {
        byte[] certificate = new byte[certificateHex.length() / 2];
        for (int i = 0; i < certificate.length; i++) {
            certificate[i] = (byte) Integer.parseInt(certificateHex.substring(2 * i, 2 * i + 2), 16);
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(certificate);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Declares the loader's {@code DocumentsProvider} so the app's private data shows up in the
     * system file picker.
     *
     * The authority is per-package so two patched apps never collide. {@code MANAGE_DOCUMENTS} is the
     * platform-signature permission the Documents UI holds, so gating the provider behind it means
     * only the system can bind it -- access still flows through the user granting a tree, not through
     * any app reaching the authority directly. {@code exported} and {@code grantUriPermissions} are
     * passed as real booleans; a string {@code "true"} would be read back as false and quietly
     * un-export the provider.
     */
    private void addDocumentsProvider(ModificationProperty property, String packageName) {
        String authority = packageName + Constants.DOCUMENTS_PROVIDER_AUTHORITY_SUFFIX;
        logger.i("Add documents provider: " + authority);
        property.addDeleteProviderAuthorities(authority);
        List<AttributeItem> attributes = new ArrayList<>();
        attributes.add(new AttributeItem(NodeValue.Application.NAME, Constants.DOCUMENTS_PROVIDER_CLASS));
        attributes.add(new AttributeItem(NodeValue.Application.Provider.AUTHORITIES, authority));
        attributes.add(new AttributeItem("exported", Boolean.TRUE));
        attributes.add(new AttributeItem("grantUriPermissions", Boolean.TRUE));
        attributes.add(
                new AttributeItem(NodeValue.Application.Component.PERMISSION, "android.permission.MANAGE_DOCUMENTS"));
        property.addProvider(attributes, "android.content.action.DOCUMENTS_PROVIDER");
    }

    /**
     * Declares LSPatch's authenticated backup/restore ContentProvider so the manager can access
     * the app's private data directory cleanly even when storage redirection is off.
     */
    private void addBackupProvider(ModificationProperty property, String packageName) {
        String authority = packageName + Constants.BACKUP_PROVIDER_AUTHORITY_SUFFIX;
        property.addDeleteProviderAuthorities(authority);
        List<AttributeItem> attributes = new ArrayList<>();
        attributes.add(new AttributeItem(NodeValue.Application.NAME, Constants.BACKUP_PROVIDER_CLASS));
        attributes.add(new AttributeItem(NodeValue.Application.Provider.AUTHORITIES, authority));
        attributes.add(new AttributeItem("exported", Boolean.TRUE));
        property.addProvider(attributes, null);
    }

    private void addAccessibilityDummyService(ModificationProperty property) {
        logger.i("Add dummy accessibility service: " + Constants.DUMMY_ACCESSIBILITY_SERVICE_CLASS);
        List<AttributeItem> attributes = new ArrayList<>();
        attributes.add(new AttributeItem(NodeValue.Application.NAME, Constants.DUMMY_ACCESSIBILITY_SERVICE_CLASS));
        attributes.add(new AttributeItem(NodeValue.Application.Component.PERMISSION, "android.permission.BIND_ACCESSIBILITY_SERVICE"));
        attributes.add(new AttributeItem("exported", Boolean.TRUE));
        property.addService(attributes, "android.accessibilityservice.AccessibilityService");
    }

    private void replaceAppIcon(ZFile srcZFile, ZFile dstZFile, String iconPath) {
        try {
            logger.i("Replacing app icon with custom image: " + iconPath);
            File iconFile = new File(iconPath);
            byte[] iconPngBytes = loadIconAsPngBytes(iconFile);
            if (iconPngBytes == null || iconPngBytes.length == 0) {
                logger.e("Failed to read custom icon bytes; skipping icon replacement");
                return;
            }

            // 1. Parse AndroidManifest.xml from dstZFile to find all icon resource IDs
            Set<Integer> iconResIds = new LinkedHashSet<>();
            StoredEntry manifestEntry = dstZFile.get(ANDROID_MANIFEST_XML);
            if (manifestEntry == null) manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
            if (manifestEntry != null) {
                try (InputStream is = manifestEntry.open()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    AxmlParser parser = new AxmlParser(baos.toByteArray());
                    while (true) {
                        int type = parser.next();
                        if (type == AxmlParser.END_FILE) break;
                        if (type == AxmlParser.START_TAG) {
                            String tagName = parser.getName();
                            if ("application".equals(tagName) || "activity".equals(tagName) || "activity-alias".equals(tagName)) {
                                int attrCount = parser.getAttributeCount();
                                for (int i = 0; i < attrCount; i++) {
                                    int attrRes = parser.getAttrResId(i);
                                    if (attrRes == 0x01010002 || attrRes == 0x0101052c) { // icon or roundIcon
                                        Object val = parser.getAttrValue(i);
                                        if (val instanceof Integer && ((Integer) val) != 0) {
                                            iconResIds.add((Integer) val);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    logger.d("Failed to parse manifest icon attributes: " + t.getMessage());
                }
            }

            logger.i("Discovered icon resource IDs from manifest: " + iconResIds);

            // 2. Read resources.arsc to resolve file paths
            StoredEntry arscEntry = dstZFile.get("resources.arsc");
            if (arscEntry == null) arscEntry = srcZFile.get("resources.arsc");
            Set<String> matchedIconFiles = new LinkedHashSet<>();
            Set<String> xmlIconFiles = new LinkedHashSet<>();

            if (arscEntry != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = arscEntry.open()) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                }
                byte[] arscBytes = baos.toByteArray();
                ArscParser arscParser = new ArscParser(arscBytes);
                Set<String> resolvedFiles = arscParser.resolveResourceFiles(iconResIds);
                matchedIconFiles.addAll(resolvedFiles);

                // Check if any resolved file is an adaptive-icon XML, and resolve child drawables
                Set<Integer> childResIds = new LinkedHashSet<>();
                for (String file : resolvedFiles) {
                    if (file.endsWith(".xml")) {
                        xmlIconFiles.add(file);
                        StoredEntry xmlEntry = dstZFile.get(file);
                        if (xmlEntry == null) xmlEntry = srcZFile.get(file);
                        if (xmlEntry != null) {
                            try (InputStream is = xmlEntry.open()) {
                                ByteArrayOutputStream xBaos = new ByteArrayOutputStream();
                                byte[] xBuf = new byte[8192];
                                int xr;
                                while ((xr = is.read(xBuf)) != -1) {
                                    xBaos.write(xBuf, 0, xr);
                                }
                                AxmlParser xmlParser = new AxmlParser(xBaos.toByteArray());
                                while (true) {
                                    int type = xmlParser.next();
                                    if (type == AxmlParser.END_FILE) break;
                                    if (type == AxmlParser.START_TAG) {
                                        int attrCount = xmlParser.getAttributeCount();
                                        for (int i = 0; i < attrCount; i++) {
                                            int attrRes = xmlParser.getAttrResId(i);
                                            if (attrRes == 0x01010199) { // android:drawable
                                                Object val = xmlParser.getAttrValue(i);
                                                if (val instanceof Integer && ((Integer) val) != 0) {
                                                    childResIds.add((Integer) val);
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                if (!childResIds.isEmpty()) {
                    logger.d("Discovered child drawable resources in adaptive icons: " + childResIds);
                    Set<String> childFiles = arscParser.resolveResourceFiles(childResIds);
                    matchedIconFiles.addAll(childFiles);
                }

                // If any XML files were found, patch resources.arsc in-place (.xml -> .png)
                if (!xmlIconFiles.isEmpty()) {
                    logger.i("Patching adaptive icon XML references in resources.arsc: " + xmlIconFiles);
                    byte[] patchedArsc = arscParser.patchXmlExtensionsToPng(xmlIconFiles);
                    dstZFile.add("resources.arsc", new ByteArrayInputStream(patchedArsc), false);
                }
            }

            // Fallback scan: add standard launcher icon files if none found
            if (matchedIconFiles.isEmpty()) {
                for (StoredEntry entry : dstZFile.entries()) {
                    String name = entry.getCentralDirectoryHeader().getName();
                    if (name.startsWith("res/") && (name.contains("ic_launcher") || name.contains("app_icon"))) {
                        matchedIconFiles.add(name);
                    }
                }
            }

            logger.i("Replacing " + matchedIconFiles.size() + " icon assets across all densities...");
            for (String file : matchedIconFiles) {
                logger.d("Replacing icon file: " + file);
                dstZFile.add(file, new ByteArrayInputStream(iconPngBytes), false);
                if (file.endsWith(".xml")) {
                    String pngPath = file.substring(0, file.length() - 4) + ".png";
                    dstZFile.add(pngPath, new ByteArrayInputStream(iconPngBytes), false);
                }
            }
            logger.i("App icon successfully replaced!");
        } catch (Throwable t) {
            logger.e("Failed to replace app icon: " + t.getMessage());
        }
    }

    private byte[] loadIconAsPngBytes(File imageFile) throws IOException {
        if (!imageFile.exists() || !imageFile.isFile()) {
            throw new PatchException("Custom icon file does not exist: " + imageFile);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = new java.io.FileInputStream(imageFile)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
        }
        byte[] rawBytes = baos.toByteArray();
        if (rawBytes.length >= 8
                && (rawBytes[0] & 0xFF) == 0x89
                && rawBytes[1] == 'P'
                && rawBytes[2] == 'N'
                && rawBytes[3] == 'G'
                && rawBytes[4] == 0x0D
                && rawBytes[5] == 0x0A
                && rawBytes[6] == 0x1A
                && rawBytes[7] == 0x0A) {
            return rawBytes;
        }

        try {
            Class<?> bfClass = Class.forName("android.graphics.BitmapFactory");
            Method decodeByteArray = bfClass.getMethod("decodeByteArray", byte[].class, int.class, int.class);
            Object bitmap = decodeByteArray.invoke(null, rawBytes, 0, rawBytes.length);
            if (bitmap != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Class<?> compressFormatClass = Class.forName("android.graphics.Bitmap$CompressFormat");
                Object pngFormat = Enum.valueOf((Class<Enum>) compressFormatClass, "PNG");
                Method compress = bitmap.getClass().getMethod("compress", compressFormatClass, int.class, java.io.OutputStream.class);
                compress.invoke(bitmap, pngFormat, 100, out);
                return out.toByteArray();
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> imageIoClass = Class.forName("javax.imageio.ImageIO");
            Method readMethod = imageIoClass.getMethod("read", InputStream.class);
            Object bufferedImage = readMethod.invoke(null, new ByteArrayInputStream(rawBytes));
            if (bufferedImage != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Method writeMethod = imageIoClass.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String.class, java.io.OutputStream.class);
                boolean ok = (Boolean) writeMethod.invoke(null, bufferedImage, "PNG", out);
                if (ok && out.size() > 0) {
                    return out.toByteArray();
                }
            }
        } catch (Throwable ignored) {
        }

        return rawBytes;
    }

    private static class ArscParser {
        private final byte[] data;
        private final List<String> globalStrings = new ArrayList<>();
        private int stringPoolPos = -1;
        private boolean isUtf8 = false;

        ArscParser(byte[] data) {
            this.data = data;
            parseStringPool();
        }

        private void parseStringPool() {
            if (data.length < 12) return;
            int pos = 12;
            if (pos + 28 > data.length) return;
            int type = getShort(pos);
            if (type != 0x0001) return;
            stringPoolPos = pos;
            int flags = getInt(pos + 16);
            isUtf8 = (flags & (1 << 8)) != 0;
            int stringCount = getInt(pos + 8);
            int stringsStart = getInt(pos + 20);
            int stringsDataStart = pos + stringsStart;

            int offsetsPos = pos + 28;
            for (int i = 0; i < stringCount; i++) {
                if (offsetsPos + 4 > data.length) break;
                int strOffset = stringsDataStart + getInt(offsetsPos);
                offsetsPos += 4;
                if (strOffset >= data.length) {
                    globalStrings.add("");
                    continue;
                }
                if (isUtf8) {
                    int p = strOffset;
                    int u16len = data[p++] & 0xFF;
                    if ((u16len & 0x80) != 0) p++;
                    if (p >= data.length) {
                        globalStrings.add("");
                        continue;
                    }
                    int u8len = data[p++] & 0xFF;
                    if ((u8len & 0x80) != 0) {
                        if (p >= data.length) {
                            globalStrings.add("");
                            continue;
                        }
                        u8len = ((u8len & 0x7F) << 8) | (data[p++] & 0xFF);
                    }
                    if (p + u8len <= data.length) {
                        globalStrings.add(new String(data, p, u8len, StandardCharsets.UTF_8));
                    } else {
                        globalStrings.add("");
                    }
                } else {
                    int p = strOffset;
                    int u16len = getShort(p);
                    p += 2;
                    if ((u16len & 0x8000) != 0) {
                        u16len = ((u16len & 0x7FFF) << 16) | getShort(p);
                        p += 2;
                    }
                    int byteLen = u16len * 2;
                    if (p + byteLen <= data.length) {
                        globalStrings.add(new String(data, p, byteLen, StandardCharsets.UTF_16LE));
                    } else {
                        globalStrings.add("");
                    }
                }
            }
        }

        private int getShort(int offset) {
            if (offset < 0 || offset + 2 > data.length) return 0;
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }

        private int getInt(int offset) {
            if (offset < 0 || offset + 4 > data.length) return 0;
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }

        public Set<String> resolveResourceFiles(Set<Integer> targetResIds) {
            Set<String> files = new LinkedHashSet<>();
            if (stringPoolPos < 0) return files;
            int chunkSize = getInt(stringPoolPos + 4);
            int pkgPos = stringPoolPos + chunkSize;
            while (pkgPos + 8 <= data.length) {
                int chunkType = getShort(pkgPos);
                int chunkHeaderSize = getShort(pkgPos + 2);
                int currentChunkSize = getInt(pkgPos + 4);
                if (currentChunkSize <= 0 || pkgPos + currentChunkSize > data.length) break;

                if (chunkType == 0x0200) { // RES_TABLE_PACKAGE_TYPE
                    int pkgId = getInt(pkgPos + 8);
                    int innerPos = pkgPos + chunkHeaderSize;
                    while (innerPos + 8 <= pkgPos + currentChunkSize) {
                        int innerType = getShort(innerPos);
                        int innerHeaderSize = getShort(innerPos + 2);
                        int innerChunkSize = getInt(innerPos + 4);
                        if (innerChunkSize <= 0 || innerPos + innerChunkSize > pkgPos + currentChunkSize) break;

                        if (innerType == 0x0201) { // RES_TABLE_TYPE_TYPE
                            int typeId = data[innerPos + 8] & 0xFF;
                            int entryCount = getInt(innerPos + 12);
                            int entriesStart = getInt(innerPos + 16);
                            int offsetTablePos = innerPos + innerHeaderSize;

                            for (int entryIdx = 0; entryIdx < entryCount; entryIdx++) {
                                if (offsetTablePos + 4 > innerPos + innerChunkSize) break;
                                int entryOffset = getInt(offsetTablePos);
                                offsetTablePos += 4;
                                if (entryOffset == -1 || entryOffset == 0xFFFFFFFF) continue;

                                int resId = (pkgId << 24) | (typeId << 16) | entryIdx;
                                if (targetResIds.contains(resId)) {
                                    int entryPtr = innerPos + entriesStart + entryOffset;
                                    if (entryPtr + 8 <= data.length) {
                                        int eSize = getShort(entryPtr);
                                        int eFlags = getShort(entryPtr + 2);
                                        if ((eFlags & 1) == 0) { // Simple entry
                                            int valPtr = entryPtr + eSize;
                                            if (valPtr + 8 <= data.length) {
                                                int dataType = data[valPtr + 3] & 0xFF;
                                                int strIdx = getInt(valPtr + 4);
                                                if (dataType == 3 && strIdx >= 0 && strIdx < globalStrings.size()) {
                                                    String file = globalStrings.get(strIdx);
                                                    if (file != null && !file.isEmpty()) {
                                                        files.add(file);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        innerPos += innerChunkSize;
                    }
                }
                pkgPos += currentChunkSize;
            }
            return files;
        }

        public byte[] patchXmlExtensionsToPng(Set<String> xmlFiles) {
            byte[] copy = Arrays.copyOf(data, data.length);
            for (String xmlFile : xmlFiles) {
                if (!xmlFile.endsWith(".xml")) continue;
                String pngFile = xmlFile.substring(0, xmlFile.length() - 4) + ".png";
                byte[] targetUtf8 = xmlFile.getBytes(StandardCharsets.UTF_8);
                byte[] replUtf8 = pngFile.getBytes(StandardCharsets.UTF_8);
                replaceBytes(copy, targetUtf8, replUtf8);

                byte[] targetUtf16 = xmlFile.getBytes(StandardCharsets.UTF_16LE);
                byte[] replUtf16 = pngFile.getBytes(StandardCharsets.UTF_16LE);
                replaceBytes(copy, targetUtf16, replUtf16);
            }
            return copy;
        }

        private static void replaceBytes(byte[] src, byte[] target, byte[] replacement) {
            if (target.length != replacement.length) return;
            for (int i = 0; i <= src.length - target.length; i++) {
                boolean match = true;
                for (int j = 0; j < target.length; j++) {
                    if (src[i + j] != target[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    System.arraycopy(replacement, 0, src, i, replacement.length);
                    i += target.length - 1;
                }
            }
        }
    }
}
