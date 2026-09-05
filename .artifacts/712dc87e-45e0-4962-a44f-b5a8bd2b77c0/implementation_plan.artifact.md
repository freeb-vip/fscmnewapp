# Fix Gradle Sync Error: Unable to load class 'org.slf4j.LoggerFactory'

The project is currently failing to sync with a `ClassNotFoundException` for `org.slf4j.LoggerFactory`. This is often caused by a corrupt Gradle cache, incompatible plugin versions, or configuration cache issues in Gradle 9.x.

## Proposed Changes

### [Gradle Configuration]

#### [MODIFY] [gradle.properties](file:///F:/code/fscmnewapp/gradle.properties)
- Disable configuration cache and parallel sync temporarily to rule out concurrency/caching issues.
- Set `org.gradle.configuration-cache=false`.
- Set `org.gradle.tooling.parallel=false`.

#### [MODIFY] [gradle-wrapper.properties](file:///F:/code/fscmnewapp/gradle/wrapper/gradle-wrapper.properties)
- Change Gradle version to `9.3.2` to match the stable AGP version and force a re-download of the Gradle distribution, which fixes potential corruption in the `9.4.1` distribution.

### [Build Logic]

#### [MODIFY] [libs.versions.toml](file:///F:/code/fscmnewapp/gradle/libs.versions.toml)
- Change `android-gradlePlugin` from `gradle-api` to `gradle`. The `gradle-api` artifact only contains the API, while the `gradle` artifact is the full plugin often required by convention plugins.

#### [MODIFY] [build.gradle.kts](file:///F:/code/fscmnewapp/build-logic/convention/build.gradle.kts)
- Move `libs.truth` from `implementation` to `compileOnly` if it's only needed for plugin compilation, or keep it if it's truly needed at runtime but ensure it doesn't conflict. Actually, Nia usually uses `compileOnly` for these.

## Verification Plan

### Automated Tests
- Run `gradle_sync` to verify that the project can now initialize and sync successfully.
- Run `.\gradlew.bat help` to verify that the Gradle daemon can start and run basic tasks.

### Manual Verification
- Check the "Build" tab in Android Studio for any remaining sync errors.
