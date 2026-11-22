/*
 * Copyright (C) 2026 LSPatch Contributors
 *
 * Original code is credited to Morphe:
 * https://github.com/MorpheApp/morphe-patches/blob/main/patches/src/main/kotlin/app/morphe/patches/shared/misc/gms/GmsCoreSupportPatch.kt
 * and hoo-dles:
 * https://github.com/hoo-dles/morphe-patches
 *
 * Licensed under the GNU General Public License v3.0.
 */

package org.lsposed.patch.microg;

import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11n;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11x;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import org.lsposed.patch.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MicroGPatcher {

    private MicroGPatcher() {}

    /**
     * Rewrites all dex entries in dstZFile to replace Google Play services references with MicroG vendor references.
     * Processed one dex file at a time to keep memory usage minimal on Android devices.
     */
    public static void process(ZFile dstZFile, String microGVendor, Logger logger) throws IOException {
        String vendor = (microGVendor != null && !microGVendor.isEmpty()) ? microGVendor : "app.revanced";
        logger.i("Running MicroG integration patch (vendor: " + vendor + ")...");

        List<String> dexEntryNames = new ArrayList<>();
        for (StoredEntry entry : dstZFile.entries()) {
            String name = entry.getCentralDirectoryHeader().getName();
            if (name.startsWith("classes") && name.endsWith(".dex")) {
                dexEntryNames.add(name);
            }
        }

        // Sort so classes.dex is first, then classes2.dex, etc.
        dexEntryNames.sort((a, b) -> {
            int idxA = a.equals("classes.dex") ? 1 : Integer.parseInt(a.substring(7, a.length() - 4));
            int idxB = b.equals("classes.dex") ? 1 : Integer.parseInt(b.substring(7, b.length() - 4));
            return Integer.compare(idxA, idxB);
        });

        int totalTransformedStrings = 0;
        int totalFingerprintedMethods = 0;

        for (String dexName : dexEntryNames) {
            StoredEntry dexEntry = dstZFile.get(dexName);
            if (dexEntry == null) continue;

            DexBackedDexFile dexFile;
            try (InputStream is = dexEntry.open()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int r;
                while ((r = is.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                dexFile = new DexBackedDexFile(Opcodes.getDefault(), baos.toByteArray());
            }

            DexPool dexPool = new DexPool(dexFile.getOpcodes());
            boolean dexModified = false;
            int dexStrings = 0;
            int dexFingerprints = 0;

            for (ClassDef classDef : dexFile.getClasses()) {
                boolean classModified = false;
                List<Method> methods = new ArrayList<>();

                for (Method method : classDef.getMethods()) {
                    MethodImplementation impl = method.getImplementation();
                    if (impl == null) {
                        methods.add(method);
                        continue;
                    }

                    // Check fingerprints first
                    MethodImplementation newImpl = checkFingerprints(classDef, method, impl, vendor);
                    if (newImpl != null) {
                        dexFingerprints++;
                        classModified = true;
                        dexModified = true;
                        methods.add(new ImmutableMethod(
                                method.getDefiningClass(),
                                method.getName(),
                                method.getParameters(),
                                method.getReturnType(),
                                method.getAccessFlags(),
                                method.getAnnotations(),
                                method.getHiddenApiRestrictions(),
                                newImpl
                        ));
                        continue;
                    }

                    // Rewrite instructions (const-string & setIsSystemProviderRequired)
                    boolean methodModified = false;
                    List<Instruction> newInstructions = new ArrayList<>();

                    for (Instruction inst : impl.getInstructions()) {
                        if (inst instanceof Instruction21c || inst instanceof Instruction31c) {
                            ReferenceInstruction refInst = (ReferenceInstruction) inst;
                            if (refInst.getReference() instanceof StringReference) {
                                String orig = ((StringReference) refInst.getReference()).getString();
                                String transformed = transformString(orig, vendor);
                                if (transformed != null) {
                                    dexStrings++;
                                    methodModified = true;
                                    classModified = true;
                                    dexModified = true;
                                    if (inst instanceof Instruction21c) {
                                        Instruction21c i21c = (Instruction21c) inst;
                                        newInstructions.add(new ImmutableInstruction21c(
                                                Opcode.CONST_STRING,
                                                i21c.getRegisterA(),
                                                new ImmutableStringReference(transformed)
                                        ));
                                    } else {
                                        Instruction31c i31c = (Instruction31c) inst;
                                        newInstructions.add(new ImmutableInstruction31c(
                                                Opcode.CONST_STRING_JUMBO,
                                                i31c.getRegisterA(),
                                                new ImmutableStringReference(transformed)
                                        ));
                                    }
                                    continue;
                                }
                            }
                        } else if (inst instanceof Instruction35c) {
                            Instruction35c i35c = (Instruction35c) inst;
                            if (i35c.getOpcode() == Opcode.INVOKE_VIRTUAL
                                    && i35c.getReference() instanceof MethodReference) {
                                MethodReference mr = (MethodReference) i35c.getReference();
                                if ("Landroid/credentials/CredentialOption$Builder;".equals(mr.getDefiningClass())
                                        && "setIsSystemProviderRequired".equals(mr.getName())) {
                                    // Force registerD (boolean arg) to false
                                    int regD = i35c.getRegisterD();
                                    newInstructions.add(new ImmutableInstruction11n(Opcode.CONST_4, regD, 0));
                                    newInstructions.add(inst);
                                    methodModified = true;
                                    classModified = true;
                                    dexModified = true;
                                    continue;
                                }
                            }
                        }
                        newInstructions.add(inst);
                    }

                    if (methodModified) {
                        methods.add(new ImmutableMethod(
                                method.getDefiningClass(),
                                method.getName(),
                                method.getParameters(),
                                method.getReturnType(),
                                method.getAccessFlags(),
                                method.getAnnotations(),
                                method.getHiddenApiRestrictions(),
                                new ImmutableMethodImplementation(
                                        impl.getRegisterCount(),
                                        newInstructions,
                                        impl.getTryBlocks(),
                                        impl.getDebugItems()
                                )
                        ));
                    } else {
                        methods.add(method);
                    }
                }

                if (classModified) {
                    dexPool.internClass(new ImmutableClassDef(
                            classDef.getType(),
                            classDef.getAccessFlags(),
                            classDef.getSuperclass(),
                            classDef.getInterfaces(),
                            classDef.getSourceFile(),
                            classDef.getAnnotations(),
                            classDef.getFields(),
                            methods
                    ));
                } else {
                    dexPool.internClass(classDef);
                }
            }

            if (dexModified) {
                File tempDex = File.createTempFile("microg_", ".dex");
                try {
                    dexPool.writeTo(new FileDataStore(tempDex));
                    try (InputStream fis = new FileInputStream(tempDex)) {
                        dstZFile.add(dexName, fis);
                    }
                } finally {
                    if (tempDex.exists()) tempDex.delete();
                }
                logger.i("  - " + dexName + ": modified (" + dexStrings + " strings, " + dexFingerprints + " method fingerprints)");
                totalTransformedStrings += dexStrings;
                totalFingerprintedMethods += dexFingerprints;
            } else {
                logger.d("  - " + dexName + ": unchanged");
            }

            // Release references and request GC between dex files on device
            dexPool = null;
            dexFile = null;
            System.gc();
        }

        logger.i("MicroG dex pass completed: " + totalTransformedStrings + " strings transformed, "
                + totalFingerprintedMethods + " method fingerprints applied.");
    }

    /**
     * Checks if a method matches any of the Play Services availability or service check fingerprints.
     */
    private static MethodImplementation checkFingerprints(ClassDef classDef, Method method, MethodImplementation impl, String vendor) {
        String methodName = method.getName();
        String returnType = method.getReturnType();
        int accessFlags = method.getAccessFlags();
        List<? extends CharSequence> paramTypes = method.getParameterTypes();

        boolean isPublic = (accessFlags & AccessFlags.PUBLIC.getValue()) != 0;
        boolean isStatic = (accessFlags & AccessFlags.STATIC.getValue()) != 0;

        // IsGooglePlayServicesAvailableFingerprint:
        // isGooglePlayServicesAvailable(Context, int) -> int (public static)
        if ("isGooglePlayServicesAvailable".equals(methodName) && isPublic && isStatic && "I".equals(returnType)) {
            if (paramTypes.size() == 2 && "Landroid/content/Context;".equals(paramTypes.get(0).toString()) && "I".equals(paramTypes.get(1).toString())) {
                return returnConstZero(impl.getRegisterCount());
            }
        }

        // IsGooglePlayServicesAvailableLightFingerprint:
        // definingClass: Lcom/google/android/gms/common/GoogleApiAvailabilityLight; (or vendor)
        // isGooglePlayServicesAvailable(Context, int) -> int (public)
        if ("isGooglePlayServicesAvailable".equals(methodName) && isPublic && "I".equals(returnType)) {
            String defClass = classDef.getType();
            if (defClass.contains("GoogleApiAvailabilityLight;")
                    && paramTypes.size() == 2 && "Landroid/content/Context;".equals(paramTypes.get(0).toString()) && "I".equals(paramTypes.get(1).toString())) {
                return returnConstZero(impl.getRegisterCount());
            }
        }

        // Methods that require checking string literals in instruction body
        Set<String> methodStrings = null;

        // IsGooglePlayServicesAvailableProguardFingerprint:
        // (Context, int) -> int, contains " requires Google Play services, but they are missing."
        if ("I".equals(returnType) && paramTypes.size() == 2
                && "Landroid/content/Context;".equals(paramTypes.get(0).toString()) && "I".equals(paramTypes.get(1).toString())) {
            methodStrings = extractMethodStrings(impl);
            if (methodStrings.contains(" requires Google Play services, but they are missing.")) {
                return returnConstZero(impl.getRegisterCount());
            }
        }

        // GooglePlayUtilityFingerprint:
        // public static (L..., int) -> int, contains "This should never happen.", "MetadataValueReader", "com.google.android.gms"
        if (isPublic && isStatic && "I".equals(returnType) && paramTypes.size() == 2 && "I".equals(paramTypes.get(1).toString())) {
            if (methodStrings == null) methodStrings = extractMethodStrings(impl);
            if (methodStrings.contains("This should never happen.")
                    && methodStrings.contains("MetadataValueReader")
                    && (methodStrings.contains("com.google.android.gms") || methodStrings.contains(vendor + ".android.gms"))) {
                return returnConstZero(impl.getRegisterCount());
            }
        }

        // ServiceCheckFingerprint:
        // public static (L..., int) -> void, contains "Google Play Services not available"
        if (isPublic && isStatic && "V".equals(returnType) && paramTypes.size() == 2 && "I".equals(paramTypes.get(1).toString())) {
            if (methodStrings == null) methodStrings = extractMethodStrings(impl);
            if (methodStrings.contains("Google Play Services not available")) {
                return returnVoid(impl.getRegisterCount());
            }
        }

        return null;
    }

    private static Set<String> extractMethodStrings(MethodImplementation impl) {
        Set<String> strings = new HashSet<>();
        for (Instruction inst : impl.getInstructions()) {
            if (inst instanceof Instruction21c || inst instanceof Instruction31c) {
                ReferenceInstruction refInst = (ReferenceInstruction) inst;
                if (refInst.getReference() instanceof StringReference) {
                    strings.add(((StringReference) refInst.getReference()).getString());
                }
            }
        }
        return strings;
    }

    private static MethodImplementation returnConstZero(int regCount) {
        int registers = Math.max(regCount, 1);
        List<Instruction> instructions = Arrays.asList(
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                new ImmutableInstruction11x(Opcode.RETURN, 0)
        );
        return new ImmutableMethodImplementation(registers, instructions, Collections.emptyList(), Collections.emptyList());
    }

    private static MethodImplementation returnVoid(int regCount) {
        List<Instruction> instructions = Collections.singletonList(
                new ImmutableInstruction10x(Opcode.RETURN_VOID)
        );
        return new ImmutableMethodImplementation(regCount, instructions, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Morphe string transformation logic.
     * Idempotent: returns null if no transformation is needed or string already starts with vendor prefix.
     */
    public static String transformString(String str, String microGVendor) {
        if (str == null || str.isEmpty()) {
            return null;
        }

        // Idempotency: if already prefixed by vendor, do not touch
        if (str.startsWith(microGVendor)) {
            return null;
        }

        // 1. Common transformations: exact matches or sets
        if (str.equals("com.google") || str.equals("com.google.android.gms")
                || MicroGConstants.PERMISSIONS.contains(str)
                || MicroGConstants.ACTIONS.contains(str)
                || MicroGConstants.AUTHORITIES.contains(str)
                || MicroGConstants.CAPABILITIES.contains(str)) {
            return str.replace("com.google", microGVendor);
        }

        // 2. Subscribed feeds special case
        if (str.equals("subscribedfeeds")) {
            return microGVendor + ".subscribedfeeds";
        }

        // 3. Content URIs
        if (str.startsWith("content://")) {
            for (String authority : MicroGConstants.AUTHORITIES) {
                String uriPrefix = "content://" + authority;
                if (str.startsWith(uriPrefix)) {
                    String newAuthority = authority.replace("com.google", microGVendor);
                    return "content://" + newAuthority + str.substring(uriPrefix.length());
                }
            }

            if (str.startsWith("content://subscribedfeeds")) {
                return "content://" + microGVendor + ".subscribedfeeds" + str.substring("content://subscribedfeeds".length());
            }
        }

        return null;
    }
}
