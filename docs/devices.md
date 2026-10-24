# Devices

Supported: Android 11 (API 30) or newer. Devices are called by role; addresses and serials are intentionally not kept
in the repo.

| Role | Model | Android | Cleanup list |
|---|---|---|---|
| Projector | XGIMI HALO+ (XK03H, product aiolia) | 11 | `tools/debloat/xgimi-halo-plus.txt` |
| TV box 1 | Xiaomi MiTV-AFMU0 (Xiaomi TV Box S 2nd gen family) | 14 Google TV | none (the user said it is not needed) |
| TV box 2 | Xiaomi TV Box S 2nd gen, MiTV-AYFR0 (product soul) | 14 Google TV | `tools/debloat/xiaomi-tv-box-s2.txt` |

Current state: all three run the current package `com.johnmak.atvlauncher` and auto-update. The old package is
removed from both TV boxes; on the projector it is still installed (see ROADMAP).

## ADB on TCP 5555 (needed by the launcher)

The launcher connects to the device's own adbd at `127.0.0.1:5555`; the first connection shows "Allow USB debugging?"
for the launcher's key (tick "Always allow"). Observations:

- Projector: `service.adb.tcp.port=5555` is set at boot and adbd listens on 5555 after a reboot (verified); the
  persistent property is empty, the service property is set by the system.
- TV box 1: after enabling wireless debugging once and running `adb tcpip 5555`, port 5555 returned after a reboot
  (`service.adb.tcp.port=5555`, `adb_enabled=1`).
- Early on a Xiaomi box lost network ADB on every reboot while only wireless debugging was used; with `adb tcpip 5555`
  applied, TV box 1 kept it. If rights, brightness or updates report a connection failure, re-check this first.
- Android 11+ wireless debugging procedure for a box without USB access: Developer options > Wireless debugging > pair
  with the pairing code (`adb pair host:port`), `adb connect host:port`, then `adb tcpip 5555`, then
  `adb connect host:5555`. Verify with `adb shell getprop service.adb.tcp.port`.

## Projector: XGIMI HALO+ (XK03H)

Not rooted (user build, `ro.debuggable=0`, no su). The lamp and picture settings sit in the vendor HAL
`xgimi.hardware.gmpf@1.0::IGmpf`; SELinux lets only shell/system domains call it, so an installed app cannot. The shell
tool `/system/bin/gmpfUnit` is the route; the launcher runs it as the adb shell user through the loopback session.

`gmpfUnit func_name args...`, output `return_data : {N }`. Verbs the launcher uses (whitelisted, range-checked):

| Verb | Meaning |
|---|---|
| `getDlpLumensLevel` / `setDlpLumensLevel N` | lamp level 1..10 (works in mode 0 and 6; the launcher never switches the mode) |
| `getDlpLumensMode` | read only (0 standard, 3 economy, 6 custom, 15 high power, 16 eye protection, 17 bright, 18 performance) |
| `getBrightness 0` / `setBrightness 0 V` | picture brightness, window type 0, V in 0..100 |
| `getContrast 0` / `setContrast 0 V` | picture contrast, V in 0..100 |

Facts: setters return `return_data : {1 }` (picture) or nothing (lamp), readback matches immediately; the picture mode
stays 3 (USER) and values belong to that mode; latency 0.2-0.7 s per call, so writes are coalesced; defaults found
were brightness 50, contrast 30; the user chose neutral 50/50. Other verbs (`resetPqSettings`, keystone, factory,
`externDisplay`, `setDlpLumensMode` 15) are dangerous and not exposed. TurboLight (environment boost) is a separate
feature and not controlled. Details of the reverse engineering: lamp presets come from `BrightnessE` in the vendor
Settings app; Custom has levels 1..10; the vendor UI guards (battery under 30 %, eye-protection confirm) are bypassed
by direct calls, which is why the launcher stays within lamp levels and picture values only. Reading `gmpfUnit`
shows no Settings keys or properties for lamp state: ADB is the only source. A firmware update may remove `gmpfUnit`;
`BrightnessSupport` then hides the sun.

Projector cleanup list removes vendor extras and some Google TV services; optional entries (TTS, TalkBack, stock
ambient mode, vendor player, stock Google TV launcher once JetTV holds HOME) are never applied automatically.
Android 11 `dumpsys meminfo -c` prints one block; Android 14 prints two (RSS, then PSS): the parser takes the last.

## TV boxes (Android 14)

- No `gmpfUnit`: the brightness sun is hidden.
- `input keyevent` has no `--duration`; held-key tests need a burst `input keyevent 22 22 ...`.
- A device may go offline right after `pm install`; reconnect.
- The macOS Local Network privacy setting can block a Homebrew `adb` from reaching LAN devices; a small local TCP relay
  works around it (relay scripts are local, not in the repo).
- Screensaver: after a package migration `screensaver_components` still pointed at the removed old package, so Android
  used its own dream. Fixed in v0.3.1: `SystemScreensaver.sync` rewrites it on every start. Manual fix:
  `adb shell settings put secure screensaver_components com.johnmak.atvlauncher/.StarfieldDream`.
- Stock launcher on the Xiaomi boxes is replaced by granting JetTV the HOME role
  (`cmd role add-role-holder --user 0 android.app.role.HOME com.johnmak.atvlauncher`).
