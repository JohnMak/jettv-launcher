# Release and device operations

- Release: tag `vX.Y.Z`, CI builds and publishes the signed APK and `.sha256` (`docs/CONTINUE.md` section 5).
  Signing secrets and the keystore: `docs/CONTINUE.md` section 4. Loss of the keystore breaks updates for all devices.
- Rollout: devices self-update (boot, 6 h, idle, screensaver, manual); rules in `docs/CONTINUE.md` section 6.
- Device setup, debloat and undo: `tools/setup-device.sh`, `tools/debloat.sh`, `tools/undebloat.sh`,
  `docs/new-device-prompt.md`, `docs/CONTINUE.md` section 7.
- Device specifics (models, adb on TCP 5555, projector brightness service): `docs/devices.md`.
- Rollback of a bad release: delete the GitHub release (the updater only offers the latest published, non-prerelease
  one), publish a fixed higher version; downgrades are not installed by the updater (version must be newer).
- Monitoring: none; check `adb logcat -d` and Settings > About on a device.
