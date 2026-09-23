# Module: infra

**Gradle path:** root project (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper).

## Responsibility
- Gradle skeleton, version catalog, shared convention config (JDK 17 toolchain, lint/format).
- CI (`.github/workflows/ci.yml`: Android build + unit tests on ubuntu, JDK 17).
- `scripts/test.sh` (the single test command) and agent OS host configuration (`config/agents.yaml`, `config/agent_prompts/`, `scripts/` shims).

## Boundaries
- `agent_os/` is a subtree of `titanarq/agent-os` and is never edited here.
- Native ABIs for jlibtorrent/libVLC (arm64-v8a, armeabi-v7a, x86_64) are configured here.

## Tests
`scripts/test.sh` green locally and in CI.
