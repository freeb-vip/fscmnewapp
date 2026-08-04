# Fix Missing Release Signing Properties Error

The project currently hard-fails when any task containing "ProdRelease" is executed if signing properties are missing. This prevents local developers from testing the `prodRelease` variant (e.g., for R8/Proguard verification) unless they have access to the production keys.

## User Review Required

> [!IMPORTANT]
> The build will now proceed **unsigned** for local development if the signing properties are missing, instead of throwing an error. The error remains mandatory in CI environments to prevent accidental unsigned production releases.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle.kts](file:///D:/code/fscmnewapp/app/build.gradle.kts)
- Update the `prodRelease` check logic:
    - Change `throw GradleException` to `logger.warn` for local builds.
    - Keep `throw GradleException` if the `CI` environment variable is detected.
    - Add a check for a new Gradle property `forceFscmSigning` to allow manual enforcement if needed.

## Verification Plan

### Manual Verification
- Run `./gradlew :app:assembleProdRelease` locally without the properties set in `local.properties`. Verify it completes with a warning instead of a crash.
- (Optional) Simulate CI by setting `CI=true` and verify it still fails.
