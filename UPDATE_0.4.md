# DropLog 0.4 — locations, working shifts and signed reports

## Keep your existing database
Do not delete Firestore, Authentication users, cash/events/shifts, equipment locks, report settings or the Room database. This update adds fields and accepts existing records. Retain the same Firebase project, application ID and PIN_PEPPER. Do not rotate PIN_PEPPER: existing employee PIN hashes depend on it.

Old cash records remain in reports with their original names and amounts. Missing meal, outlet and signatures are identified as legacy data; no missing information is invented. Legacy location/job-title fields remain readable. A job title containing “housekeep” initially maps to equipment; other legacy employees initially map to cash at their existing location. Review every employee's duties in Admin rather than relying on job titles.

## Replace source together
Back up your current project. Copy these from this ZIP into matching project paths:

- app/src/main/java/com/droplog/app/MainActivity.kt
- app/src/main/java/com/droplog/app/CloudApi.kt
- app/src/main/res/drawable/ic_settings.xml
- app/build.gradle.kts (version 0.4.0/code 4; Gradle stays 9.4.1)
- functions/ including index.js, domain.js, package.json, package-lock.json and test/
- firestore.indexes.json

Keep your own app/google-services.json, .firebaserc, functions/.env.YOUR_PROJECT_ID, local.properties and signing keystore. Do not replace your SMTP_PASSWORD or PIN_PEPPER with new values for this upgrade. Never copy node_modules or build folders.

Alternatively open the extracted project in Android Studio and transfer your configuration files into it.

## Deploy before installing the new APK
From the DropLog project root:

```sh
firebase use
cd functions
npm ci
npm test
cd ..
firebase deploy --only firestore,functions
```

Confirm the selected Firebase project matches app/google-services.json. Deployment adds `sendReport`, updates the existing functions and excludes cash signatures from indexing. No database reset or record migration is required. The existing dailyReport scheduler remains at midnight America/Chicago and now sends only cash reports. Stop using old APKs after the deployment because new cash submissions require signatures and staff permissions are enforced on the server.

In Android Studio sync and build/run, or run `./gradlew assembleDebug`. Install `app/build/outputs/apk/debug/app-debug.apk` over the old installation using the same signing key. Uninstalling deletes local cached data. Cloud records remain, but preserve local data where possible.

## Configure users
Admin → Users → tap each existing user. Enter first and last name, job title, role, one or both locations and all applicable duties. Re-enter/confirm the existing PIN (or intentionally assign a new unique PIN) and Save.

Duties:
- Hotel front desk: signed Hotel cash drops.
- Restaurant / Bar: signed cash drops for an outlet and meal.
- Housekeeping: radio/key checkout and return at assigned locations.

Users can have multiple duties. Managers automatically have all duties at both locations. Existing email/password managers can also assign themselves a PIN by editing their user record; their email login remains unchanged. New managers created in the app use PIN login. A manager can open the gear settings and return to the admin dashboard. Creating an email/password login remains a Firebase Authentication setup operation.

PIN login routes a single-choice user directly to the relevant form. Multiple-choice users select their Working shift from a dropdown. Restaurant/Bar cash then asks for outlet and meal: Restaurant allows Breakfast, Lunch and Dinner; Bar allows Lunch and Dinner only.

An active housekeeping shift remains at its original location. Return/close that shift before starting another location.

## Housekeeping
- First collection: Start shift, enter radio/key numbers, sign.
- Out: return both items and sign.
- In: collect items again, enter numbers and sign. Repeat as needed.
- End shift: confirm “Are you sure to End the Shift?”, then sign. Any checked-out equipment is returned.
- Undo end shift: on the next PIN login choose Undo end shift and sign. This reopens the same shift with items marked returned; choose In on the following login to collect equipment. It does not take equipment away from someone who collected it after your return.
- Start new shift: after ending a shift, collect equipment for a new shift instead of undoing. Once a new shift starts, the earlier shift cannot be undone.

Every transition is recorded as a new event; ending or undoing never deletes event history. Existing lunch states are understood by the new app.

## Email reports
Admin → gear icon → save the recipient and enable Daily cash report. Test SMTP with Send test email.

- Automatic: previous-day cash report at midnight Central time, including empty days. No equipment report is attached.
- Manual cash or Radio & keys: select From/Through dates, both inclusive, then Send selected report. A single date uses the same start/end. Range maximum 366 days.
- Each report has a CSV attachment and an HTML attachment containing readable drawn signatures. Download/open the HTML in a browser and print/save as PDF if needed. CSV retains signature stroke coordinates as well.
- Equipment report includes outstanding items as of the end of the selected range.
- No automatic radio report is scheduled.

## Signature storage
Signatures are lists of strokes with normalized x/y points. Firestore stores the JSON as a string to avoid nested-array restrictions; the Room mirror stores the confirmed cloud record. Cash entries store user ID, full name, first/last names, department, outlet, meal, cents, UTC timestamp, signature and a unique request ID. Equipment events also store shift ID, location, action/state and numbered items. Report dates/times display Central time.

## Verify after upgrading
1. Create a Hotel front desk user; PIN opens the Hotel cash form automatically.
2. Create a Restaurant cash user; verify Bar cannot select Breakfast.
3. Create a dual-location/dual-duty user; verify the Working shift dropdown routes correctly.
4. Save cash with a signature; view Firestore cash fields.
5. Run Start → Out → In → Out → In → End, then Undo end → In. Check equipment locks and event history.
6. Request cash and equipment reports for one day and a range; open signature attachments.
7. Confirm automatic cash report after midnight, without radio attachments.

Figma edits were blocked by the Starter plan MCP allowance during this update. The Android implementation was updated; the existing Figma file has not been revised to match version 0.4.

## PIN login shows INTERNAL / signBlob permission denied
This is a cloud IAM configuration issue, not an employee PIN/database reset issue. Check `firebase functions:log --only pinLogin`. If it reports `iam.serviceAccounts.signBlob` denied, identify the function's runtime service account in Google Cloud Functions/Cloud Run settings and grant it Service Account Token Creator on that same service account. Enable the IAM Service Account Credentials API. Use Google Cloud Shell if gcloud is not installed locally:

```sh
gcloud services enable iamcredentials.googleapis.com --project=YOUR_PROJECT_ID
gcloud iam service-accounts add-iam-policy-binding YOUR_RUNTIME_SERVICE_ACCOUNT_EMAIL \
  --member="serviceAccount:YOUR_RUNTIME_SERVICE_ACCOUNT_EMAIL" \
  --role="roles/iam.serviceAccountTokenCreator" \
  --project=YOUR_PROJECT_ID
```

Replace both service account placeholders with the same verified runtime account. Allow time for IAM propagation and retry PIN login. No app rebuild, PIN change or database deletion is required for this permission fix. The new cloud code logs token creation failures and returns an actionable setup message instead of a generic INTERNAL error. Other errors still require inspecting the function logs.
