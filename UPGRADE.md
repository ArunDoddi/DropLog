# Replace your existing project with serverless DropLog 0.4

**Already using Firebase? Follow UPDATE_0.4.md instead; keep your existing database and configuration.**

## Safest method: open the new project separately

1. Stop the old Python server. Back up your existing DropLog folder and SQLite database, including its .pin-secret file.
2. Extract this ZIP into a new folder such as `DropLog-Cloud`. The archive contains a `DropLog` folder inside it.
3. Follow FIREBASE_SETUP.md to create your Firebase project, add the real `app/google-services.json`, and deploy functions.
4. Copy your old `local.properties` into the new DropLog root, or let Android Studio create it. Do not copy old project Gradle files over these new files.
5. In Android Studio, use File → Open and select the new DropLog folder. Choose a new window so your previous project remains available.
6. Sync Gradle, select JDK 17, and Run. The included wrapper uses **Gradle 9.4.1**; AGP is **9.1.1**. No Gradle downgrade is needed.
7. The app keeps application ID `com.droplog.app`. Installing with the same signing key can update the existing app. Preserve your release signing key/configuration if you have one. Do not uninstall the old app just to resolve a signing mismatch before preserving needed data.
8. Import any old SQLite records using the included migration instructions before using the cloud app for daily operations. The new cloud database starts empty until configured/imported.

## If you want to update your current Android Studio folder instead

After backing it up, copy/replace these items together:

- Root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`.
- `gradlew`, `gradlew.bat`, and the `gradle/wrapper` directory (contains Gradle 9.4.1).
- `app/build.gradle.kts`.
- Entire `app/src/main` and `app/src/debug` from this ZIP; these include MainActivity.kt, CloudApi.kt and LocalDatabase.kt.
- `functions`, `migration`, `firebase.json`, `firestore.rules`, `firestore.indexes.json`.
- README.md and FIREBASE_SETUP.md.

Keep your own `local.properties`, signing keys/configuration and original SQLite database. Add your real `app/google-services.json`. Copy your Firebase `.firebaserc` only if it points to the intended cloud project. The old `server` folder is no longer used by the Android app; keep it and its data as an archive until migration is verified.

Do not mix the old Kotlin Android/kapt plugins with AGP 9's built-in Kotlin. These build files already use built-in Kotlin, the matching Compose compiler plugin, and `com.android.legacy-kapt` for Room.

## First run

- No server address is required.
- Admin uses the Firebase email/password you create, not your old Python username/password.
- Create employees or assign fresh PINs to imported employees.
- Save the report recipient and test email.
- Enable daily reports and managed Firestore backups.

Internet is required for sign-in and recording. Confirmed cloud records are mirrored to Room; this version does not queue offline writes. The tablet's screen remains awake while the app is foregrounded; it is not a full Android device-owner kiosk lock.
