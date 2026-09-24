#!/usr/bin/env bash
# The project's single test command (config/agents.yaml project.test_command).
# Compact Gradle runner: prints only a summary, or the failing tasks/tests on failure.
# The full output always lands in .cache/gradle-test-last.log -- grep that instead of re-running.
# Usage: scripts/test.sh [extra gradle args]   e.g. scripts/test.sh :torrent:test --tests '*Engine*'
# With no args, or with Gradle options only (everything starts with "-", e.g.
# scripts/test.sh --max-workers=2), it runs `./gradlew test <options>` -- the options augment the
# default `test` task instead of replacing it. Once any argument is a task name (does not start
# with "-"), behaviour is unchanged: all arguments are passed to Gradle exactly as given, e.g.
# scripts/test.sh :torrent:test --tests '*Engine*'. Instrumented tests (connectedAndroidTest) need
# a device/emulator and are never run here.
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
else
  # Decide whether a task name is present among the extra args, so option-only invocations (e.g.
  # --max-workers=2) augment the default `test` task instead of replacing it. An option that takes
  # a separate value (--tests PATTERN) must not have its value mistaken for a task name.
  has_task=0
  take_value=0
  for arg in "$@"; do
    if [ "$take_value" -eq 1 ]; then
      take_value=0
      continue
    fi
    case "$arg" in
      --tests)
        take_value=1
        ;;
      -*) ;;
      *)
        has_task=1
        ;;
    esac
  done
  if [ "$has_task" -eq 0 ]; then
    set -- test "$@"
  fi
fi

./gradlew --console=plain --no-daemon "$@" >"$log" 2>&1
status=$?

if [ $status -eq 0 ]; then
  test_task_count=$(grep -cE '^> Task .*test' "$log" || true)
  echo "OK: ./gradlew $* ($test_task_count test tasks) -- full log: $log"
  if [ "$test_task_count" -eq 0 ]; then
    echo "WARNING: 0 test tasks ran -- check the task/args passed to scripts/test.sh"
  fi
else
  echo "FAILED (exit $status): ./gradlew $* -- full log: $log"
  grep -nE 'FAILED|error:|e: |What went wrong|Exception' "$log" | head -n "$max_lines"
fi
exit $status
