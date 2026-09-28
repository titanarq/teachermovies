#!/usr/bin/env bash
# The project's single test command (config/agents.yaml project.test_command).
# Compact Gradle runner: prints only a summary, or the failing tasks/tests on failure.
# The full output always lands in .cache/gradle-test-last.log -- grep that instead of re-running.
# Usage: scripts/test.sh [extra gradle args]   e.g. scripts/test.sh :torrent:test --tests '*Engine*'
# With no args, or with Gradle options only (everything starts with "-", e.g.
# scripts/test.sh --max-workers=2), it runs `./gradlew test <options>` -- the options augment the
# default `test` task instead of replacing it. Once any argument is a task name (does not start
# with "-"), behaviour is unchanged: all arguments are passed to Gradle exactly as given, e.g.
# scripts/test.sh :torrent:test --tests '*Engine*'. Whatever test scope ends up running, the
# matching ktlintCheck task(s) ride along in the same invocation (root `test` -> root `ktlintCheck`,
# `:torrent:test` -> `:torrent:ktlintCheck`) unless the caller named a ktlint task itself, so CI is
# never the first thing to see a style violation. Instrumented tests (connectedAndroidTest) need a
# device/emulator and are never run here.
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

# Decide whether a task name is present among the extra args, so option-only invocations (e.g.
# --max-workers=2) -- and no args at all -- augment the default `test` task instead of replacing it.
# An option that takes a separate value (--tests PATTERN) must not have its value mistaken for a
# task name.
has_task=0
ktlint_named=0
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
      case "$arg" in
        *ktlint*) ktlint_named=1 ;;
      esac
      ;;
  esac
done
if [ "$has_task" -eq 0 ]; then
  set -- test "$@"
fi

# ktlint rides along in this same invocation: CI runs ktlintCheck on every PR while workers and the
# validator are only asked to run this script, so a style violation caught here costs seconds and
# one caught only by CI costs a round trip (PRs #314 and #316). One ktlintCheck per tested scope --
# `test` -> `ktlintCheck`, `:mod:test` -> `:mod:ktlintCheck` -- and nothing added when the caller
# named a ktlint task itself.
if [ "$ktlint_named" -eq 0 ]; then
  ktlint_tasks=()
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
        case "${arg##*:}" in
          test*)
            scope="${arg%:*}"
            [ "$scope" = "$arg" ] && scope="" # no ":" in it -> a root project task
            task="${scope:+$scope:}ktlintCheck"
            case " ${ktlint_tasks[*]-} " in
              *" $task "*) ;;
              *) ktlint_tasks+=("$task") ;;
            esac
            ;;
        esac
        ;;
    esac
  done
  if [ "${#ktlint_tasks[@]}" -gt 0 ]; then
    set -- "$@" "${ktlint_tasks[@]}"
  fi
fi

./gradlew --console=plain --no-daemon "$@" >"$log" 2>&1
status=$?

if [ $status -eq 0 ]; then
  test_task_count=$(grep -cE '^> Task .*test' "$log" || true)
  ktlint_task_count=$(grep -cE '^> Task .*ktlint' "$log" || true)
  echo "OK: ./gradlew $* ($test_task_count test tasks, $ktlint_task_count ktlint tasks) -- full log: $log"
  if [ "$test_task_count" -eq 0 ]; then
    echo "WARNING: 0 test tasks ran -- check the task/args passed to scripts/test.sh"
  fi
else
  echo "FAILED (exit $status): ./gradlew $* -- full log: $log"
  # The last alternative is a ktlint violation line from the console report
  # (`/abs/path/File.kt:3:1 message (rule)`), which carries no FAILED/error: marker of its own and
  # starts at column 0, unlike the compiler's `w: `/`e: ` diagnostics.
  grep -nE 'FAILED|error:|e: |What went wrong|Exception|^/.*\.kt:[0-9]+:[0-9]+' "$log" |
    head -n "$max_lines"
fi
exit $status
