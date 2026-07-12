# Implement Box Label Management Feature

This plan outlines the implementation of a "Box Label Management" (箱唛管理) feature in the FSCM Android app. The feature allows users to scan a box label code (QR/barcode) and immediately view its details, without showing a list of box labels.

## User Review Required

> [!NOTE]
> The implementation uses the `/api/box-labels/:id/resolve` endpoint for scanning, which is optimized for raw QR code input.

## Proposed Changes

### Data Layer

#### [FscmModels.kt](file:///D:/code/fscmnewapp/app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm/data/FscmModels.kt)

- Add `BoxLabel`, `BoxLabelSKUItem`, `BoxLabelDocumentRef` models.

#### [FscmRepositories.kt](file:///D:/code/fscmnewapp/app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm/data/FscmRepositories.kt)

- Add `CartonRepository` with `resolveBoxLabel` method.

#### [FscmFormatters.kt](file:///D:/code/fscmnewapp/app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm/data/FscmFormatters.kt)

- Update `statusText` to include Box Label specific statuses (`voided`, `shorted`, `received`, `exception`).

---

### UI Layer

#### [FscmViewModels.kt](file:///D:/code/fscmnewapp/app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm/ui/FscmViewModels.kt)

- Add `CartonManagementViewModel` to handle scanning, resolving, and state management for Box Label details.

#### [FscmApp.kt](file:///D:/code/fscmnewapp/app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm/ui/FscmApp.kt)

- Implement `CartonManagementScreen`.
- Design a detail card view for Box Label info (Manufacturer, SKU details, Receiving info, Associated documents).
- Update `FeatureScreen` to use the new `CartonManagementScreen`.

---

## Verification Plan

### Automated Tests
- Run `:app:assembleDemoDebug` to ensure compilation.
- I will add a simple unit test for `CartonRepository` if feasible, but since it's mostly API mapping, I'll focus on manual verification.

### Manual Verification
1. Open the FSCM app and navigate to "应用" (Apps).
2. Tap "箱唛管理" (Box Label Management).
3. Verify that the screen starts with a scanning interface or a prominent scan button.
4. Input/Scan a known box label code.
5. Verify that the app calls the backend and displays the correct details in a white card style.
6. Verify that no list is shown, as per requirements.
