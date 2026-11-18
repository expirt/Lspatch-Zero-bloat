# AGENT TESTS & VERIFICATION LOG

## Build Verification
- **Command:**
  ```bash
  TMPDIR=/data/user/0/com.agy/files/usr/tmp \
  GRADLE_USER_HOME=/storage/emulated/0/projects/.gradle \
  ./gradlew -Djava.io.tmpdir=/data/user/0/com.agy/files/usr/tmp \
      :jar:buildRelease :manager:buildRelease
  ```
- **Result:** `BUILD SUCCESSFUL in 2m 44s`
- **Generated Artifacts:**
  - `out/release/manager-v1.2-492-release.apk` (14,642,866 bytes)
  - `out/release/lspatch-v1.2-492-release.jar` (13,117,046 bytes)

## On-Device Installation Test
- **Target:** Native Android ARM64 device
- **Command:** `rish -c "pm install -r /data/local/tmp/manager.apk"`
- **Result:** `Success`
- **Package:** `org.lsposed.lspatch` verified installed and running.

## Unit / Patch Logic Tests
- **MicroGPatcher:** Fixed `InputStream.markSupported()` issue by buffering DEX entries into `DexBackedDexFile(Opcodes.getDefault(), bytes)`.
- **ZFile mergeFrom:** Stream-piped entries to eliminate OOM on >500MB APKs.
