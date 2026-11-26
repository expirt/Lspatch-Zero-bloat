package org.lsposed.patch;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.lsposed.lspatch.share.FeatureConfig;
import org.lsposed.patch.util.JavaLogger;
import org.lsposed.patch.util.Logger;

/**
 * The command-line front end.
 *
 * Its whole job is to turn argv into a {@link PatchSpec} and hand it to {@link ApkPatcher}. It used
 * to be the engine as well -- the parameter fields the argument parser wrote into *were* the
 * patcher's inputs -- which meant the only way to run a patch was to render its settings into
 * command-line flags and parse them back, even from the Android manager, which had them all as
 * typed values already. It also meant an invalid invocation was answered by setting a {@code help}
 * flag and then patching nothing at all, successfully.
 */
public class LSPatch {

    @Parameter(description = "apks")
    private List<String> apkPaths = new ArrayList<>();

    @Parameter(
            names = {"-h", "--help"},
            help = true,
            order = 0,
            description = "Print this message")
    private boolean help = false;

    @Parameter(
            names = {"-o", "--output"},
            description = "Output directory")
    private String outputPath = ".";

    @Parameter(
            names = {"-f", "--force"},
            description = "Force overwrite exists output file")
    private boolean forceOverwrite = false;

    @Parameter(
            names = {"-d", "--debuggable"},
            description = "Set app to be debuggable")
    private boolean debuggableFlag = false;

    @Parameter(
            names = {"-l", "--sigbypasslv"},
            description =
                    "Signature bypass level. 0 (disable), 1 (pm), 2 (pm+openat), 3 (pm+openat+svc, instruments raw-syscall apk reads; patches native code, so an app that verifies its own code may detect it -- prefer 2 unless it is not enough). default 0")
    private int sigbypassLevel = 0;

    @Parameter(
            names = {"--injectdex"},
            description = "Inject directly the loder dex file into the original application package")
    private boolean injectDex = false;

    @Parameter(
            names = {"-k", "--keystore"},
            arity = 4,
            description =
                    "Set custom signature keystore. Followed by 4 arguments: keystore path, keystore password, keystore alias, keystore alias password")
    private List<String> keystoreArgs = Arrays.asList(null, "123456", "key0", "123456");

    @Parameter(
            names = {"--manager"},
            description = "Use manager (Cannot work with embedding modules)")
    private boolean useManager = false;

    @Parameter(
            names = {"--manager-package"},
            description =
                    "The manager package a manager-mode app binds to at runtime (defaults to the built-in manager). Set this to match a manager reinstalled under a custom package name.")
    private String managerPackageName = null;

    @Parameter(
            names = {"--version-code"},
            description =
                    "Set the patched app's versionCode to this value (e.g. 1, so a later build can be installed over it)")
    private Integer versionCodeOverride = null;

    @Parameter(
            names = {"-v", "--verbose"},
            description = "Verbose output")
    private boolean verbose = false;

    @Parameter(
            names = {"-m", "--embed"},
            description = "Embed provided modules to apk")
    private List<String> modules = new ArrayList<>();

    @Parameter(
            names = {"--name", "--app-name"},
            description = "Override the patched app's launcher label")
    private String labelOverride = null;

    @Parameter(
            names = {"--pkg", "--package-name", "--clone"},
            description = "New package name for the patched application (cloning)")
    private String newPackageName = null;

    @Parameter(
            names = {"--target-sdk"},
            description = "Override the patched app's targetSdkVersion")
    private Integer targetSdkOverride = null;

    @Parameter(
            names = {"--extract-libs"},
            description = "Force android:extractNativeLibs=true")
    private boolean extractNativeLibs = false;

    @Parameter(
            names = {"--cleartext"},
            description = "Force android:usesCleartextTraffic=true")
    private boolean usesCleartextTraffic = false;

    @Parameter(
            names = {"--add-permission"},
            description =
                    "Add a <uses-permission> to the manifest (repeatable). A bare name is prefixed with android.permission.")
    private List<String> addedPermissions = new ArrayList<>();

    @Parameter(
            names = {"--documents-provider"},
            description = "Inject a DocumentsProvider exposing the app's private data to the system file picker")
    private boolean injectDocumentsProvider = false;

