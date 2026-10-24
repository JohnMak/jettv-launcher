# Roadmap and open work

The beads database is not published, so open items are recorded here (bead ids in brackets for reference). Priority
order within each list.

## Open work

1. Projector: remove the old package after the user confirms it works (data loss). Then close [androidtv-097].
2. Verify unattended updates on every device: after a boot (v0.3.3 immediate install), during the screensaver, and after
   10 min idle. Watch one real release end to end on the projector. [androidtv-agk, androidtv-697]
3. Delete `SettingsImport` and `LegacyPurge` (and their tests) once the projector is clean; release as a patch.
   [androidtv-g7m]
4. Bug: local-only `migrate-settings.sh push` reports a false failure; it should `am start` the app instead of waiting
   for a HOME key event. Low priority since migration is nearly finished. [androidtv-hrv]
5. Bug: `StarfieldView` renders about 45 ms/frame on TV box 2 (about 33 fps, RenderThread near 82 %). [androidtv-3cd]
6. Feature: separate "card scale" setting (70-130 %, step 5, cards and tiles only; text follows UI scale). Depends on the
   opened-dot work being done (it is). [androidtv-217]
7. Brightness: finish the edge-dash E2E: compare `get*` readbacks with the scale table at all positions, visual check by
   the user, picture values after reboot, picture mode stays 3. [androidtv-9xe]
8. Held LEFT/RIGHT performance: final verification on TV box 2 with UI scale check and review. [androidtv-yxp.4]
9. Ambient video backgrounds from YouTube links (later; needs an ToS-compliant embedded player and must work where
   YouTube is throttled). The `PlaylistVideoView` code is kept for it. [androidtv-b27]
10. Optional: a cleanup profile for TV box 1 (the user said it is not needed).

## Stale or umbrella beads that are effectively done (close them)

- androidtv-8k3 project documentation: done by this documentation set.
- androidtv-liq "Finalize: code review, docs, commit, push" and epic androidtv-gyi: brightness is shipped and reviewed;
  only 9xe (item 7) remains, close the epic after it.
- androidtv-j9b epic: public repo, updater, UI work shipped; remaining children are items 1-3 above.
- androidtv-1rz epic and androidtv-697 "Release + device verification (TV box 2)": released in v0.2.0 and verified on
  TV box 2; the update-verification part is item 2.
- androidtv-agk "E2E self-update": the manual 0.1.1 -> 0.1.2 update passed on TV box 2; the remaining value is item 2.
- androidtv-yxp.4: see item 8; close if the held-key behaviour is acceptable on TV box 2.

## Known caveats

- A firmware update of the projector can remove `gmpfUnit`; the sun then hides itself.
- Self-update and brightness need adbd on TCP 5555; a device that drops it on reboot needs it re-enabled.
- If the release keystore is lost, installed apps cannot be updated (see `docs/CONTINUE.md`).
