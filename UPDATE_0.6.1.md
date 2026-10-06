# DropLog 0.6.1 — version code 7

This update is for the existing DropLog 0.5.0 project, including the equipment Undo patch.

## Install the source update

Extract this update ZIP into a separate folder. Run its script with your CURRENT project folder:

```bash
bash "/path/to/extracted/update/apply-0.6.1.sh" "/Users/arund/Downloads/DropLog"
```

The script only copies the changed source files and backs up files it replaces. It preserves google-services.json, SMTP environment files, PIN_PEPPER, signing keys, and local.properties. It does not deploy or install a debug APK. You can alternatively copy each listed file manually, preserving the directory structure.

From your current project folder deploy the updated callable function monitoring:

```bash
cd functions
npm ci
npm test
cd ..
firebase deploy --only functions --project=droplog-27857
```

Open the CURRENT project in Android Studio, sync Gradle, then generate a signed RELEASE APK with the same existing keystore/alias/password used for your first release. Install over the existing tablet app. Do not uninstall with pending offline logs. No database reset, rules change, or new secret is needed. Version in the app header is 0.6.1; Android version code is 7.

## Admin PIN and gear

Admin is represented by the existing Manager role. Start → PIN → action menu now shows a gear for managers; the gear opens users, activity, reports, additional admins, offline review and app monitoring. Employees do not see the gear and Firebase still rejects their admin calls.

If an existing email admin has no PIN yet, use Admin recovery · Email sign-in once. Open Users, select that SAME admin profile, enter first/last name and job title if missing, retain Manager, and assign/confirm a unique PIN. Save, sign out and test that PIN. Do not create a duplicate employee profile for the same admin. Newly added email admins can be assigned PINs the same way. Admin functions need online authentication; cached offline PIN access only supports logging/cached views, not server administration.

## Icon

The update includes the generated DropLog image, adaptive icon XML, fallback launcher images, and explicit android:icon / android:roundIcon manifest references. Replace all included icon files along with the manifest. Keep your unrelated resources. The icon should appear after installing the new APK; launcher caches may require removing/readding the HOME SCREEN shortcut or restarting the tablet. Do not uninstall to refresh the icon.

## Monitoring

Release builds enable Crashlytics and Firebase Performance. Debug builds disable their collection at application startup. Custom traces measure PIN login, cash/equipment/meal saves, dashboard loads, report sends, offline batch/entry sync and queue health. A save trace distinguishes saved_locally from success. Queue metrics report pending/review counts and oldest pending seconds. These are observations at sync completion, not a continuous device heartbeat. Cold starts and network traces depend on Firebase's instrumentation; Compose screens are not individually measured as screens by this update.

App timings include local persistence and attempted sync. They are not identical to individual server execution time. Function monitoring separately logs operation, outcome, error_category and duration_ms for callable handlers. Expected validation/access rejections are distinguished from technical errors. No request bodies, PINs, employee identities, amounts or signatures are attached to these custom metrics. SDK automatic crash reports may still include exception information; do not add sensitive diagnostic logs.

Firebase Console → droplog-27857 → Crashlytics / Performance Monitoring: complete any console onboarding. Install the signed APK, use several actions online, then Admin gear → App health & monitoring → Send monitoring test. This submits an intentional NON-FATAL report and a diagnostic trace without crashing the app. Allow upload/processing time and restart/reopen the app if needed. A real fatal-crash smoke test should only be performed on a test tablet with no pending records, never a live staff session. The test button is disabled in debug builds. Console access still requires the owner's Google permissions, separate from the app admin role.

Cloud Logging Logs Explorer filter for callable operation summaries:

```text
resource.type="cloud_run_revision"
resource.labels.project_id="droplog-27857"
jsonPayload.message="droplog_operation"
```

In Google Cloud Monitoring use the Cloud Run revision resource to chart request latency, request count, errors and instance resource use for each function service. For operational errors make a logs-based counter using the filter above plus jsonPayload.outcome="error"; group by jsonPayload.operation if needed. Build an alert after establishing a baseline. No alert, billing budget, console dashboard or production deployment was created by this source update. Set those up in your project. Firebase custom timing traces can be filtered by app version; compare 0.6.1 and future releases using p50/p95, failures and sync backlog rather than totals alone.

## Verification and limits

34 backend tests passed, including PIN manager access, employee access denial, legacy compatibility, offline deduplication and sanitized monitoring output. Android resource XML and update script syntax were checked. Android compilation could NOT run here because Gradle downloads are blocked by the workspace network. This is a source update, not a prebuilt or device-tested APK. Sync/build in Android Studio and test on ONE tablet before installing broadly. Verify gear visibility by role, the launcher image, equipment Undo, online saving, offline/reconnect behavior and arrival of diagnostic telemetry.

SDK reference instructions: https://firebase.google.com/docs/crashlytics/android/get-started and https://firebase.google.com/docs/perf-mon/get-started-android
