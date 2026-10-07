# DropLog 0.6.5 — Start at PIN

Android version code 11. The app opens directly to employee PIN entry. The welcome / Let’s start screen is removed. Saved entries, sign-out, idle logout and returning from admin sign-in all return to an empty PIN screen, ready for the next employee.

PIN entry includes an Admin email sign-in button for admin access/recovery and a version label. Admin PIN sign-in continues to open Admin options; employees continue to see their permitted actions. Session reset clears the prior PIN, selected logging destination and pending account-confirmation dialog.

Install the signed release over the existing app. No backend deployment or data reset is needed for this navigation change.

Release build and lint pass. All 7 Android unit tests pass. APK signing certificate matches the existing release.
