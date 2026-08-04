# Implementation Plan - Fix Signing Configuration

The project build fails during the `:app:packageDemoRelease` task because of a `KeytoolException` indicating an incorrect keystore password. Research shows that the password provided in `local.properties` (`freeb586586`) is incorrect for the keystore file at `D:\app密钥\fscm`, while `freeb586` is the correct one.

## Proposed Changes

### Configuration

#### [MODIFY] [local.properties](file:///D:/code/fscmnewapp/local.properties)
- Correct the `FSCM_RELEASE_STORE_PASSWORD` and `FSCM_RELEASE_KEY_PASSWORD` values from `freeb586586` to `freeb586`.
- Escape the colon in the path `D\:/app密钥/fscm` to strictly adhere to the `.properties` file format, although the current format might be tolerated.

### Build Logic

#### [MODIFY] [app/build.gradle.kts](file:///D:/code/fscmnewapp/app/build.gradle.kts)
- Improve the `local.properties` loading logic to explicitly use `UTF-8` encoding. This ensures that the Chinese characters in the keystore path (`app密钥`) are read correctly regardless of the system's default character set (e.g., GBK on some Windows environments).

## Verification Plan

### Automated Tests
- Run a subset of the build task or a validation task to verify signing configuration:
  ```powershell
  ./gradlew :app:validateSigningDemoRelease
  ```
- Use `keytool` to verify that the corrected password works with the keystore file (already verified during research).

### Manual Verification
- Ask the user to run the build task `:app:assembleDemoRelease` to confirm the fix.
