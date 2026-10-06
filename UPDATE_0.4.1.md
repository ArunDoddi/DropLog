# DropLog Android 0.4.1 — Cash / Equipment routing fix

The home-screen selection is now retained after PIN login. Cash lists only assigned cash shifts; Equipment lists only assigned radio/key shifts. A single eligible shift opens directly, while multiple eligible locations use the working-shift dropdown. An employee without permission for the selected log sees an access message instead of another log form. Admin → Log my working shift continues to offer all assigned duties.

## Replace in your existing Android Studio project

Copy these files from this ZIP, replacing the corresponding existing files:

- `app/src/main/java/com/droplog/app/MainActivity.kt`
- `app/build.gradle.kts`

Then Sync Project with Gradle Files, and run or rebuild/install your APK. Version is 0.4.1 (code 5); Gradle remains 9.4.1. No database deletion or data migration is needed. This release also limits housekeeping duties to Hotel. Restaurant-only employees can use Restaurant / Bar cash; employees with both locations can combine Hotel housekeeping with Restaurant cash. Managers use Hotel radio/key logs. Removing Hotel from an employee removes Hotel duties from the saved selection. Preserve your existing `app/google-services.json` and Firebase configuration. Admin must assign Equipment / housekeeping duty and the correct locations to employees who use radio/key logs.

This ZIP also retains the corrected functions/package-lock.json from the previous npm installation fix.

## Deploy Hotel-only housekeeping validation

Also replace `functions/index.js` in your existing project, then run from the project root:

```bash
firebase deploy --only functions --project=droplog-27857
```

The source tests in `functions/test/service.test.js` are updated too. Preserve `.env.droplog-27857`, secrets, and `app/google-services.json`.
