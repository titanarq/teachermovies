BUILDING AND TESTING (Android / Gradle)
- Run tests only through `bash __TEST_COMMAND__` (it wraps `./gradlew test`); pass a module task
  to narrow it, e.g. `bash __TEST_COMMAND__ :torrent:test`. Read `.cache/gradle-test-last.log`
  instead of re-running. Never run `connectedAndroidTest` or anything needing a device/emulator.
- `ANDROID_HOME` is exported to your worktree. Do not install SDK packages, change Gradle
  wrapper versions or add dependencies outside `gradle/libs.versions.toml` unless the issue asks.
- Keep jlibtorrent types inside `:torrent` and libVLC types inside `:player`; everything else
  goes through `TorrentEngine` / `Player` interfaces (docs/adr/0001-core-technology-choices.md).
- Before any Gradle run, check no other Gradle build is running for your worktree:
  `ps -eo pid,cmd | grep [G]radleWrapperMain`.
