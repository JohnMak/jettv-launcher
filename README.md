# JetTV Launcher

A fast, minimal home screen for Android TV / Google TV devices and projectors. It replaces the stock launcher with a
clock, a favorites row, content rows from your apps, an all-apps grid and a few device-specific extras.

Tested on an XGIMI HALO+ projector (Android 11) and a Xiaomi TV Box S, 2nd gen (Android 14, Google TV). Requires
Android 11 (API 30) or newer.

## Features

- **Favorites row**: pin apps, reorder them, long-press OK on a tile for actions. Recently opened apps get a dot.
- **Feed rows** from the Android TV content provider (`TvContract`, the same previews the stock launcher shows:
  continue watching, recommendations). An app picker chooses which apps contribute; row share follows how often you
  launch each app.
- **Kinopoisk ratings** under feed cards, matched by title and exact release year (strict for series too).
  Needs your own API token (kinopoiskapiunofficial.tech), stored on the device only; without it the feature is off and
  no requests are made.
- **Weather** (MET Norway, CC BY 4.0) with city search or detection by public IP, and the city's clock and timezone.
- **VPN button** showing VPN state; opens or silently toggles a VPN app of your choice.
- **Screensaver and dimming**: starfield or NASA videos, system screensaver integration, idle dimming.
- **All-apps grid**, ordered by launch frequency.
- **Brightness "sun"** on XGIMI projectors: lamp and picture control through the projector's own settings service
  (`gmpfUnit`), hidden on devices that do not have it.
- **Self-update** from GitHub Releases, with verification (see Security).

## Install

1. Download `jettv-launcher-X.Y.Z.apk` and its `.sha256` from the Releases page and check the digest.
2. Install and grant (replace `<serial>` with the value from `adb devices`):

```bash
adb -s <serial> install -r jettv-launcher-X.Y.Z.apk
adb -s <serial> shell pm grant com.johnmak.atvlauncher android.permission.ACCESS_FINE_LOCATION
adb -s <serial> shell pm grant com.johnmak.atvlauncher android.permission.READ_TV_LISTINGS
adb -s <serial> shell pm grant com.johnmak.atvlauncher android.permission.WRITE_SECURE_SETTINGS
adb -s <serial> shell appops set com.johnmak.atvlauncher GET_USAGE_STATS allow
adb -s <serial> shell cmd role add-role-holder --user 0 android.app.role.HOME com.johnmak.atvlauncher
```

The grants are for: Wi-Fi name and weather location (`ACCESS_FINE_LOCATION`), feed rows (`READ_TV_LISTINGS`),
switching the system screensaver from the launcher settings (`WRITE_SECURE_SETTINGS`), and the all-apps ranking
(`GET_USAGE_STATS`). `tools/setup-device.sh <serial>` does all of this, plus optional debloat, in one go.

### Loopback ADB and the "Allow USB debugging" prompt

Android gives apps no API to change projector brightness through the vendor service or to silently install an
update. JetTV therefore talks to the device's own `adbd` over `127.0.0.1:5555`, using an RSA key it generates and
keeps in its private storage. This needs:

- `adbd` listening on TCP port 5555. On a network-connected device it already does; over USB run
  `adb -s <serial> tcpip 5555` once (it resets on reboot on some devices; enable Developer options > wireless
  debugging where available).
- A one-time approval: the first time the launcher connects, the TV shows **Allow USB debugging?** for the launcher's
  key. Tick **Always allow** and confirm with the remote.

Without it the launcher still works; only brightness control and silent self-update are unavailable.

## Setting up a new device

- `tools/setup-device.sh <serial> [--apk FILE | --version X.Y.Z] [--debloat LIST]`: downloads and verifies the
  latest release, installs, grants, sets the screensaver and HOME role.
- `tools/debloat.sh <serial> <list>`: disables the listed packages with `pm disable-user --user 0` (reversible;
  asks for confirmation, saves an inventory first, writes a log). Lists are in `tools/debloat/`.
- `tools/undebloat.sh <serial> <list-or-log>`: re-enables them.
- `docs/new-device-prompt.md`: a prompt to give an AI coding agent to do the whole setup, including building a debloat
  list for a device that has none yet.

Return to the stock launcher at any time: `adb shell pm enable <stock launcher package>` and
`adb shell cmd role remove-role-holder --user 0 android.app.role.HOME com.johnmak.atvlauncher`.

## Building

Requirements: JDK 17, Android SDK (set `ANDROID_HOME` or `sdk.dir` in `launcher/local.properties`), git (the version
comes from tags).

```bash
cd launcher
./gradlew testDebugUnitTest      # unit tests, no device needed
./gradlew assembleDebug          # debug APK
tools/build-and-install.sh install   # (from the repo root) build + install + grants on the connected device
```

The Kinopoisk token is never built into the APK; enter it in the launcher settings (it is stored on the device only).

### Versions and signing

Versions are derived from git tags `vX.Y.Z`: a build on a clean tag is `X.Y.Z`, anything else is
`X.Y.Z-dev.N+gSHA`. Release tasks fail off a clean tag. Release signing reads `storeFile`, `storePassword`,
`keyAlias`, `keyPassword` from a properties file at `$JETTV_SIGNING_PROPERTIES` (default `materials/signing.properties`
next to the repo). Keystores and properties files are git-ignored.

## Releasing

Push a tag: `git tag vX.Y.Z && git push origin vX.Y.Z`. The GitHub Actions workflow runs the tests, builds the signed
APK, writes the `.sha256` file and creates the GitHub release with both attached. Installed launchers pick it up from
Settings > About.

## Security notes

- Loopback ADB accepts only a fixed whitelist of validated commands (the `gmpfUnit` brightness/contrast/lamp verbs
  with range-checked arguments, and the update install step); free-form shell strings are never built from network or
  user data.
- Self-update accepts an APK only when its SHA-256 matches the published digest, it is the same package at exactly the
  announced version, newer than the installed one, and signed by the same certificate as the installed app.
- The Kinopoisk token and the ADB key stay in the app's private storage. Nothing is sent to any server except the
  weather, rating, geocoding and update requests described above; requests carry a
  `JetTV-Launcher/<version> (+repo URL)` User-Agent and no personal data.

## Credits

Weather data: MET Norway (CC BY 4.0). Ratings: Kinopoisk via kinopoiskapiunofficial.tech. Screensaver video credit:
NASA Scientific Visualization Studio / STScI / ESA (Hubble), no endorsement implied. Icons: Lucide and Tabler
(licenses in `launcher/third_party/icons/`), font Outfit (OFL, `launcher/OFL.txt`). Developer notes:
`launcher/NOTES.md`.
