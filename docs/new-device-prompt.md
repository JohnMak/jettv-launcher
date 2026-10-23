# Prompt: set up a new Android TV device with JetTV

Copy everything inside the block below into an AI coding agent (for example Claude Code) started in a clone of this
repository, and replace the two `<...>` values.

````text
You are setting up JetTV Launcher on a new Android TV / Google TV device over adb and, with my approval, removing
unneeded services. Work in this repository. Device adb serial: <SERIAL>. Device description: <e.g. "Xiaomi TV Box S 2nd gen">.

SAFETY RULES (binding)
- Reversible changes only. The only way to remove a package is `pm disable-user --user 0 <pkg>`. NEVER run
  `pm uninstall`, `pm clear`, `pm uninstall-system-updates`, factory reset, flash/recovery commands, or anything that
  deletes data.
- Never disable anything without my explicit confirmation of the exact list. Show the list with a one-line reason per
  package first, then wait.
- Before the first change, save a backup inventory (installed, disabled, system packages, home activity, secure/global
  settings you will touch) into ./backup/ (git-ignored) and tell me where it is.
- Do not disable: the package holding the HOME role until JetTV is HOME and verified, the package installer,
  settings, Wi-Fi/network/bluetooth services, the input/keyboard services, Play Store / Play Services, the
  voice-remote/Assistant services (ask me first), Cast receiver (ask me first), and anything you cannot identify.
- Never read, print, copy or search for credentials: ADB keys (.adbkey*), keystores, signing.properties, .env files,
  account names, Wi-Fi passwords, tokens. Do not put device serials, IPs, MACs or account names into any file that is
  committed.
- Only touch the one device with the serial above (always `adb -s <SERIAL>`). Do not push, tag, or publish anything.

STEPS
1. Verify the device: `adb -s <SERIAL> get-state`, model, Android version (needs API 30+), free storage.
2. Run `tools/setup-device.sh <SERIAL>` (it downloads the latest release APK, verifies its SHA-256, installs, grants
   permissions, sets the screensaver and the HOME role). Read the script first and tell me what it will do.
3. Make sure adbd listens on TCP 5555 (needed for loopback ADB: brightness and self-update). On a USB-connected device
   run `adb -s <SERIAL> tcpip 5555`. Then ask me to look at the TV and approve "Allow USB debugging?" (Always allow).
4. Verify: JetTV is the HOME app (`adb shell cmd role get-role-holders --user 0 android.app.role.HOME`), the launcher
   process runs, `adb logcat -d -s AndroidRuntime:E` shows no crashes. Press HOME via `adb shell input keyevent 3`.
5. Debloat (optional, only if I agree):
   a. If tools/debloat/ has a list for this model, show it to me and use it.
   b. Otherwise build one (see "Building a debloat list" below), save it as tools/debloat/<model>.txt in the same
      format as the existing lists, and show it to me.
   c. After my confirmation run `tools/debloat.sh <SERIAL> <list>`; keep the log it writes.
6. Re-verify: HOME works, apps launch, Wi-Fi works, the remote's keys work, reboot the device once and check again.
7. Report: what was installed, which packages were disabled (from the log), where the inventory and log are, and the
   exact command to undo (`tools/undebloat.sh <SERIAL> <log>`).

BUILDING A DEBLOAT LIST FOR AN UNKNOWN DEVICE
- Collect: `pm list packages -f --user 0`, `pm list packages -s --user 0` (system), `pm list packages -3 --user 0`
  (user apps), `dumpsys package <pkg>` for unknown ones, and `dumpsys activity services` / `dumpsys battery` /
  `top` to see what runs in the background.
- Candidates, by category (disable only if the package exists on this device):
  * setup wizards (`*setupwraith*`, `*setupwizard*`) once setup is finished;
  * vendor analytics, statistics, feedback, telemetry and "legal/terms" pop-up services;
  * vendor content hubs, promo/ads services, vendor home/launcher alternatives and recommendation services that JetTV
    replaces (stock tvrecommendations, backdrop/ambient screensaver);
  * vendor media player/gallery/music/file manager apps you do not use; print spooler; Nearby/share pop-ups;
  * accessibility helpers (TalkBack, TTS) only if I do not need them;
  * retail demo packages.
- Never include: the HOME package while it still holds the role, system UI, settings, package installer/permission
  controller, connectivity/Wi-Fi/bluetooth/network, input methods, DRM/media codecs services, `com.android.*`
  framework packages you cannot explain, the remote-control/IR services, and update clients (OTA) unless I ask.
- For every candidate write: the package, the one-line reason, and your confidence. Mark anything uncertain as an
  optional `#?` line (commented out). If a package's role is unclear, leave it out.
- Apply in small batches (about five), after each batch run the verification in step 6, and stop at the first
  symptom (missing remote keys, no network, broken app launches, boot problems). To roll back run
  `tools/undebloat.sh <SERIAL> <log>`.
````
