# Module: infra

**Gradle path:** root project (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper).

## Responsibility
- Gradle skeleton, version catalog, shared convention config (JDK 17 toolchain, lint/format). Versions: ADR-0004 (Gradle 8.7, AGP 8.6.1, Kotlin 2.1.21, compileSdk/targetSdk 35, minSdk 26).
- CI (`.github/workflows/ci.yml`: Android build, `ktlintCheck`, unit tests on ubuntu, JDK 17).
- Formatting: the ktlint Gradle plugin is applied to every project from the root `build.gradle.kts`;
  rules live in the root `.editorconfig` (`ktlint_official`, max line 120, no wildcard imports,
  `@Composable` functions exempt from function naming). `./gradlew ktlintCheck` must pass --
  `scripts/test.sh` runs the matching one alongside the tests; `./gradlew ktlintFormat` fixes most
  violations.
- `scripts/test.sh` (the single test command) and agent OS host configuration (`config/agents.yaml`, `config/agent_prompts/`, `scripts/` shims).

## Boundaries
- `agent_os/` is a subtree of `titanarq/agent-os` and is never edited here.
- Native ABIs for jlibtorrent/libVLC (arm64-v8a, armeabi-v7a, x86_64) are configured here.

## Tests
`scripts/test.sh` green locally and in CI. It runs the matching `ktlintCheck` task(s) in the same
Gradle invocation as the tests it was given (no args -> root `ktlintCheck`, `:mod:test` ->
`:mod:ktlintCheck`), so ktlint is no longer a second, separate command a worker has to remember.
