# Employee roles and meal reporting update

Create or edit an employee in Admin → Users. Select Housekeeping, Front desk, or Restaurant / Bar. Housekeeping employees have exactly two actions: Log equipment and Log meal (Log Ortiz meal for Ortiz employees). Choose Ortiz or Full time when assigning housekeeping. Existing employees without an employment type default to Full time; edit Ortiz employees to assign the correct type. Existing mixed-duty profiles show housekeeping actions when equipment duty is present; save an explicit department to replace mixed duties.

Admin accounts use the existing Manager role. PIN sign-in for these accounts opens Admin options. Front desk accounts see only Drop cash. PIN assignment remains protected by an atomic database transaction and displays a PIN already taken dialog on collision.

Employee sessions return to the welcome screen after one minute without interaction. Touch, scrolling, signing and keyboard activity reset the timer. A save already in progress finishes before idle logout. Admin sessions are excluded.

Admin → Check drop, equipment & meal logs provides inclusive date filters in America/Chicago and tables for cash, equipment and meals. Choose Meal → View monthly meal report to use the month containing the From date. The report displays employee breakfast/lunch/dinner/total counts, date-wise counts, and signed meal sheets grouped by date and meal, with server name and distinct employee count. Empty months display no records. Existing two-meal-per-day limits and automatic meal windows remain in effect. The monthly report is viewed in the app; monthly email scheduling is not added.

Admin → Log Server saves a server for a date and meal. New meal records store the server name and employment type. Reports also resolve the saved date/meal server, allowing a server to be entered or corrected after employees signed. Signatures and employee records are retained.

## Installation

Deploy the updated Firebase functions before installing the updated app: `firebase deploy --only functions`. New callable functions are `activity` and `logServer`; existing `users`, `meal` and profile responses also change. Direct Firestore access remains denied. The new queries use single-field indexes. Install the APK over the existing installation without clearing app data. Deployment and installation have not been performed by this change.

## Verification

37 deterministic Node tests pass, including employment-type validation, PIN-login propagation, server persistence, corrected server lookup, Central date bounds and admin-only report access. Real tablet idle/logout and visual layout should be smoke-tested after installation.

Android `assembleDebug` and `testDebugUnitTest` pass using installed Java 17 with a temporary daemon-toolchain override; the original Java 25 daemon configuration was restored. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
