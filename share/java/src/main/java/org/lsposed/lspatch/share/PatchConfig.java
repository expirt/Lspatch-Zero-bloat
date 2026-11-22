package org.lsposed.lspatch.share;

public class PatchConfig {

    public final boolean useManager;
    public final boolean debuggable;

    /** The chosen {@code android:versionCode}, or null when the app kept its own. */
    public final Integer versionCode;
    public final int sigBypassLevel;
    public final String originalSignature;
    public final String appComponentFactory;
    /**
     * Whether the loader dex was injected straight into the host package rather than kept as an
     * asset. Recorded so a re-patch driven from an installed app can reproduce the choice; older
     * patched apps have no such key, and Gson leaves the primitive {@code false} for them -- which
     * is exactly what those apps were built with.
     */
    public final boolean injectDex;

    /**
     * The {@code <uses-permission>} names the patch added on top of the app's own, or null for an
     * apk patched before this was recorded. Kept here, unlike the other manifest overrides, because
     * a re-patch recovers the *original* apks -- which never had these -- so without this record a
     * loader update would silently drop the permissions a module depends on.
     */
    public final String[] addedPermissions;

    /**
     * Whether a {@code DocumentsProvider} exposing the app's private data was injected. Recorded for
     * the same reason as {@link #addedPermissions}: a re-patch recovers the original apks, which
     * never carried it, so without this the option would silently turn itself off on a loader update.
     */
    public final boolean injectDocumentsProvider;

    /**
     * The replacement the app's Google Play services traffic is redirected to, or null when it is not.
     *
     * A package name rather than a flag: the metadata key the patcher writes and the C2DM names it renames are
     * both derived from it, so the build the user chose has to survive into the apk. Read by the loader, and
     * recorded for the same reason as {@link #addedPermissions}: a re-patch would otherwise silently drop it.
     */
    public final String gmsReplacementPackage;

    /** Whether {@code fake-signature} was declared. Recorded for the same reason as {@link #addedPermissions}. */
    public final boolean declareFakeSignature;

    /**
     * The manager package a manager-mode app should bind to for module loading, or null for an
     * apk patched before this was recorded (and for integrated apps, which never bind a manager).
     * Recorded so the manager can be reinstalled under a different package name without orphaning
     * already-patched apps: a loader update rewrites this field, and older apks fall back to the
     * built-in {@link Constants#MANAGER_PACKAGE_NAME} via {@link #resolveManagerPackageName()}.
     */
    public final String managerPackageName;
    public final LSPConfig lspConfig;

    public final boolean storageRedirectEnabled;
    public final String containerPath;
    public final boolean storageSpoofing;
    public final boolean redirectInternal;
    public final boolean redirectExternal;
    public final boolean redirectDatabases;
    public final boolean redirectPreferences;
    public final boolean redirectConstructors;
    public final boolean redirectLogging;

    public final boolean useMicroG;
    public final String microGVendor;

