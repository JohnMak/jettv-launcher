#!/bin/bash
# Set up JetTV Launcher on a new Android TV device over adb.
# Usage: tools/setup-device.sh <adb-serial> [--apk FILE | --version X.Y.Z] [--debloat LIST] [--no-home]
#   <adb-serial>  value of `adb devices` (USB serial or host:port). Network devices are connected automatically.
#   --apk FILE    install this APK instead of downloading the latest GitHub release
#   --version V   download release vV instead of the latest one
#   --debloat L   afterwards run tools/debloat.sh with the list L (asks for confirmation)
#   --no-home     do not set JetTV as the HOME app
# Everything here is reversible; see README.md ("Install") for what each step does.
set -euo pipefail
REPO="johnmak/jettv-launcher"
PKG=com.johnmak.atvlauncher
HERE="$(cd "$(dirname "$0")" && pwd)"

[ $# -ge 1 ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
SERIAL="$1"; shift
APK=""; VERSION=""; DEBLOAT=""; SET_HOME=1
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2;;
    --version) VERSION="$2"; shift 2;;
    --debloat) DEBLOAT="$2"; shift 2;;
    --no-home) SET_HOME=0; shift;;
    *) echo "unknown option: $1" >&2; exit 2;;
  esac
done

case "$SERIAL" in *:*) adb connect "$SERIAL" >/dev/null;; esac
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null || { echo "device $SERIAL not reachable" >&2; exit 1; }
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "device: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), API $SDK"
[ "$SDK" -ge 30 ] || { echo "JetTV needs Android 11 (API 30) or newer" >&2; exit 1; }

# 1. Fetch and verify the APK
if [ -z "$APK" ]; then
  WORK="$(mktemp -d)"
  if [ -n "$VERSION" ]; then REL="tags/v$VERSION"; else REL="latest"; fi
  URL="https://api.github.com/repos/$REPO/releases/$REL"
  curl -fsSL "$URL" -o "$WORK/release.json"
  python3 -I - "$WORK" <<'PY'
import json, sys, urllib.request
w = sys.argv[1]
r = json.load(open(w + "/release.json"))
assets = {a["name"]: a["browser_download_url"] for a in r["assets"]}
apk = next(n for n in assets if n.endswith(".apk"))
for n in (apk, apk + ".sha256"):
    urllib.request.urlretrieve(assets[n], w + "/" + n)
open(w + "/apk-name", "w").write(apk)
PY
  NAME="$(cat "$WORK/apk-name")"
  (cd "$WORK" && shasum -a 256 -c "$NAME.sha256")
  APK="$WORK/$NAME"
fi
echo "APK: $APK"

# 2. Install and grant
"${ADB[@]}" push "$APK" /data/local/tmp/jettv.apk >/dev/null
"${ADB[@]}" shell pm install -r /data/local/tmp/jettv.apk
"${ADB[@]}" shell rm -f /data/local/tmp/jettv.apk
for perm in ACCESS_FINE_LOCATION READ_TV_LISTINGS WRITE_SECURE_SETTINGS; do
  "${ADB[@]}" shell pm grant "$PKG" "android.permission.$perm"
done
"${ADB[@]}" shell appops set "$PKG" GET_USAGE_STATS allow

# 3. Screensaver (undo: adb shell settings delete secure screensaver_components)
"${ADB[@]}" shell settings put secure screensaver_components "$PKG/.StarfieldDream"
"${ADB[@]}" shell settings put secure screensaver_enabled 1

# 4. HOME role
if [ "$SET_HOME" -eq 1 ]; then
  "${ADB[@]}" shell cmd role add-role-holder --user 0 android.app.role.HOME "$PKG"
fi

# 5. Loopback ADB: the launcher talks to the device's own adbd on 127.0.0.1:5555 (brightness, self-update).
#    That needs adbd listening on TCP 5555. Over USB you can switch it on with `adb tcpip 5555`; on a
#    network-connected device it is already on. Also enable Developer options > Network/Wi-Fi debugging if the
#    device has no USB port. The first time the launcher connects, the TV asks "Allow USB debugging?" - tick
#    "Always allow" and confirm with the remote.
case "$SERIAL" in
  *:*) ;;
  *) echo "Note: run 'adb -s $SERIAL tcpip 5555' once if the device does not listen on TCP 5555 yet.";;
esac
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Launcher started. Approve the 'Allow USB debugging' prompt on the TV (Always allow)."

# 6. Optional debloat
if [ -n "$DEBLOAT" ]; then
  "$HERE/debloat.sh" "$SERIAL" "$DEBLOAT"
fi
echo "done"