    @Parameter(
            names = {"--gms-replacement"},
            description =
                    "Redirect Google Play services calls and pushes to this replacement package (e.g. vendor.android.gms) and declare the original signature to it")
    private String gmsReplacementPackage = null;

    @Parameter(
            names = {"--fake-signature"},
            description = "Declare the original signature as fake-signature, for a ROM with signature spoofing")
    private boolean declareFakeSignature = false;

    @Parameter(
            names = {"--storage-redirect", "--sr"},
            description = "Enable storage redirection to custom container")
    private boolean storageRedirectEnabled = false;

    @Parameter(
            names = {"--storage-spoof", "--sr-spoof"},
            description = "Enable transparent path spoofing mode instead of direct rewriting",
            arity = 1)
    private Boolean storageSpoofing = null;

    @Parameter(
            names = {"--custom-rule"},
            description = "Add a custom path mapping rule: <virtual>=<real> (repeatable)")
    private List<String> customRules = new ArrayList<>();

    @Parameter(
            names = {"--container-path"},
            description = "Custom container directory template (default: Containers/{pkg})")
    private String containerPath = "Containers/{pkg}";

    @Parameter(
            names = {"--sr-internal"},
            description = "Redirect internal data (/data/user/0/<pkg>) to container",
            arity = 1)
    private boolean redirectInternal = true;

    @Parameter(
            names = {"--sr-external"},
            description = "Redirect external storage (/sdcard) to container",
            arity = 1)
    private boolean redirectExternal = true;

    @Parameter(
            names = {"--sr-databases"},
            description = "Redirect SQLite database paths",
            arity = 1)
    private boolean redirectDatabases = true;

    @Parameter(
            names = {"--sr-preferences"},
            description = "Redirect SharedPreferences",
            arity = 1)
    private boolean redirectPreferences = true;

    @Parameter(
            names = {"--sr-constructors"},
            description = "Redirect File and stream constructor calls",
            arity = 1)
    private boolean redirectConstructors = true;

    @Parameter(
            names = {"--sr-logging"},
            description = "Enable redirection activity logging to <container>/redirect.log")
    private boolean redirectLogging = false;

    @Parameter(
            names = {"--microg"},
            description = "Enable MicroG universal integration patch (rewrite GMS references to MicroG at build-time)")
    private boolean useMicroG = false;

    @Parameter(
            names = {"--microg-vendor"},
            description = "Vendor prefix for MicroG integration (default: app.revanced)")
    private String microGVendor = "app.revanced";

    @Parameter(
            names = {"--keep-abi", "--strip-other-abis"},
            description = "Comma-separated list of ABIs to keep in the APK, stripping all others (e.g. arm64-v8a)")
    private List<String> keepAbis = new ArrayList<>();

    @Parameter(
            names = {"--ssl-unpin", "--unpin-ssl"},
            description = "Enable 1-click SSL/TLS certificate unpinning and trust user certificates")
    private boolean sslUnpinning = false;

    @Parameter(
            names = {"--crash-debugger", "--catch-crashes"},
            description = "Enable notification-based crash debugger to capture error logs on crash")
    private boolean crashDebugger = false;

    @Parameter(
            names = {"--accessibility-dummy", "--keep-alive"},
            description = "Inject dummy accessibility service to keep the app working in the background")
    private boolean accessibilityDummy = false;

    @Parameter(
            names = {"--app-icon", "--icon"},
            description = "Custom app launcher icon image file (PNG, JPG, JPEG, WEBP, etc.)")
    private String appIcon = null;

    @Parameter(
            names = {"--anti-detect"},
            description = "Enable anti-detection protection (hide LSPatch/Xposed traces, packages, dev options, maps)")
    private boolean antiDetect = false;

    @Parameter(
            names = {"--spoof-identity", "--randomize-identity"},
            description = "Generate and spoof a full randomized device identity per patch")
    private boolean spoofIdentity = false;

    @Parameter(
            names = {"--spoof-android-id"},
            description = "Spoof a custom 16-hex Settings.Secure.ANDROID_ID value")
    private String spoofAndroidId = null;

    @Parameter(
            names = {"--disable-flag-secure"},
            description = "Allow screenshots and screen recording by blocking FLAG_SECURE")
    private boolean disableFlagSecure = false;

