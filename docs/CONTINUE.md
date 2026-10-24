# Continuing the work from a fresh clone

Everything needed to resume is in this repository plus a few local-only secrets described below. Read, in order:
`CLAUDE.md` (rules), this file, `docs/architecture.md`, `docs/decisions.md`, `docs/devices.md`, `docs/ROADMAP.md`.

## 1. Prerequisites

- JDK 17 (macOS: `brew install openjdk@17`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`). The default `java`
  may be 1.8 and will not work.
- Android SDK (platform 34, build-tools). Set `ANDROID_HOME` or put `sdk.dir=...` into `launcher/local.properties`
  (git-ignored; never commit it).
- `git`, `adb`, `gh` (GitHub CLI, logged in with push rights), `python3`, `curl`, `shasum`.
- Optional: `bd` (beads) if you want local task tracking; the database is not published (see ROADMAP).

## 2. Baseline tag (required to build)

Versions are derived from tags (`launcher/app/version.gradle`). A fresh clone has `vX.Y.Z` release tags, so Gradle
works as is. If you cloned without tags (`git fetch --tags`) or work on a history without any tag, create the
development baseline on the first commit:

```bash
git tag v0.0.1 $(git rev-list --max-parents=0 HEAD)
```

`v0.0.1` is only a local baseline: CI skips it (no release). Do NOT use `v0.0.0` (versionCode 0 is rejected by AGP).
Versions: minor and patch must be <= 999; versionCode = X*1_000_000 + Y*1_000 + Z.

## 3. Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd launcher
./gradlew testDebugUnitTest assembleDebug      # unit tests (offline) and debug APK
```

Install on a device: `tools/build-and-install.sh install` (builds a release APK, so the signing properties must exist;
for a quick debug install push `launcher/app/build/outputs/apk/debug/app-debug.apk` and `pm install -r` it; a
debug build has a different signature than releases, so it cannot replace an installed release without uninstalling).

## 4. Local-only material (not in the repo)

Recreate or restore these on a new machine:

- **Release keystore** `materials/jettv-release.jks` and **`materials/signing.properties`**:
  ```properties
  storeFile=/absolute/path/to/materials/jettv-release.jks
  storePassword=<store password>
  keyAlias=<alias>
  keyPassword=<key password>
  ```
  Override the properties location with `JETTV_SIGNING_PROPERTIES`. Both are git-ignored (`*.jks`, `materials/`).
  Back them up somewhere safe. **If the keystore is lost, installed apps cannot be updated**: Android rejects an APK
  signed with another key, and the self-updater verifies the signer too. Devices would have to be reinstalled
  (uninstall, install the new build, grant rights again, settings are lost) and the GitHub secrets replaced.
- **GitHub Actions secrets** (repo Settings > Secrets): `JETTV_KEYSTORE_B64` (base64 of the .jks),
  `JETTV_STORE_PASSWORD`, `JETTV_KEY_PASSWORD`, `JETTV_KEY_ALIAS`. Without them the release workflow fails at
  "Prepare release signing".
- `launcher/local.properties` (SDK path), ADB keys of the workstation (`~/.android/`), nothing else.

## 5. Cutting a release

1. Tests green, working tree clean, `docs/CHANGELOG.md` updated.
2. `git tag vX.Y.Z && git push origin main vX.Y.Z` (SemVer; the tag must match `vX.Y.Z`).
3. CI (`.github/workflows/release.yml`) runs tests, builds the signed release APK, verifies versionName equals the tag,
   publishes the GitHub release with `jettv-launcher-X.Y.Z.apk` and `.sha256`. Not a draft, not a prerelease
   (the updater ignores both).
4. Check the release page, then watch a device update (section 6).

## 6. How devices update

The launcher asks the GitHub "latest release" API (`ReleaseFeed`), downloads the APK, verifies it (`ApkVerifier`:
SHA-256 from the release, same package, exact announced version, newer than installed, same signing certificate) and
installs it through its own loopback ADB session. No toast, no dialog. An update is looked for and installed when:

| Trigger | Rule |
|---|---|
| Boot | Process starts within 10 min after device boot: check immediately (retry every 30 s on network failure) and install right away |
| Process start | Check immediately |
| Periodic | Tick every 10 min; a check when 6 h passed since the last good answer |
| Idle | Verified update installs when the home screen is in front and untouched for 10 min |
| Screensaver | Installs while the in-launcher or system screensaver is showing |
| Manual | Settings > About > check for updates; installs right after verification |

After 2 failed installs of one version the updater stops retrying that version. Requirement: adbd on TCP 5555 and the
one-time "Allow USB debugging?" approval on the device (see `docs/devices.md`). Without it self-update and brightness
do not work.

## 7. Setting up a new device

1. Enable Developer options and USB/network debugging on the device, connect `adb` (USB, or `adb connect host:5555`).
2. `tools/setup-device.sh <serial> [--debloat tools/debloat/<list>.txt]` downloads and verifies the latest release,
   installs, grants (`ACCESS_FINE_LOCATION`, `READ_TV_LISTINGS`, `WRITE_SECURE_SETTINGS`, usage-stats app-op),
   sets the screensaver and the HOME role. See the header of the script for options.
3. Make adbd listen on TCP 5555 (`adb tcpip 5555` over USB, or the wireless-debugging procedure in `docs/devices.md`)
   and approve the "Allow USB debugging" dialog with "Always allow".
4. For a device without a cleanup list, let an agent follow `docs/new-device-prompt.md` (it also builds the list).
5. Verify: launcher is HOME, no missing-rights warning in Settings, screensaver set, `adb shell logcat -d` shows no
   crashes, Settings > About shows the version and a successful update check.

Return to the stock launcher: `adb shell pm enable <stock launcher package>` and
`adb shell cmd role remove-role-holder --user 0 android.app.role.HOME com.johnmak.atvlauncher`.

## 8. Migration notes (old package to the current one)

The package was renamed to `com.johnmak.atvlauncher` (old: a personal-name package, different signature, so no
in-place update was possible). Migration per device: pull the old settings files over adb (local-only script
`tools/migrate-settings.sh`, not published), install the new build, grant rights, set HOME, import settings on first start
(`SettingsImport` reads `getExternalFilesDir/import/settings.json`, strict whitelist), then uninstall the old package
after the user confirms. TV box 1 and TV box 2 are migrated and cleaned; the projector runs the new package but its
old package is still installed (see ROADMAP). After the last device is clean, delete `SettingsImport` and
`LegacyPurge`.

## 9. Where things are

- Decisions and their reasons: `docs/decisions.md`.
- Open work and stale tracker items: `docs/ROADMAP.md`.
- Version history: `docs/CHANGELOG.md`.
- Device quirks, gmpfUnit verbs: `docs/devices.md`.
- Home-screen behaviour details (focus, backdrop, favorites row, weather, VPN): `launcher/NOTES.md`.
- Daily workflow and operations: `docs/dev.md`, `docs/prod.md`.
