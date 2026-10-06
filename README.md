# DropLog — serverless Android 0.5

For an existing Firebase installation start with **UPDATE_0.5.md**. For first-time setup use **FIREBASE_SETUP.md**; Python-to-Firebase upgrades are covered in **UPGRADE.md**.

Native Kotlin / Jetpack Compose app for one property's shared Samsung tablet, with Firebase Authentication, Firestore and Cloud Functions. Room stores local copies of confirmed records. There is no Python server and no server address to enter.

## Features
- Landscape tablet welcome screen, Let's start, centered Cash and Equipment choices.
- Start → PIN → role-based Housekeeping, Drop cash, Log equipment and Log meal actions. Unique 3–8 digit employee PINs, masked keypad and personal log attribution.
- Two distinct signed housekeeping meals per Central day: Breakfast before noon, Lunch noon to 2:59 PM, Dinner from 3 PM. Meal type is automatic.
- Multiple admins: each uses their own email/password; an existing admin can create another through Add admin.
- Separate admin email/password authentication.
- Admin creates/edits users with first/last names, job title, employee/manager role, PIN confirmation, one/both locations and multiple duties. Multiple-choice users select a working shift after PIN login.
- Signed cash drops: location, full employee name, amount and cloud timestamp; exact integer cents. Restaurant/Bar outlet and meal selection; Bar allows Lunch/Dinner only.
- Signed radio/key events: start, repeated In/Out, confirmed End shift and Undo end shift.
- Atomic cloud equipment locks prevent double checkout; server enforces valid transitions and staff permissions.
- Employees are signed out after each saved entry; server rejects authenticated sessions older than 12 hours.
- Admin daily activity, outstanding equipment, recipient settings, test mail and manual previous-day reports.
- Scheduled cash-only reports at 4:00 AM America/Chicago. Manual cash/equipment reports for inclusive dates/ranges. CSV plus HTML attachment with rendered signatures; equipment reports have no automatic email schedule.
- Cloud storage of logs, signatures, employee profiles, hashed PIN records, equipment state and email settings.
- Room mirrors confirmed logs/profiles/settings. Cloud records survive tablet replacement; managed backups require setup.
- One-time old SQLite importer without running a Python service.

## Build versions
Gradle **9.4.1** wrapper is included. AGP **9.1.1**, built-in Kotlin **2.2.10**, matching Compose compiler, JDK 17, compile SDK 35, minimum Android 8/API 26. Room uses the AGP legacy-kapt plugin. Firebase config must be supplied by the user in app/google-services.json.

## Verification
Run `npm ci` and `npm test` inside functions. Tests include validation, hashing, permissions, cloud business-logic handlers with deterministic Firebase/SMTP fakes, equipment locks, retries, reports and session expiry. These do not replace a real Firebase deployment/IAM/SMTP smoke test. Android build verification and limitations are listed in BUILD_STATUS.md.

## Important limits
Signed cash, equipment and meal logs save to an encrypted persistent tablet queue and sync through WorkManager when internet returns. Cached PIN access requires one successful online PIN login per employee on that tablet and expires after 30 days. Admin account setup and report email actions require internet. Pending conflicts remain visible in Admin → Offline logs · Sync review; updates should be installed over the existing app without clearing its data. See UPDATE_0.5.md for setup, timing, conflicts and device checks.

Equipment permits repeated In/Out and end without a lunch break. Undo reopens the last ended shift in returned/out state and never steals equipment reissued to others. Lost items and supervisor overrides are not supported. Signatures appear as coordinates in CSV and readable drawings in HTML reports; PDFs are not directly generated. Activity UI shows today's records; earlier history remains in Firestore and reports. Report generation reads previous equipment events to reconstruct historical outstanding equipment; large multi-year histories may need an optimized reporting index/aggregation before scaling beyond a small staff. The rate limiter is per-IP and may limit fast consecutive PIN sign-ins at a busy shared tablet. Cloud Functions endpoints use Firebase authentication or scoped offline device credentials; PIN login uses keyed hashed PIN lookup and throttling; App Check enforcement is not configured in this beginner setup.

Daily delivery is duplicate-protected by report date. SMTP cannot guarantee exactly-once delivery after ambiguous failures; inspect mail-provider logs before retrying a delivery left in sending status. Manual date/range requests intentionally send another copy and are rate-limited.

## Recovery
Enable Firestore daily backup schedules in your own cloud project. Firestore backups do not include Firebase Auth or Secret Manager; preserve/export those separately. Back up PIN_PEPPER securely and keep the Firebase project configuration and app signing key. Do not use production test-mode Firestore rules: this project denies direct database access and performs data operations through authenticated functions.

## Design
Earlier editable tablet reference (Figma Starter MCP allowance prevented updating the file for 0.4): https://www.figma.com/design/t6sVoTLjHoonsXHiihfAob

No Firebase project has been created/deployed and no emails sent by generating this source archive. Follow the guide to activate those services.