    public final String[] keepAbis;
    public final boolean sslUnpinning;
    public final boolean crashDebugger;
    public final String[] customRules;
    public final String newPackageName;

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName
    ) {
        this(
                useManager,
                debuggable,
                versionCode,
                sigBypassLevel,
                originalSignature,
                appComponentFactory,
                injectDex,
                addedPermissions,
                injectDocumentsProvider,
                gmsReplacementPackage,
                declareFakeSignature,
                managerPackageName,
                false,
                null,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                "app.revanced"
        );
    }

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName,
            boolean storageRedirectEnabled,
            String containerPath,
            boolean redirectInternal,
            boolean redirectExternal,
            boolean redirectDatabases,
            boolean redirectPreferences,
            boolean redirectConstructors,
            boolean redirectLogging
    ) {
        this(
                useManager,
                debuggable,
                versionCode,
                sigBypassLevel,
                originalSignature,
                appComponentFactory,
                injectDex,
                addedPermissions,
                injectDocumentsProvider,
                gmsReplacementPackage,
                declareFakeSignature,
                managerPackageName,
                storageRedirectEnabled,
                containerPath,
                redirectInternal,
                redirectExternal,
                redirectDatabases,
                redirectPreferences,
                redirectConstructors,
                redirectLogging,
                false,
                "app.revanced"
        );
    }

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName,
            boolean storageRedirectEnabled,
            String containerPath,
            boolean redirectInternal,
            boolean redirectExternal,
            boolean redirectDatabases,
            boolean redirectPreferences,
            boolean redirectConstructors,
            boolean redirectLogging,
            boolean useMicroG,
            String microGVendor
    ) {
        this(
                useManager,
                debuggable,
                versionCode,
                sigBypassLevel,
                originalSignature,
                appComponentFactory,
                injectDex,
                addedPermissions,
                injectDocumentsProvider,
                gmsReplacementPackage,
                declareFakeSignature,
                managerPackageName,
                storageRedirectEnabled,
                containerPath,
                redirectInternal,
                redirectExternal,
                redirectDatabases,
                redirectPreferences,
                redirectConstructors,
                redirectLogging,
                useMicroG,
                microGVendor,
                null,
                false,
                false
        );
    }

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName,
            boolean storageRedirectEnabled,
            String containerPath,
            boolean redirectInternal,
            boolean redirectExternal,
            boolean redirectDatabases,
            boolean redirectPreferences,
            boolean redirectConstructors,
            boolean redirectLogging,
            boolean useMicroG,
            String microGVendor,
            String[] keepAbis,
            boolean sslUnpinning,
            boolean crashDebugger
    ) {
        this(
                useManager,
                debuggable,
                versionCode,
                sigBypassLevel,
                originalSignature,
                appComponentFactory,
                injectDex,
                addedPermissions,
                injectDocumentsProvider,
                gmsReplacementPackage,
                declareFakeSignature,
                managerPackageName,
                storageRedirectEnabled,
                containerPath,
                storageRedirectEnabled,
                redirectInternal,
                redirectExternal,
                redirectDatabases,
                redirectPreferences,
                redirectConstructors,
                redirectLogging,
                useMicroG,
                microGVendor,
                keepAbis,
                sslUnpinning,
                crashDebugger,
                null
        );
    }

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName,
            boolean storageRedirectEnabled,
            String containerPath,
            boolean storageSpoofing,
            boolean redirectInternal,
            boolean redirectExternal,
            boolean redirectDatabases,
            boolean redirectPreferences,
            boolean redirectConstructors,
            boolean redirectLogging,
            boolean useMicroG,
            String microGVendor,
            String[] keepAbis,
            boolean sslUnpinning,
            boolean crashDebugger
    ) {
        this(
                useManager,
                debuggable,
                versionCode,
                sigBypassLevel,
                originalSignature,
                appComponentFactory,
                injectDex,
                addedPermissions,
                injectDocumentsProvider,
                gmsReplacementPackage,
                declareFakeSignature,
                managerPackageName,
                storageRedirectEnabled,
                containerPath,
                storageSpoofing,
                redirectInternal,
                redirectExternal,
                redirectDatabases,
                redirectPreferences,
                redirectConstructors,
                redirectLogging,
                useMicroG,
                microGVendor,
                keepAbis,
                sslUnpinning,
                crashDebugger,
                null
        );
    }

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName,
            boolean storageRedirectEnabled,
            String containerPath,
            boolean storageSpoofing,
            boolean redirectInternal,
            boolean redirectExternal,
            boolean redirectDatabases,
            boolean redirectPreferences,
            boolean redirectConstructors,
            boolean redirectLogging,
            boolean useMicroG,
            String microGVendor,
            String[] keepAbis,
            boolean sslUnpinning,
            boolean crashDebugger,
            String[] customRules
    ) {
        this(
                useManager,
                debuggable,
                versionCode,
                sigBypassLevel,
                originalSignature,
                appComponentFactory,
                injectDex,
                addedPermissions,
                injectDocumentsProvider,
                gmsReplacementPackage,
                declareFakeSignature,
                managerPackageName,
                storageRedirectEnabled,
                containerPath,
                storageSpoofing,
                redirectInternal,
                redirectExternal,
                redirectDatabases,
                redirectPreferences,
                redirectConstructors,
                redirectLogging,
                useMicroG,
                microGVendor,
                keepAbis,
                sslUnpinning,
                crashDebugger,
                customRules,
                null
        );
    }

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions,
            boolean injectDocumentsProvider,
            String gmsReplacementPackage,
            boolean declareFakeSignature,
            String managerPackageName,
            boolean storageRedirectEnabled,
            String containerPath,
            boolean storageSpoofing,
            boolean redirectInternal,
            boolean redirectExternal,
            boolean redirectDatabases,
            boolean redirectPreferences,
            boolean redirectConstructors,
            boolean redirectLogging,
            boolean useMicroG,
            String microGVendor,
            String[] keepAbis,
            boolean sslUnpinning,
            boolean crashDebugger,
            String[] customRules,
            String newPackageName
    ) {
        this.useManager = useManager;
        this.debuggable = debuggable;
        this.versionCode = versionCode;
        this.sigBypassLevel = sigBypassLevel;
        this.originalSignature = originalSignature;
        this.appComponentFactory = appComponentFactory;
        this.injectDex = injectDex;
        this.addedPermissions = addedPermissions;
        this.injectDocumentsProvider = injectDocumentsProvider;
        this.gmsReplacementPackage = (gmsReplacementPackage != null && !gmsReplacementPackage.isEmpty())
                ? gmsReplacementPackage
                : null;
        this.declareFakeSignature = declareFakeSignature;
        if (useManager) {
            this.managerPackageName = (managerPackageName != null && !managerPackageName.isEmpty())
                    ? managerPackageName
                    : Constants.MANAGER_PACKAGE_NAME;
        } else {
            this.managerPackageName = null;
        }
        this.storageRedirectEnabled = storageRedirectEnabled;
        this.containerPath = (containerPath != null && !containerPath.isEmpty()) ? containerPath : "Containers/{pkg}";
        this.storageSpoofing = storageSpoofing;
        this.redirectInternal = redirectInternal;
        this.redirectExternal = redirectExternal;
        this.redirectDatabases = redirectDatabases;
        this.redirectPreferences = redirectPreferences;
        this.redirectConstructors = redirectConstructors;
        this.redirectLogging = redirectLogging;
        this.useMicroG = useMicroG;
        this.microGVendor = (microGVendor != null && !microGVendor.isEmpty()) ? microGVendor : "app.revanced";
        this.keepAbis = keepAbis;
        this.sslUnpinning = sslUnpinning;
        this.crashDebugger = crashDebugger;
        this.customRules = (customRules != null) ? customRules : new String[0];
        this.newPackageName = newPackageName;
        this.lspConfig = LSPConfig.instance;
    }

    /** Resolved manager package for a manager-mode app; falls back for configs created before this field existed. */
    public String resolveManagerPackageName() {
        if (managerPackageName != null && !managerPackageName.isEmpty()) {
            return managerPackageName;
        }
        return Constants.MANAGER_PACKAGE_NAME;
    }
}
