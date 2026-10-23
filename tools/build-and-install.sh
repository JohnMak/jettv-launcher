#!/bin/bash
# Build the launcher and (optionally) install it.
# Usage: tools/build-and-install.sh [build|install|home]
#   build    - assembleRelease only (signed when signing properties are configured; version comes from the git tag) (default)
#   install  - build, push APK to /data/local/tmp, `pm install -r`, grant location (SSID),
#             READ_TV_LISTINGS (content feed), WRITE_SECURE_SETTINGS (settings screen switches the
#             system screensaver) and GET_USAGE_STATS app-op (all-apps ranking),
#             and sets the starfield dream as the system screensaver
#   home     - install + make it the default home (cmd package set-home-activity)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# JDK 17 and the Android SDK come from the environment (JAVA_HOME, ANDROID_HOME or launcher/local.properties sdk.dir).
PKG=com.johnmak.atvlauncher
APK="$ROOT/launcher/app/build/outputs/apk/release/app-release.apk"
MODE="${1:-build}"

(cd "$ROOT/launcher" && ./gradlew assembleRelease)
echo "APK: $APK ($(du -h "$APK" | cut -f1))"
[ "$MODE" = build ] && exit 0

# Target device: set ANDROID_SERIAL (host:port or USB serial) when more than one device is connected.
ADB="${ADB:-adb}"
"$ADB" push "$APK" /data/local/tmp/atvlauncher.apk
"$ADB" shell pm install -r /data/local/tmp/atvlauncher.apk
"$ADB" shell rm -f /data/local/tmp/atvlauncher.apk
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
"$ADB" shell pm grant "$PKG" android.permission.READ_TV_LISTINGS
"$ADB" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
"$ADB" shell appops set "$PKG" GET_USAGE_STATS allow
# System screensaver (dream): the launcher's starfield. Undo: adb shell settings delete secure screensaver_components
"$ADB" shell settings put secure screensaver_components "$PKG/.StarfieldDream"
"$ADB" shell settings put secure screensaver_enabled 1
if [ "$MODE" = home ]; then
  "$ADB" shell cmd package set-home-activity "$PKG/.MainActivity"
fi
