# Changelog

Dates are tag dates. SemVer; versionCode = X*1_000_000 + Y*1_000 + Z. `v0.0.1` is a local baseline, not a release.

## v0.6.0 (2026-10-28)
- ART screensaver reworked onto Wallhaven: a keyless 4K wallpaper source replaces the old art.jmwork.ru feed.
- Native Ken-Burns slow-zoom for the ART slideshow.
- Category selection for the Wallhaven source.
- Voluntary wallhaven.cc attribution overlay (ART variant only).
- One-time cleanup of the orphaned legacy art-cache directory.
- Comprehensive offline test coverage for the Wallhaven rework.

## v0.5.0 (2026-10-26)
- New ART screensaver variant: a curated public-domain painting slideshow with a build-time Wikimedia manifest
  generator and an owner-vetted set.
- ART source/cache/manifest layer (`ArtSource`, `ArtCache`, `ArtManifest`, `ArtHosts`) with preset resolution and
  availability checks.
- `ScreensaverType.ART` with an `artPreset` setting; settings reordered with a category row and the logo removed.
- ART playback timing/phase engine and `ArtScreensaverView` with fade-through-black transitions.

## v0.4.2 (2026-10-24)
- Brighter focused app-tile glow: the icon-colour halo is rescaled into a clearly bright band instead of a dark one.

## v0.3.3 (2026-10-24)
- Updates install immediately after the device boots (boot window, retries while the network is not up).
- Settings are split into tabs: Общие / Интерфейс / Заставка / Оптимизация.
- With animations off, rows scroll instantly.
- Parser tests with projector (Android 11) fixtures.

## v0.3.2 (2026-10-24)
- New setting: selection animation. Backdrop always crossfades smoothly. Favorites scale up on focus.

## v0.3.1 (2026-10-24)
- The system screensaver is re-pointed at the launcher's dream on every start (fixes a dangling component after the
  package migration).

## v0.3.0 (2026-10-24)
- Kinopoisk removed: card info comes only from the source app.
- Configure tile is icon only with a round glass highlight.
- "Off" is the first option of delay settings; new "disable animations" setting.
- System cleanup redesigned: one list by size with toggles, trash and rollback, app descriptions, read-only process
  stats; pre-release fixes (guarded workers, auto-run snapshot, tolerant parsers).

## v0.2.0 (2026-10-23)
- Silent self-update: 6 h checks, background tick, install while idle or on the screensaver; stop after 2 failed installs.
- System cleanup screen with profiles, planner, log, protection rules, typed `pm` commands.
- Rights warning in Settings with grant and set-home actions.
- Feed rows: hidden configure tile with a per-row source checklist.

## v0.1.2 (2026-10-23)
- CI: pinned node24 action versions.

## v0.1.1 (2026-10-23)
- One-time settings import for migration from the old package.

## v0.1.0 (2026-10-23)
- First public release: package `com.johnmak.atvlauncher`, self-updater (verified download, silent install via loopback
  ADB), About section with update status, tag-triggered signed CI release, device setup and reversible debloat tooling,
  brightness sun for XGIMI projectors (3+9+3 scale).
