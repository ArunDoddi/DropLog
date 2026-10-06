# Verification — DropLog 0.5.0

- Gradle remains 9.4.1; Android version 0.5.0 (code 6).
- Android assembleDebug and testDebugUnitTest executed using Java 17 and SDK 35, with a temporary non-production google-services build fixture. The fixture is removed and no test APK is shipped.
- 33 Node handler/domain tests pass, covering original cash/equipment/report behavior, automatic meal windows (noon / 3 PM Central), duplicate / two-meal limits, timestamp-preserving offline replay, duplicate request IDs, grant reuse/revocation/expiry, changed shift conflicts, and multiple admin authorization.
- 3 Kotlin local-state tests pass: equipment In/Out/End/Undo state from queued events, acknowledged-entry deduplication, per-employee isolation, exact cash cents, meal day rollover and Central-vs-UTC midnight.
- These are deterministic local checks. Live Firebase deployment, Gmail SMTP, Android Keystore on a real device, airplane-mode / process-restart queue persistence, WorkManager reconnect behavior and real second-admin login require the device checks in UPDATE_0.5.md. They have not been exercised against your production project here.
- Room schema remains version 1 using the existing generic table; no destructive migration. Firestore direct-client access remains denied. Existing cloud data and project configuration are preserved.

- Clean npm ci succeeds with the 0.5.0 package/lock metadata.
- update-existing-project.sh passes bash syntax validation and a simulated-tool orchestration check for backups, preserved Firebase/SMTP files, APK update installation and emulator launch. The script has not been executed against your Mac or deployed cloud project here.
