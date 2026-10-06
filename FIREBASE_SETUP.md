# DropLog 0.5 — beginner setup guide

You do not need Python or a server address after this conversion. Google operates the cloud functions and database. Your tablet keeps Room copies of confirmed logs. Sign-in and saving require internet in this version; an entry is considered saved only after the cloud confirms it.

**Do these steps in order.** Nothing has been deployed into your Google account by creating this ZIP. Email and cloud backups become active only after you configure your project.

## 1. Create your Firebase project

1. Visit https://console.firebase.google.com and sign in with the Google account you want to own the hotel app.
2. Create a project named **DropLog**. Google Analytics is optional; you can leave it off.
3. Write down the **Project ID** shown in Project settings. It may look like `droplog-12345`; use your actual ID everywhere this guide says `YOUR_PROJECT_ID`.
4. Upgrade to the **Blaze pay-as-you-go** plan. Cloud Functions deployment, scheduled reports, and managed database backups require billing. This is not a fixed free service. Enable billing budget alerts before live use; alerts do not automatically cap spending. Actual charges depend on usage and backup retention.
5. Build → Firestore Database → Create database. Use **Standard edition / Native mode**, the `(default)` database, production mode, and a U.S. location. For this project, `us-central1` is a straightforward choice. Do not choose MongoDB compatibility. Database location cannot simply be changed later.

## 2. Register the Android app

1. Project overview → Add app → Android.
2. Package/application ID: **`com.droplog.app`**. If you previously renamed the app package, register that exact ID instead and update the project consistently.
3. Download `google-services.json`.
4. Put it at **`DropLog/app/google-services.json`**, next to `app/build.gradle.kts`, not inside `app/src`.
5. The ZIP already contains the Firebase Gradle dependencies. Do not add them a second time.

This file is project configuration, not an admin credential. Never put a service-account private key or SMTP password into the Android app.

## 3. Create the admin account

1. Build → Authentication → Get started.
2. Sign-in method → Email/Password → enable Email/Password. You do not need anonymous or phone authentication.
3. Users → Add user → enter your admin email and a strong password.
4. Copy the new user's **UID**.
5. Firestore Database → Start collection → collection ID **`users`**.
6. Document ID: paste that **exact UID**. Add these fields (all type **string**):

| Field | Value |
| --- | --- |
| name | Your name |
| username | Your admin email |
| role | manager |
| job_title | Manager |
| location | Hotel |

7. Save. Do not use an automatic document ID for this admin profile.

The app's admin screen now uses your Firebase **email and password**. Your old Python admin password is not automatically moved into Firebase. Employees continue to use PINs; the app creates their cloud identities for you.

## 4. Install deployment tools on your laptop

Install **Node.js 22 LTS** from https://nodejs.org. Keep Android Studio with Gradle 9.4.1 and JDK 17. Use an Android Studio release that supports AGP 9.1.1.

Open Terminal and run:

```sh
npm install -g firebase-tools
firebase login
```

A Google sign-in window will open. Sign in to the account that owns your Firebase project.

Open Terminal in the extracted **DropLog** folder (the one containing `firebase.json`), then:

```sh
firebase use --add
```

Choose your project and give it the alias `default`. This creates the local `.firebaserc` that links the deployment to your project. You do not need `firebase init`; the configuration and functions are already included.

Install the function dependencies:

```sh
cd functions
npm ci
npm test
cd ..
```

## 5. Configure PIN security

Generate a random secret on your laptop:

```sh
openssl rand -hex 32
```

Copy the random value. Run:

```sh
firebase functions:secrets:set PIN_PEPPER
```

Paste the value into the secret prompt. Keep this secret in your password manager as well. Do not send it in chat. Firebase stores it in Secret Manager; no raw employee PINs are stored in Firestore.

**Do not delete or rotate PIN_PEPPER without resetting employee PINs.** It is used to identify and verify PINs.

## 6. Configure the email sender

You need an SMTP service: your approved company mail provider or a transactional email provider. Obtain its SMTP hostname, port, username, password/API credential, and verified sender address. A provider account may have separate fees.

Inside `functions`, create a text file named **`.env.YOUR_PROJECT_ID`** (replace YOUR_PROJECT_ID with your actual ID) containing:

```dotenv
SMTP_HOST=your-provider-smtp-host
SMTP_PORT=587
SMTP_USER=your-provider-smtp-username
SMTP_FROM=your-verified-sender@example.com
```

Port 587 uses STARTTLS; port 465 uses TLS. Use the provider's specified port. Do not use an ordinary personal-email password unless your provider explicitly supports it; providers commonly require an app-specific password or API credential.

Store the SMTP credential separately:

```sh
firebase functions:secrets:set SMTP_PASSWORD
```

Enter the credential at the prompt. Keep it out of the `.env` file and Android source. This sender setup is separate from the recipient address you enter in the app.

## 7. Deploy the serverless functions

From the **DropLog** root folder:

```sh
firebase deploy --only firestore,functions
```

If asked for missing SMTP parameters, supply the values from step 6. If asked about retaining build/container artifacts, choose a short retention such as 7 days. Wait until deployment finishes successfully.

The deployment creates the 4:00 AM scheduler for `dailyReport`. Its timezone is **America/Chicago**. You do not need a laptop cron job or a tablet alarm. Reports are disabled until you enable them in the app. The scheduler starts the work at 4:00 AM; email delivery can take a little longer.

If employee PIN login later reports `iam.serviceAccounts.signBlob` or a custom-token permission error, see Troubleshooting below.

## 8. Enable actual cloud backups

Firestore is your live cloud database. A tablet reset or replacement does not erase those cloud records, but cloud storage alone is not a historical backup of deletions.

