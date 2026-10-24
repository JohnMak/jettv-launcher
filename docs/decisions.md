# Decisions and reasons

Chronological; "user" = the project owner. Product decisions are the user's; do not reverse them without asking.

## Early work (2026-10-21/22)

- Own launcher replaces the stock ones; Russian UI texts, greeting without a name.
- Favorites row (long-press OK to manage), "continue watching" and a mixed recommendations row from the TvProvider
  (`READ_TV_LISTINGS`), all-apps grid sorted by usage. Tiles small, no centered title, backdrop tinted from the focused
  icon/poster.
- Weather from MET Norway only (the Gismeteo option was removed on request); city by search or by public IP.
- VPN pill in the top bar that toggles a chosen VPN app.
- Opened-dot on tiles shows the real in-memory state (via the DUMP grant), not a guess.
- Ambient video backdrops were built, then removed from the UI; the player classes are kept for future YouTube
  backgrounds (ROADMAP).

## Brightness (2026-10-22/23)

- No root exists on the projector, so brightness goes through the device's own adbd on loopback (dadb) running
  `gmpfUnit` as the shell user. A normal app cannot reach the vendor HAL (SELinux).
- The controller never switches the lamp mode; it only sets the level (works in the current mode).
- First design: 10 lamp dashes. Then edges were added with the picture brightness/contrast: 4+9+4, finally 3+9+3 (15
  dashes, 16 positions). Picture values per edge position: 27/35/42 on the dim side and 57/65/72 on the bright side;
  neutral picture is 50/50 (brightness and contrast carry the same value). Lamp 1..10 walks the middle at neutral.
- Popup: zone icons (dim image, lamp, bright image); DOWN enters adjust mode, a second DOWN closes the popup and moves
  focus to the tiles.
- Hide the sun where `gmpfUnit` is missing (TV boxes).
- Avoid every other `gmpfUnit` verb (whitelist), keep vendor guards in mind (see `docs/devices.md`).

## Project, repo and releases (2026-10-23)

- Public GitHub repo `JohnMak/jettv-launcher`; package renamed to `com.johnmak.atvlauncher` (a personal-name package
  was in use before). Commit author kept; only the User-Agent has no personal contact.
- SemVer from git tags `vX.Y.Z`; baseline tag `v0.0.1` (not `v0.0.0`, versionCode 0 is rejected); a local `v0.1.0` was
  deleted on purpose so the first release already contained the updater.
- Signed releases by CI from tags; artifacts: APK plus `.sha256`.
- Whitelist `.gitignore`: only deliberately published files; secrets, keys, tokens and personal material never leave
  the machine. Public docs carry no addresses, serials, accounts or locations.
- Third-party tokens are never compiled in; the Kinopoisk token feature was dropped together with Kinopoisk.

## Self-update and rights (2026-10-23)

- Silent updates: no toast, check every 6 h, background download, install during idle (10 min) or screensaver; on boot
  install immediately (v0.3.3). Verification: SHA-256, same package, exact version, newer, same signer.
- Stop retrying a version after 2 failed installs.
- The permission prompt appears only on the user's key press and never after a silent update (avoids popups in a
  relaunched process).
- Rights warning in Settings (yellow) with grant button and a "set as home" button; grants go through loopback ADB.

## Settings migration (2026-10-23/24)

- Old package had a different identity, so settings are carried over once: pull the old prefs over adb, push as
  `import/settings.json`, strict whitelist, all-or-nothing import (`SettingsImport`); data of the removed Kinopoisk
  feature is wiped by `LegacyPurge`. Both are temporary and must be deleted when every device is migrated.
- The old package is uninstalled only after the user confirms (data loss), per device.

## Feed and content

- Strict year match for ratings in the early rating lookups (series too); descriptions came from the app's TvContract.
- Kinopoisk removed entirely (v0.3.0): card info comes only from the source app. Rows are configured only through a
  hidden "configure" tile left of the first card (fades in when focus moves left), per-row checklist of source apps;
  settings rows for sources were removed; the tile is icon-only with a round glass highlight.

## System cleanup (2026-10-23/24)

- Goal: reversible debloat from the launcher itself. Actions: uninstall for user 0 keeping data, disable, reset updates;
  never full uninstall of system packages.
- Protection levels (FULL / DISABLE_ONLY for HOME handlers); vendor packages are deny-listed unless the matched
  profile lists them. Optional (`?`) entries never run automatically.
- UX redesign (v0.3.0): one list of installed apps sorted by storage size with a toggle per app, a trash block with
  rollback for removed system apps, one-sentence descriptions for known packages, "automatic" run that must be
  confirmed twice and executes exactly the steps that were shown. Auto run stops when the screen closes.
- A cleanup list is only for devices that need it; TV box 1 gets none (user said not needed).

## Interface settings

- Settings in tabs: Общие / Интерфейс / Заставка / Оптимизация (v0.3.3).
- "Off" is the first option of delay settings (dimming, screensaver).
- Disable animations: selection changes (cursor, grow, dim) instant and row scrolling jumps; the vertical page shift and
  the backdrop crossfade always animate (backdrop is always smooth since v0.3.2). Favorites scale up on focus.
- UI scale 70-130 % in steps of 5 scales every dp/sp through the density; a separate cards-only scale was requested
  (ROADMAP).
- The system screensaver is pointed at our dream on every start (after the migration a dangling component made Android
  use its own).
