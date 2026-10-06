#!/usr/bin/env bash
set -euo pipefail

# Run from the extracted NEW ZIP. The argument is your existing Android Studio project.
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -ne 1 ]]; then
  printf 'Usage: bash "%s/update-existing-project.sh" "/path/to/your/existing/DropLog"\n' "$source_dir"
  exit 1
fi
target_dir="$(cd -- "$1" && pwd)"
if [[ "$target_dir" == "$source_dir" ]]; then
  echo 'Choose your EXISTING Android Studio project as the argument, not this extracted update folder.'
  exit 1
fi
for path in app/build.gradle.kts gradlew functions/package.json app/google-services.json; do
  if [[ ! -f "$target_dir/$path" ]]; then
    printf 'Required existing project file is missing: %s\n' "$target_dir/$path"
    exit 1
  fi
done
for command in npm firebase java; do
  if ! command -v "$command" >/dev/null 2>&1; then
    printf 'Install/configure %s first, then run this script again.\n' "$command"
    exit 1
  fi
done
project_id="${DROPLOG_FIREBASE_PROJECT:-droplog-27857}"
backup_dir="$target_dir/droplog-update-backups/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$backup_dir"
files=(
  app/src/main/java/com/droplog/app/MainActivity.kt
  app/src/main/java/com/droplog/app/CloudApi.kt
  app/src/main/java/com/droplog/app/OfflineSupport.kt
  app/src/main/java/com/droplog/app/LocalLogProjection.kt
  app/src/main/java/com/droplog/app/LocalDatabase.kt
  app/src/test/java/com/droplog/app/LocalLogProjectionTest.kt
  app/src/main/AndroidManifest.xml
  app/build.gradle.kts
  functions/index.js
  functions/domain.js
  functions/package.json
  functions/package-lock.json
  functions/test/service.test.js
  functions/test/domain.test.js
  firestore.indexes.json
  UPDATE_0.5.md
)
for file in "${files[@]}"; do
  [[ -f "$source_dir/$file" ]] || { printf 'Update ZIP is incomplete: %s\n' "$file"; exit 1; }
done
for file in "${files[@]}"; do
  if [[ -f "$target_dir/$file" ]]; then
    mkdir -p "$backup_dir/$(dirname "$file")"
    cp -p "$target_dir/$file" "$backup_dir/$file"
  else
    printf '%s\n' "$file" >> "$backup_dir/new-files.txt"
  fi
  mkdir -p "$target_dir/$(dirname "$file")"
  cp -p "$source_dir/$file" "$target_dir/$file"
done
printf 'Updated source files. Previous versions saved in: %s\n' "$backup_dir"
echo 'Installing and checking Firebase functions…'
(cd "$target_dir/functions" && npm ci && npm test)
echo 'Deploying functions and Firestore configuration…'
(cd "$target_dir" && firebase deploy --only firestore,functions --project "$project_id")
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -d "$sdk_dir" ]]; then export ANDROID_HOME="$sdk_dir"; fi
echo 'Building the Android APK and running local-state tests…'
(cd "$target_dir" && bash ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest)
apk="$target_dir/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo 'Build completed, but the APK was not found at the standard path. Check your custom build output directory.'; exit 1; }
printf 'APK ready: %s\n' "$apk"
adb_command="$sdk_dir/platform-tools/adb"
if [[ ! -x "$adb_command" ]] && command -v adb >/dev/null 2>&1; then adb_command="$(command -v adb)"; fi
if [[ ! -x "$adb_command" ]]; then
  echo 'No adb found. Open this existing project in Android Studio, sync Gradle, and click Run.'
  exit 0
fi
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  serial="$ANDROID_SERIAL"
else
  device_count="$("$adb_command" devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')"
  if [[ "$device_count" != 1 ]]; then
    echo 'Connect one device or start one emulator, then click Run in Android Studio. With multiple devices, set ANDROID_SERIAL before rerunning.'
    exit 0
  fi
  serial="$("$adb_command" devices | awk 'NR>1 && $2=="device" {print $1}')"
fi
echo 'Installing as an update; existing app data is retained…'
"$adb_command" -s "$serial" install -r -t "$apk"
"$adb_command" -s "$serial" shell am start -n com.droplog.app/.MainActivity
echo 'DropLog 0.5.0 installed and opened. Sign in online once with each employee PIN to prepare offline use.'