1. Open https://console.cloud.google.com/firestore and select your project.
2. Select your `(default)` database.
3. Open **Backups / backup schedules** and enable a **daily** schedule.
4. Choose a retention period, for example 7 days initially. Backup storage and restore operations are billable.
5. Confirm the schedule appears. After the first run, verify a completed backup exists.
6. If you want recovery from changes within a day, consider enabling Point-in-time recovery as well; it is a separately billed feature.

These backups cover Firestore data: cash logs, meals, signatures, equipment history/state, employee profiles/PIN hashes, settings and report status. Offline records are backed up in Firestore only after sync. Firebase Authentication accounts are stored separately. For an additional Auth export, run:

```sh
firebase auth:export auth-backup.json --format=json
```

Keep that export securely; it contains sensitive account material. A full disaster recovery also needs your Firebase project configuration and PIN_PEPPER secret. Do not keep the only copy of recovery information on the shared tablet.

## 9. Replace your Android Studio project code

See UPGRADE.md. Back up your existing project/database first. Keep local.properties and any signing keys. This update includes a Gradle **9.4.1** wrapper and **AGP 9.1.1** build configuration; replace build files together.

After adding your real google-services.json, select **Sync Project with Gradle Files**, then Run. No server address appears. Tap **Admin** and use the email/password from step 3.

Admin → Users: enter first/last names, job title, role, PIN/confirmation, one or both job locations, and applicable duties. Tap existing users to review their access. Admin → gear icon: save the recipient, send a test email, then enable daily cash reports. Radio reports are manual only; select Radio & keys and a date range in the report panel. See UPDATE_0.5.md for the PIN-first menu, meals, offline sync and multiple admins.

## 10. Verify one full shift

1. Tap Let's start → enter an employee PIN → Drop cash → confirm the displayed employee/location.
2. Record a small test cash drop. Confirm it appears in Admin → Today's activity and Firestore's `cash` collection.
3. PIN → Log equipment → collect radio/keys → sign. Repeat Out/In as needed, confirm End shift and sign. Test Undo end shift, then In again.
4. Try a second employee collecting the same checked-out radio/key set; it must be rejected.
5. Restart the app and confirm cloud records are still available after admin sign-in.
6. Send a test email. Send previous day's report. Verify the cash CSV and signed HTML attachments arrive. Request a separate Radio & keys report for an inclusive date range.
7. After midnight, check the email and Firestore `deliveries` status.
8. Verify a completed database backup after its scheduled run.

Do not uninstall the old app or discard its database until you have imported any existing logs you want to keep.

## Import your existing Python database (optional)

No Python server is required. This is a one-time export only:

```sh
python3 migration/export_legacy.py /absolute/path/to/droplog.sqlite3 legacy-export.json
```

Install Google Cloud CLI from https://cloud.google.com/sdk/docs/install, then authenticate locally:

```sh
gcloud auth application-default login
gcloud auth application-default set-quota-project YOUR_PROJECT_ID
node migration/import_legacy.js legacy-export.json YOUR_PROJECT_ID YOUR_ADMIN_UID
```

The last command is a dry run. Check the totals. To import:

```sh
node migration/import_legacy.js legacy-export.json YOUR_PROJECT_ID YOUR_ADMIN_UID --commit
```

Use your admin UID from step 3. The script preserves timestamps, cash amounts, signatures and current equipment state. Existing log IDs are not overwritten. Old password/PIN hashes are not imported; in the app, tap each imported employee and assign a fresh PIN. Keep the exported data and old database backed up until you have verified the imported counts. Don't run the old Python app and new cloud app in parallel after migration: their databases do not sync.

## Troubleshooting

- **Missing google-services.json:** place your real file in the app folder and sync. No sample project config is included in the ZIP.
- **Function NOT_FOUND:** deploy the functions into the same project as google-services.json. Functions run in us-central1.
- **Admin access required:** the Firestore document `users/ADMIN_UID` must match the Authentication UID and have `role` = `manager`.
- **PIN token signing / signBlob error:** in Google Cloud IAM, inspect the runtime service account for the `pinLogin` Cloud Function. Enable IAM Service Account Credentials API and grant that runtime service account **Service Account Token Creator on itself**. Avoid using an admin private key in the APK. The relevant Firebase custom-token guidance is https://firebase.google.com/docs/auth/admin/create-custom-tokens.
- **Firestore or Firebase Auth permission denied inside a function:** inspect the runtime service account in Google Cloud. It needs Datastore User for Firestore and Firebase Authentication Admin for employee identities; the PIN function also needs token-signing permission described above. Grant these to the runtime service account, not to employee accounts. Firebase deployment handles the configured Secret Manager access; review deployment errors if that fails.
- **Email failed:** verify the SMTP secrets/parameters, sender verification and provider logs; redeploy functions after changing params/secrets. A saved recipient alone cannot send mail.
- **Daily report status remains sending after a process crash:** inspect mail-provider logs before resetting that delivery to `failed` or manually requesting another report. SMTP cannot guarantee exactly-once delivery across an uncertain connection failure.
- **Internet unavailable:** first complete one online PIN login per employee on the tablet. Cash, equipment and meal logs can then save offline and sync later. Admin account setup and reports need internet. Review pending / rejected logs in Admin → Offline logs · Sync review. See UPDATE_0.5.md.
- **Replacing tablet:** install the app configured for the same Firebase project, sign in as admin, and confirm cloud history. Room is a local mirror, not the cloud backup itself.

## Reference links

- Firebase Android setup: https://firebase.google.com/docs/android/setup
- Function deployment: https://firebase.google.com/docs/functions/get-started
- Scheduled functions: https://firebase.google.com/docs/functions/schedule-functions
- Firestore backups: https://firebase.google.com/docs/firestore/backups
- Room database: https://developer.android.com/training/data-storage/room
