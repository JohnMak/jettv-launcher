# Architecture

Single Android module `launcher/app`, package `com.johnmak.atvlauncher`, Java, no SDK_INT forks. Pattern everywhere:
a pure, unit-tested class decides, a thin Android class does the I/O. Fallible results are `FetchResult<T>` (value or
message) or typed errors. Background work goes through `GuardedWorker` (a closed executor drops the task, a failing task
reports instead of killing the process). Behaviour details of the home screen: `launcher/NOTES.md`.

## Home screen

- `MainActivity`: the HOME activity. Virtual focus (key events dispatched by the activity), one glass highlight.
  Order: top bar, favorites row, "continue watching" row, "recommendations" row.
- Focus: `FocusModel`, `SelectionDelta` (only changed views update), `CenterKey`, `RowScroller`/`RowScrollModel`
  (continuous scroll per row), `Approach` (exponential approach animation), `GlassView` (highlight), `Tiles`
  (shared tile rendering/geometry), `FocusDim`, `DimImageView`.
- Feeds: `FeedLoader` reads the TvProvider preview and watch-next programs (needs `READ_TV_LISTINGS`), `FeedBuilder`
  filters and builds rows (mixed row: every publishing app gets at least one card, rest split by launch frequency),
  `FeedAdapter` renders, `FeedInfoField`/`FeedInfoLine`/`FeedDescription` show the info under a row, only from the
  source app (title, season/episode, rating, year, description). A hidden "configure" tile left of the first card
  opens a per-row source checklist (`ChecklistWindow`, `ExcludedApps`, `FeedSource`).
- Favorites: `FavoritesStore`/`FavoritesPrefs` (persistence), `FavoritesRowState`, `FavoritesRowLayout` (tile sizing and
  scroll), `FavoritesAdapter`. The row continues after the apps button with frequently used apps (`UsageRanking`,
  `LaunchCounter`, `RecentApps`). `AllAppsActivity` is the full grid, ordered by launch frequency, with `AppGrouping`.
- Top bar: `TopBarItem`, clock/date, network (`NetworkStatus`, `LinkState`), VPN (`VpnApps`, `VpnAppsLoader`),
  weather (`WeatherController`, `WeatherModel`, `WeatherRefreshPolicy`, `MetProvider` = MET Norway, `GeocodingClient`,
  `GeoIpClient`, `WeatherPrefs`), brightness sun, settings sliders.
- Backdrop: `BackdropController` (debounced by `BackdropPlanner`, blurred by `BackdropBlur` on a worker, vignette
  `BackdropVignette`, drawn by `BackdropImageView`, crossfaded via `PosterBackdrop`), keys in `BackdropKeys`, app
  images from `AppBackdropImage`.
- Motion policy: `Motion` (from the animations setting): selection changes instant or animated; row scroll jumps or
  glides; vertical page shift and backdrop crossfade always animate.
- UI scale: `UiScale` wraps the activity context with a scaled `densityDpi`; `PreviewScale` is the card scale.

## Brightness (XGIMI projector only)

- `BrightnessScale`: positions 0..15 = number of filled dashes (15 dashes drawn as 3+9+3). Positions 0-2 dim the picture
  (brightness = contrast = 27/35/42) at lamp level 1, positions 3-12 walk lamp levels 1..10 at the neutral picture (50),
  positions 13-15 boost the picture (57/65/72) at lamp level 10. `Position` is validated; `fromDevice` maps device
  readings back to the nearest position.
- `BrightnessController`: reads lamp/brightness/contrast, applies positions optimistically with coalescing (only the
  latest target is written while a write is in flight), writes only the parts that differ, never changes lamp mode
  or picture mode. Shell I/O on one worker thread.
- `BrightnessPopup`, `BrightnessKeyModel` (DOWN enters adjust, second DOWN closes and moves focus to tiles),
  `BrightnessState`, `BrightnessFeature` (present or ABSENT), `BrightnessSupport` (decides once at startup whether
  `gmpfUnit` exists; the sun is hidden otherwise), `LampLevel`, `GmpfParser` (`return_data : {N }`).
- Transport: loopback ADB to the device's own adbd, see next section.

## Shell and ADB

- `AdbSession`: the one ADB channel per process with exactly two typed operations: a validated shell command and the
  install of a `VerifiedApk`. `LoopbackAdbSession` implements it over dadb 2.0.0 to 127.0.0.1:5555 with an RSA key in
  the app's private files; one shared instance, calls serialized, a failure drops the connection.
