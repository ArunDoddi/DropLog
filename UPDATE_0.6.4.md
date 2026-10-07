# DropLog 0.6.4 — Exported Figma layouts

Version code 10. This release implements the visual direction in the eight PNGs supplied in `DropLog — Samsung Tablet.zip` directly in native Jetpack Compose. No Figma MCP access or paid plan is required for these exports.

The welcome screen uses the navy background, decorative teal circle, left-aligned headline and large blue start button. PIN entry uses a navy information panel beside the large keypad in landscape. Employee actions use the exported two-card layout while keeping role-specific actions. Cash, equipment, employee editing and email settings use roomy rounded white forms and guidance panels. Colors, fonts, shapes and spacing are shared through `TabletDesign.kt`.

The exports include older example text and workflows. The app keeps the current role restrictions, separate Restaurant/Bar permissions, Ortiz/full-time meals, required signatures, admin PIN confirmations, protected account deletion, and 4:00 AM Central daily cash schedule. Employee name/location data remains dynamic. Extra current fields make some forms scroll; portrait layouts stack the panels. Equipment supports repeated In/Out and End/Undo rather than requiring the old four-step lunch flow.

No backend changes are needed beyond the backend deployed on October 6, 2026. Install over the existing app without uninstalling or clearing its data.

## Verification

Release/debug builds and release lint are verified locally; all 7 Android unit tests pass. Screens are rendered in a local API 35 tablet emulator using a debug-only preview activity and dummy data; no test accounts, meals, cash logs or emails are created. The preview activity is excluded from release builds. Visual checks cover welcome, PIN, actions, cash, equipment, employee editing, Users and report settings. Live sign-in and production submissions still require tablet testing.

Signed release APK: `app/build/outputs/apk/release/DropLog-0.6.4-release.apk`. Signature matches the existing release certificate. The release manifest excludes the debug-only preview activity. Emulator keypad interaction verifies three masked digits enable Continue and Clear removes digits and disables Continue. The Admin footer stays visible with status messages. Preview screenshots are in `design-previews/0.6.4/`.