    @Parameter(
            names = {"--embed-backup"},
            description = "Embed a backup zip into the patched APK for automatic restore on first launch")
    private String embedBackup = null;

    private final JCommander jCommander;

    public LSPatch(String... args) {
        jCommander = JCommander.newBuilder().addObject(this).build();
        jCommander.parse(args);
    }

    public static void main(String... args) {
        Logger logger = new JavaLogger();
        LSPatch cli;
        try {
            cli = new LSPatch(args);
        } catch (ParameterException e) {
            logger.e(e.getMessage());
            e.getJCommander().usage();
            System.exit(2);
            return;
        }
        if (cli.help) {
            cli.jCommander.usage();
            return;
        }
        try {
            new ApkPatcher(logger, cli.toSpec()).patch();
        } catch (PatchException e) {
            // The message is the whole of what a user can act on; the stack trace is noise unless
            // something unexpected went wrong underneath it.
            logger.e(e.getMessage());
            if (e.getCause() != null) e.getCause().printStackTrace(System.err);
            System.exit(1);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** Translates the parsed arguments into a spec, which validates them. */
    public PatchSpec toSpec() throws PatchException {
        KeystoreSpec keystore = keystoreArgs.get(0) == null
                ? KeystoreSpec.builtIn()
                : KeystoreSpec.of(
                        new File(keystoreArgs.get(0)), keystoreArgs.get(1), keystoreArgs.get(2), keystoreArgs.get(3));
        FeatureConfig fc = new FeatureConfig();
        if (antiDetect) {
            fc.enableAllAntiDetection();
        }
        if (disableFlagSecure) {
            fc.disableFlagSecure = true;
        }
        if (spoofIdentity) {
            fc.regenerateAll(true);
        }
        if (spoofAndroidId != null && !spoofAndroidId.isBlank()) {
            fc.identitySpoofEnabled = true;
            fc.androidId = spoofAndroidId.trim();
        }
        ManifestOverrides overrides = ManifestOverrides.builder()
                .versionCode(versionCodeOverride)
                .label(labelOverride)
                .targetSdkVersion(targetSdkOverride)
                .extractNativeLibs(extractNativeLibs ? Boolean.TRUE : null)
                .usesCleartextTraffic(usesCleartextTraffic ? Boolean.TRUE : null)
                .permissions(addedPermissions.stream()
                        .map(ManifestOverrides::normalizePermission)
                        .collect(Collectors.toList()))
                .injectDocumentsProvider(injectDocumentsProvider)
                .gmsReplacementPackage(gmsReplacementPackage)
                .declareFakeSignature(declareFakeSignature)
                .storageRedirectEnabled(storageRedirectEnabled)
                .containerPath(containerPath)
                .storageSpoofing((storageSpoofing != null) ? storageSpoofing : storageRedirectEnabled)
                .customRules(customRules)
                .redirectInternal(redirectInternal)
                .redirectExternal(redirectExternal)
                .redirectDatabases(redirectDatabases)
                .redirectPreferences(redirectPreferences)
                .redirectConstructors(redirectConstructors)
                .redirectLogging(redirectLogging)
                .useMicroG(useMicroG)
                .microGVendor(microGVendor)
                .keepAbis(keepAbis)
                .sslUnpinning(sslUnpinning)
                .crashDebugger(crashDebugger)
                .newPackageName(newPackageName)
                .accessibilityDummy(accessibilityDummy)
                .appIconPath(appIcon)
                .features(fc)
                .embeddedBackupPath(embedBackup)
                .build();
        return PatchSpec.builder()
                .apks(apkPaths.stream().map(File::new).collect(Collectors.toList()))
                .outputDir(new File(outputPath))
                .useManager(useManager)
                .debuggable(debuggableFlag)
                .sigBypassLevel(sigbypassLevel)
                .injectDex(injectDex)
                .forceOverwrite(forceOverwrite)
                .verbose(verbose)
                .modules(modules.stream().map(File::new).collect(Collectors.toList()))
                .keystore(keystore)
                .manifestOverrides(overrides)
                .managerPackageName(managerPackageName)
                .build();
    }
}