- `ShellCommand`: closed set of verbs; factories `Gmpf` (lamp/picture, range-checked), `Pm` (grant, disable,
  remove-for-user, install-existing, reset updates, set HOME role; arguments typed as `PackageName`,
  `GrantablePermission`), `Stats` (read-only meminfo/ps). `ShellError`/`ShellErrorClassifier` classify failures.
- `PmOutputParser`: judges a package command by exit code AND the verb's success marker; stdout and stderr separated,
  WARNING lines ignored for empty-marker verbs. `MeminfoParser` and `ProcessTimesParser` parse `dumpsys meminfo -c` and
  `ps`; fixtures from the projector and TV box 2 are in `src/test/resources/stats/`.

## Updater

- `ReleaseFeed` parses the GitHub latest-release JSON into `Release` (published, not prerelease, tag `vX.Y.Z`, exactly
  the `.apk` and `.apk.sha256` assets on allowed hosts `UpdateHosts`); `SemVer` compares versions.
- `ApkDownloader` (manual redirects only to allowed hosts, capped body), `ApkChecks` + `ApkVerifier` (SHA-256, package,
  exact version, newer, same signing certificate) produce `VerifiedApk`.
- `UpdatePolicy`: pure scheduling (triggers PROCESS_START, TICK, BOOT; 6 h check interval; 10 min tick; boot window
  10 min with 30 s network retry; auto install after 10 min idle or during screensaver).
- `UpdateController` (one instance, state machine `UpdateState`: Idle, Checking, UpToDate, Downloading, Verifying, Ready,
  Installing, Error; `UpdateError`, `UpdateStatusText`, `UpdatePrefs`). Install goes through `AdbSession`. After 2 failed
  installs of one version it stops retrying and reuses an already verified APK.

## Settings

- `LauncherSettingsActivity` with four tabs (Общие, Интерфейс, Заставка, Оптимизация; `SettingsTabsNav`), About with live
  update status, rights warning.
- `LauncherSettings` (immutable, cycling helpers) in `LauncherSettingsStore` (prefs file `launcher_settings`).
- One-time migration, to be deleted: `SettingsImport` (imports `import/settings.json` from the app's external files dir,
  strict whitelist, all-or-nothing) and `LegacyPurge` (removes leftovers of the removed Kinopoisk feature).

## System cleanup

- `SystemCleanupActivity`: one list of installed apps sorted by storage with a toggle, a trash block of removed system
  apps (rollback), an "automatic" run confirmed twice (`AutoRunGuard` keeps a snapshot of the step list; `CleanupConfirm`).
- Model: `CleanupList`, `CleanupSections`, `CleanupRowFocus`, `CleanupLog` (+ `PrefsCleanupLogStorage`: what was done to
  which package, for revert), `StatsFormat`.
- Planning: `CleanupProfile` parses `tools/debloat/*.txt` (bundled as an asset; `@match model=/product=` selects a
  profile; actions disable/remove/reset, `?` optional, `requires=home`), `CleanupPlanner` turns profile + inventory
  into the ordered steps, `CleanupInventory` reads the device packages (worker thread), `PackageInspector`.
- Safety: `ProtectedPackages` levels NONE, DISABLE_ONLY (HOME handlers), FULL (fixed core set, provider/network/platform/
  vendor prefix rules, dynamic IME/screensaver/accessibility/WebView/persistent apps); vendor packages listed in the
  matched profile are the curated exception.
- `AppDescriptions`: one-sentence Russian descriptions from `assets/app_descriptions.tsv`.

## Rights

`RequiredRights` (FINE_LOCATION, TV_LISTINGS, SECURE_SETTINGS, USAGE_STATS, HOME; the probe is injected),
`RightsFixer` (grants through `AdbSession`, each right maps to one `Pm` command, re-probes), `RightsPrompt` (opens
Settings once on the user's first key press; never unattended and never after a silent update install).

## Screensaver

`StarfieldDream` (system dream service) and the in-launcher `Screensaver` show the same content: `StarfieldView` +
`StarfieldModel`/`StarSprite` (stars, clock) or a NASA video (`VideoScreensaverView`, `PlaylistVideoView`);
`IdleController`/`IdleTimeline` drive dimming and screensaver timing; `ScreensaverKind`/`State`/`Overlay`.
`SystemScreensaver.sync` runs on every launcher start and points `screensaver_components` at our dream, so a stale
component never makes Android fall back to its own.

## Misc

`KeyInterceptService` (optional accessibility key remap), `HttpText` (HTTP client with one project User-Agent,
no personal data), `TrimPolicy`, `ProcessNames`, `LaunchStats`.
