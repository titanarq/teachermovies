#!/usr/bin/env bash
# Cleanup wrapper for #305: stop the shared persistent Gradle daemon when no build is using it.
#
# Why a wrapper and not KillMode=control-group on teachermovies-guard.service: the guard tick is
# Type=oneshot, so a cgroup-wide kill would take the worker run that tick dispatched with it --
# which is why the drop-in that agent_os renders sets KillMode=process on purpose. A Gradle daemon
# escapes that run anyway: it starts its own session, so neither the run's `kill -TERM -<pgid>` cut
# nor KillMode=process reaches it, and it then lives on inside the guard's cgroup, logged as a
# left-over process at every tick. This reaps that class from outside, by name. The Kotlin compile
# daemon needs no branch of its own -- measured 2026-09-28, it exits with the Gradle daemon that
# started it (both gone 12 s after `--stop`).
#
# Safety: ~/.gradle is a symlink to /mnt/data/titan-cache/gradle, so every worktree on this host
# shares one daemon registry and `--stop` reaches a daemon another checkout may be building with.
# Nothing is stopped unless the registry reports no BUSY daemon and no Gradle client JVM is alive;
# the client check runs again immediately before the stop, and every refusal says why.
#
# Usage: scripts/stop_idle_gradle_daemon.sh [--dry-run]
set -uo pipefail
cd "$(dirname "$0")/.."

dry_run=0
if [ "${1-}" = "--dry-run" ]; then
  dry_run=1
fi

# A build in flight is a client JVM holding the wrapper jar or a distribution launcher jar.
# Matched on the executable, never with `pgrep -f GradleWrapperMain`: a waiter loop another agent
# left behind carries that string in its own command line for hours after the build it watched.
gradle_clients() {
  ps -eo pid=,comm=,args= |
    awk '$2 == "java" && (/gradle-wrapper\.jar/ || /gradle-launcher-/) && !/GradleDaemon/ {print $1}' |
    tr '\n' ' '
}

# "<pid> <IDLE|BUSY>" per daemon registered for this Gradle version ("--status" shows no others).
registered_daemons() {
  ./gradlew --status 2>/dev/null |
    awk '$1 ~ /^[0-9]+$/ && ($2 == "IDLE" || $2 == "BUSY") {print $1, $2}'
}

if [ ! -x ./gradlew ]; then
  echo "stop_idle_gradle_daemon: no Gradle wrapper in this checkout -- nothing to do."
  exit 0
fi

clients=$(gradle_clients)
if [ -n "$clients" ]; then
  echo "REFUSED: a Gradle build is in flight (client PID(s): $clients) -- nothing stopped."
  exit 0
fi

daemons=$(registered_daemons)
if [ -z "$daemons" ]; then
  echo "OK: no running Gradle daemon registered for this Gradle version -- nothing to stop."
  exit 0
fi

busy=$(awk '$2 == "BUSY" {printf "%s ", $1}' <<<"$daemons")
if [ -n "$busy" ]; then
  echo "REFUSED: daemon(s) $busy are BUSY, another worktree is building -- nothing stopped."
  exit 0
fi

idle=$(awk '$2 == "IDLE" {printf "%s ", $1}' <<<"$daemons")
if [ "$dry_run" -eq 1 ]; then
  echo "DRY-RUN: would stop IDLE Gradle daemon(s): $idle"
  exit 0
fi

clients=$(gradle_clients)
if [ -n "$clients" ]; then
  echo "REFUSED: a Gradle build started while this script was checking (client PID(s): $clients) -- nothing stopped."
  exit 0
fi

echo "Stopping IDLE Gradle daemon(s): $idle"
./gradlew --stop
status=$?
echo "Registered after the stop: $(registered_daemons | tr '\n' ' ')"
exit $status
