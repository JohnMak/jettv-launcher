# Development workflow

- Setup, build, tests, baseline tag: `docs/CONTINUE.md` (sections 1-3).
- Rules and principles (one logic path, typed shell commands, TDD for bugs, offline tests): `CLAUDE.md`.
- Code map: `docs/architecture.md`; behaviour notes for the home screen: `launcher/NOTES.md`.

Loop:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd launcher && ./gradlew testDebugUnitTest          # fast, no device
./gradlew assembleDebug                              # debug APK
```

Bug fix order: failing test (unit, integration, e2e scenario) -> localize -> fix -> green. Real-device checks use
`tools/build-and-install.sh install` or a pushed debug APK; make sure only one build of the service runs on the device.
Parser tests use captured device output in `launcher/app/src/test/resources/` (add a fixture from each new device
model). Commit only on green tests; update `docs/CHANGELOG.md` for user-visible changes.
