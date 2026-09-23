#!/usr/bin/env bash
# The project's single test command (config/agents.yaml project.test_command).
# Compact Gradle runner: prints only a summary, or the failing tasks/tests on failure.
# The full output always lands in .cache/gradle-test-last.log -- grep that instead of re-running.
# Usage: scripts/test.sh [extra gradle args]   e.g. scripts/test.sh :torrent:test --tests '*Engine*'
# With no args it runs `./gradlew test` (JVM unit tests of every module). Instrumented tests
# (connectedAndroidTest) need a device/emulator and are never run here.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p .cache
log=.cache/gradle-test-last.log
max_lines=${TEST_MAX_LINES:-120}

if [ ! -x ./gradlew ]; then
  echo "scripts/test.sh: no Gradle project yet (./gradlew missing) -- nothing to test."
  echo "The first infra task creates the Gradle skeleton and wrapper; until then this is a no-op."
  exit 0
fi

if [ "$#" -eq 0 ]; then
  set -- test
fi

./gradlew --console=plain --no-daemon "$@" >"$log" 2>&1
status=$?

if [ $status -eq 0 ]; then
  echo "OK: ./gradlew $* ($(grep -cE '^> Task .*test' "$log" || true) test tasks) -- full log: $log"
else
  echo "FAILED (exit $status): ./gradlew $* -- full log: $log"
  grep -nE 'FAILED|error:|e: |What went wrong|Exception' "$log" | head -n "$max_lines"
fi
exit $status
