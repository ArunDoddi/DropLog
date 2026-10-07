# DropLog 0.6.3 — People & access

Android version code: 9. Install the signed release over the existing app, without uninstalling or clearing data.

## Users

Admin → Users opens the People & access directory, with name/assignment search, All/Employees/Admins filters, account cards and separate Edit details, Reset PIN and Delete actions. Add employee and Add admin are separate actions. Profile edits can retain the current employee PIN by leaving both new PIN fields empty. Forgotten PINs use the dedicated Reset PIN action.

Hotel Front desk and Housekeeping are mutually exclusive: selecting one disables the other. Deselect the current duty to switch. Restaurant and Bar are independent duty buttons; select one or both. Permissions are enforced by the cloud as well as the app. Existing Restaurant/Bar accounts retain both outlets until explicitly edited; saving them records the selected separate duties. Existing mixed hotel-duty profiles must be corrected when saved.

Edits, resets and deletions require the signed-in admin's own PIN in a confirmation dialog. Another admin's PIN cannot approve the change. An admin without a PIN first uses Admin options → Set my admin PIN and confirms their admin email password. That operation requires recent email authentication. PINs remain unique across all employees and admins.

Delete removes account access and Firebase Authentication identity; a disabled profile remains for attribution/audit, hidden from the directory. Existing cash, equipment and meal logs and signatures remain intact. Self-deletion and deleting/demoting the last admin are blocked. Admin management transactions serialize changes to protect concurrent deletion. Employees with an open equipment shift must end the shift first. If authentication cleanup fails, account access remains disabled and deletion can be retried.

Successful edits/resets/deletions clear the affected profile's cached access on this tablet. Other disconnected tablets may retain cached login access until they reconnect or the offline credential expires, but cloud sync rejects disabled accounts and revoked PIN grants. No queued records are silently discarded.

## Deployment

Deploy the updated backend before using the new account actions:

```sh
firebase deploy --only functions:droplog --project droplog-27857
```

This includes the new `setAdminPin` callable and updated account/profile, cash and offline handlers. It also deploys the earlier meal-server and dated-activity features included in this source tree. The daily cash schedule remains 4:00 AM America/Chicago. No live accounts were deleted. The matching backend was successfully deployed to droplog-27857 on October 6, 2026, after the tablet reported the older handler’s name/job-title validation during deletion.

## Design and verification

The existing DropLog Figma reference uses Roboto, Material controls, blue/teal accents and rounded tablet panels. The app's new Users directory follows those conventions. Figma's Starter MCP tool limit was reached during design-system discovery; no new Figma screen or prototype was saved.

47 deterministic backend tests pass, covering incompatible duties, independent Restaurant/Bar access, required own-admin PIN, PIN preservation/reset/revocation, protected deletion/demotion, concurrent cross-deletion and retained logs. Android release build, release lint and all 7 unit tests pass locally. The APK signature verifies and matches the existing release certificate. Tablet layout, keyboard behavior and live Firebase authentication should be smoke-tested after deployment and installation.

Signed APK: `app/build/outputs/apk/release/DropLog-0.6.3-release.apk`.
