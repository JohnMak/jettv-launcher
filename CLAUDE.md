# JetTV Launcher - agent instructions

Start here, then read `docs/CONTINUE.md`.

## What this is

JetTV Launcher (`com.johnmak.atvlauncher`) is a Java Android TV / Google TV home-screen replacement (minSdk 30,
targetSdk 34). It runs on an XGIMI HALO+ projector (Android 11) and two Xiaomi TV boxes (Android 14). Public repo:
github.com/JohnMak/jettv-launcher. Releases are signed APKs built by CI from `vX.Y.Z` tags; installed launchers update
themselves silently.

## Repo map

| Path | Content |
|---|---|
| `launcher/` | Gradle project (module `app`). Sources `app/src/main/java/com/johnmak/atvlauncher/`, tests `app/src/test/`, fixtures `app/src/test/resources/` (`release/`, `stats/`), asset `app_descriptions.tsv`. `launcher/NOTES.md` = detailed behaviour notes of the home screen. |
| `tools/` | Published scripts: `build-and-install.sh`, `setup-device.sh`, `debloat.sh`, `undebloat.sh`, lists in `tools/debloat/`. Other files in `tools/` are local-only (git-ignored). |
| `docs/` | `CONTINUE.md`, `architecture.md`, `decisions.md`, `devices.md`, `ROADMAP.md`, `CHANGELOG.md`, `dev.md`, `prod.md`, `new-device-prompt.md`. |
| `.github/workflows/release.yml` | Tag-triggered signed release. |
| `.beads/` | Beads (bd) config only; the issue database is NOT published. |
| `materials/` | Local only: release keystore and `signing.properties`. Never commit, never print. |

The root `.gitignore` is a whitelist: a new top-level file or folder must be added there to be tracked.

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17      # default java may be 1.8; JDK 17 is required
git tag v0.0.1 <first-commit>                       # local development baseline, see docs/CONTINUE.md
cd launcher && ./gradlew testDebugUnitTest assembleDebug
```

The version comes from git tags (`launcher/app/version.gradle`); without a `vX.Y.Z` tag in history Gradle fails.
Release tasks work only on a clean tag checkout.

## Principles

- One service, one logic path. No fallbacks, no `SDK_INT` forks, no legacy branches (legacy means a fork).
- Errors are values: `FetchResult<T>` (value or message, never both) and typed errors; validate at boundaries.
- Device shell: ONLY typed `ShellCommand` (`Gmpf`, `Pm`, `Stats` factories) with validated arguments through
  `AdbSession`. Never build a shell string from user or network data.
- Pure logic lives in small classes with unit tests (`*Test.java`); Android adapters stay thin.
- Bugs: TDD. Write the failing test first (unit, integration, e2e scenario), localize, then fix. Extend existing tests
  with new asserts before adding new test cases.
- Tests run offline: stubs and fixtures only, no network, no device.
- Architecture over speed. Product, pricing and UX decisions belong to the user (see `docs/decisions.md`).
- Commits only when tests pass. No Co-Authored-By trailers.

## Release process

Update `docs/CHANGELOG.md`, commit, then `git tag vX.Y.Z && git push origin main vX.Y.Z`. CI runs the tests, builds the
signed APK, checks versionName == tag, publishes `jettv-launcher-X.Y.Z.apk` plus `.sha256`. Details: `docs/prod.md`.

## Device safety rules

- Never fully uninstall a system package. Cleanup uses only reversible actions: `pm disable-user --user 0`,
  `pm uninstall -k --user 0` (keeps data, restorable with `cmd package install-existing`), and `pm uninstall`
  for updates of system apps.
- Cleanup runs only through typed commands built by `CleanupPlanner` and is filtered by `ProtectedPackages`
  (FULL = never touched; DISABLE_ONLY = HOME handlers, disable only). Do not weaken those rules.
- Do not call `gmpfUnit` verbs other than the whitelisted lamp/picture ones (`resetPqSettings`, keystone and
  factory verbs are dangerous).
- Back up the package inventory before changing a device; destructive device actions need the user's explicit OK.
- Keep exactly one known process of the service on a device while testing (no stale old build).

## How devices are reached

Devices are reached with `adb` over TCP port 5555 (the same channel the launcher uses to itself on loopback). On a
workstation where the OS blocks direct LAN access for `adb`, a local TCP relay script may be needed; those relay
scripts are device-specific and NOT in the repo. Recreate one if required (a plain port forward to the device's
5555) and do not commit addresses. Details and per-device quirks: `docs/devices.md`.

## Task tracking

The project used beads (`bd`) locally, but the database is not in the repo. Open work is listed in
`docs/ROADMAP.md`; recreate beads from it with `bd create` if you use bd. `AGENTS.md` has the generic bd workflow.
