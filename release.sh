#!/usr/bin/env bash
# Test, build and publish CashBalance.apk to GitHub Releases as v<versionName>.
# Run from Git Bash on the dev PC: the APK is signed with this PC's debug key, so a CI build couldn't update the installed app.
# Bump versionCode/versionName in app/build.gradle.kts and commit before running; an existing tag makes it fail.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=/d/android-dev/jdk-17 ANDROID_HOME='D:\android-dev\sdk'
export PATH="$JAVA_HOME/bin:$PATH"
GH="${GH:-/c/Program Files/GitHub CLI/gh.exe}"

v=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes; commit first so the release matches the code." >&2; exit 1; }

./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk CashBalance.apk
git push origin HEAD
"$GH" release create "v$v" CashBalance.apk --title "Cash Balance $v" --generate-notes --target "$(git rev-parse HEAD)"
