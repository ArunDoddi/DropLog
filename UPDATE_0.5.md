# DropLog 0.5.0 — PIN-first menu, meals, offline sync and multiple admins

## One-command Mac update

Extract the new ZIP to a different folder from your existing Android Studio project. With Firebase CLI login and Android SDK / Java setup already working, run:

```bash
bash "/path/to/extracted-new/DropLog/update-existing-project.sh" "/path/to/existing/DropLog"
```

Drag each folder from Finder into Terminal to obtain its real path. The script backs up the source files it replaces, preserves your Firebase/email configuration, runs npm ci and backend tests, deploys Firestore/functions to droplog-27857, builds the APK, runs local-state tests, and installs/opens it if exactly one device/emulator is connected. With no connected device it still produces the APK. It does not install Android Studio, Node, Java or Firebase CLI, create the cloud project, log in to Google, or bypass deployment billing/IAM setup. If a step fails, it stops and prints that tool's error. Fix the error and rerun; backups are kept under your existing project. Install over the existing app without uninstalling.

## Replace these files in your existing Android Studio project

| File | Purpose |
| --- | --- |
| app/src/main/java/com/droplog/app/MainActivity.kt | PIN-first menu, signed meals, Add Admin and Sync Review |
| app/src/main/java/com/droplog/app/CloudApi.kt | Device credentials, cached PIN login and queued writes |
| app/src/main/java/com/droplog/app/OfflineSupport.kt | New file: encrypted queue, offline PIN verification and background worker |
| app/src/main/java/com/droplog/app/LocalLogProjection.kt | New file: consistent local state from queued logs |
| app/src/main/java/com/droplog/app/LocalDatabase.kt | Queue storage using the existing Room table |
| app/src/main/AndroidManifest.xml | Application initialization and network permission |
| app/build.gradle.kts | WorkManager dependency and app version 0.5.0 (code 6) |
| functions/index.js | Meals, offline grants/replay and multiple admin creation |
| functions/package.json | Release version |
| functions/package-lock.json | Matching dependency lock metadata |
| functions/test/service.test.js | Backend checks for the new features |
| firestore.indexes.json | Signature indexing exemption for meals |

Keep your own `app/google-services.json`, `.firebaserc`, `.env.droplog-27857`, SMTP settings, and secrets. Gradle remains 9.4.1. Do not delete Firestore or reinstall the app to update it. The existing Room schema is retained; no database migration is needed.

## Deploy first

From the project root in Terminal:

```bash
cd functions
npm ci
npm test
cd ..
firebase deploy --only firestore,functions --project=droplog-27857
```

The new deployed functions include `meal`, `offlineSync`, and `createAdmin`; `pinLogin` and existing handlers are updated. Then Sync Project with Gradle Files and Run in Android Studio, or install your rebuilt APK as an update over the previous app.

## Employee flow

Let's start → PIN → available action buttons. Cash duties show Drop cash, with a Hotel / Restaurant working-shift selection if needed. Hotel housekeeping duty shows Housekeeping, Log equipment and Log meal. Housekeeping opens shortcuts to equipment and meals; no separate housekeeping checklist is introduced. Equipment stays Hotel-only. Managers see all logging actions and keep the admin gear.

Meal logging fetches the employee's name and requires only a signature. Meal type uses America/Chicago time at submission:

- Before 12:00 noon: Breakfast.
- 12:00 noon through 2:59 PM: Lunch.
- 3:00 PM onward: Dinner.

At most two different meals per employee per Central calendar day. Each type can be recorded once. Both the tablet and Firebase check the allowance. The server derives the meal from the original recording time, including when offline records arrive later. A date / time set incorrectly on the tablet can affect offline meal classification; keep automatic device time enabled.

Firestore `meals` stores employee ID, full name, first and last names, meal, Central day, recording time, received time, signature strokes and request ID. `mealDays` enforces the allowance transactionally across devices. Meal reports are not automatically emailed; the existing automatic cash-only email and manual equipment reports continue.

## Offline setup and behavior

Before using an employee offline, complete one successful online PIN login on that tablet. Do this for each employee who will use it. Offline access lasts 30 days from the device credential's issue date; an online PIN login after expiry renews it. Admin email/password setup and report email actions require internet. To log offline as a manager, assign that manager a PIN and sign in online with that PIN first.

Cash, equipment and meal entries are committed to a persistent encrypted tablet queue before upload. The success screen distinguishes saved-on-tablet from confirmed-in-Firebase. Each record retains its original time, name and signature. Android WorkManager retries when connectivity returns, with a network callback and a periodic fallback; Android may delay background execution. Opening the app or Admin → Offline logs → Retry sync also triggers sync. The queue survives closing the app and rebooting the device.

Do not uninstall, clear app data or factory-reset a tablet with pending logs: those actions remove its local queue and encryption keys. Install an updated APK over the existing app instead.

## Sync review and limitations

Admin → Offline logs · Sync review lists pending record IDs, employee names, original times and failure messages. Retry sync never removes rejected records; it removes a record only after Firebase confirms it. Equipment events are replayed in order. A failed equipment event holds its later equipment events for review. Separate cash and meal records can still sync.

An offline tablet cannot know what another disconnected tablet has logged. Firebase resolves equipment ownership conflicts and enforces the two-meal limit on sync. Conflicts, PIN revocations, changed permissions and bad device timestamps remain queued for admin review. No conflict is silently overwritten. Review the displayed record ID with the employee; this release offers inspection/retry rather than automatic conflict correction or discarding signed records.

PINs are not stored in readable form. Cached PIN lookups use an Android Keystore HMAC; profiles, log-only credentials and queued signatures use AES-GCM with a non-exportable device key. Five incorrect offline PIN attempts cause a one-minute local lockout. Firebase stores only hashed device credentials, validates current employee permissions for every replay, revokes old access after a PIN change, and never allows device credentials to create users, modify settings or email reports. Credentials permit only log replay. Records captured within a credential's validity can sync for seven days after its expiry; older pending records stay queued for review.

Daily cash emails include records already in Firebase at midnight. If cash logs arrive later from an offline tablet, resend that date using the manual report date selector.

## Multiple admins

An existing admin opens Admin → Add admin, enters first/last name, email, password (12–128 characters), and confirmation. The new admin uses their own email/password via the home Admin button. All admins share the same hotel data and report email configuration. Create user / edit user remains available to each admin. Assign a PIN through Users if the new admin also wants employee-menu or offline access. An existing Firebase Authentication email cannot be recreated by Add admin; its existing UID needs a matching manager profile.

## Quick device check

1. Online: log in with a housekeeper PIN; confirm the three housekeeping buttons and meal label.
2. Enable airplane mode; log a meal, then log equipment Start and Out with signatures. Confirm the tablet-save message.
3. Close/reopen the app offline and log in with the same PIN. Equipment must still show returned state and the meal must count toward the allowance.
4. Restore internet. Open Sync Review and retry if needed; confirm pending entries disappear only after Firebase confirms them.
5. Confirm Firestore recording times precede received times for offline entries, and no duplicates appear after repeated retries.
6. Add a second admin and verify their email/password login and report settings access.
