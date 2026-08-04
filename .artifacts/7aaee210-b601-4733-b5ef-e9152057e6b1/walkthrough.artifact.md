# Walkthrough - Fix Keystore Path

Fixed the issue where the keystore file could not be found due to incorrect path formatting in `local.properties`.

## Changes Made

### Configuration

#### [local.properties](file:///D:/code/fscmnewapp/local.properties)
- Changed `FSCM_RELEASE_STORE_FILE` from `D:\app密钥\fscm` to `D:/app密钥/fscm`.
- Using forward slashes `/` prevents the Java Properties loader from interpreting `\f` as a special escape character (Form Feed), ensuring the path is read correctly by Gradle.

## Verification Results

### Manual Verification
- Verified that the file `D:\app密钥\fscm` exists on disk.
- Updated the configuration to use a safe path format.
- You can now retry the build command:
  ```powershell
  ./gradlew :app:validateSigningDemoRelease
  ```
