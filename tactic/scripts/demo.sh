#!/usr/bin/env bash
#
# demo.sh -- build everything, run the scripted fault demo (about two minutes), and print the
# verdict table last. Runs on macOS, Linux and Git Bash on Windows.
#
# Owners: Leon Letournel (#33) writes this script; Godson Umoren (#34) adds the demo driver and
# the verdict table it calls.
#
# Rules this script must keep:
#   - Data and log folders live under tactic/ (demo-data/, demo-logs/) and are passed to the
#     JVM as paths relative to tactic/, never as Git Bash paths such as /c/... .
#   - Crash phases use "kill -9 <pid>" on the process ID recorded at start; a plain kill is a
#     clean shutdown, not a crash.
#   - A restarted process uses the same ports and data folder as before.
#
# Tools: bash 3.2 or later, java (21 or later) on the PATH, mvn, kill, sleep. Control commands
# go over bash's own /dev/tcp, so no nc is needed.
set -euo pipefail

TACTIC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
cd "${TACTIC_DIR}"

DATA_DIR="demo-data"   # relative to tactic/
LOG_DIR="demo-logs"    # relative to tactic/

# Start order: each process's downstream peer is listening before it starts.
PROCESSES="master-inventory cfe fault-link store-agent"

phase() {
  echo "== $* =="
}

control_port() {
  case "$1" in
    store-agent) echo 7102 ;;
    fault-link) echo 7202 ;;
    cfe) echo 7302 ;;
    master-inventory) echo 7402 ;;
  esac
}

# Command-line options for one process: only its data folder; ports stay at their defaults.
process_args() {
  case "$1" in
    fault-link) ;;
    *) echo "--data-dir ${DATA_DIR}/$1" ;;
  esac
}

pid_of() {
  cat "${LOG_DIR}/$1.pid"
}

ready_count() {
  cat "${LOG_DIR}/$1.log" 2>/dev/null | grep -c "^READY $1" || true
}

# Starts one process in the background, records its PID and waits for its READY line. A restart
# appends to the same log, so it waits for one READY line more than the log already holds.
start() {
  local name="$1" log="${LOG_DIR}/$1.log" waited=0 before
  before="$(ready_count "${name}")"
  # shellcheck disable=SC2046 # process_args is a word list on purpose
  java -jar "${name}/target/${name}.jar" $(process_args "${name}") >>"${log}" 2>&1 &
  echo $! >"${LOG_DIR}/${name}.pid"
  disown $! # no "Killed: 9" job notice from bash when the crash phases kill it
  until [ "$(ready_count "${name}")" -gt "${before}" ]; do
    if ! kill -0 "$(pid_of "${name}")" 2>/dev/null; then
      echo "demo: ${name} exited during startup; see ${log}" >&2
      tail -n 20 "${log}" >&2
      exit 1
    fi
    if [ "${waited}" -ge 300 ]; then
      echo "demo: ${name} printed no READY line in 30 s; see ${log}" >&2
      exit 1
    fi
    sleep 0.1
    waited=$((waited + 1))
  done
  echo "started ${name} (pid $(pid_of "${name}")): $(grep "^READY ${name}" "${log}" | tail -n 1)"
}

# Waits until a process ID is gone, so its ports and files are free.
await_exit() {
  local pid="$1" waited=0
  while kill -0 "${pid}" 2>/dev/null && [ "${waited}" -lt 100 ]; do
    sleep 0.1
    waited=$((waited + 1))
  done
}

# A real crash: kill -9, then a restart on the same ports and data folder.
crash_and_restart() {
  local name="$1" pid
  pid="$(pid_of "${name}")"
  echo "kill -9 ${name} (pid ${pid})"
  kill -9 "${pid}"
  await_exit "${pid}"
  sleep "${2:-3}"
  start "${name}"
}

# Sends one control line to a process and prints the reply line.
control() {
  local port reply
  port="$(control_port "$1")"
  exec 3<>"/dev/tcp/127.0.0.1/${port}"
  printf '%s\n' "$2" >&3
  IFS= read -r reply <&3 || true
  exec 3<&- 3>&-
  echo "${reply}"
}

# Sends a fault-link command and fails the run unless the link answers OK.
link() {
  local reply
  reply="$(control fault-link "$1")"
  echo "fault-link $1 -> ${reply}"
  [ "${reply}" = "OK" ] || { echo "demo: the fault link refused $1" >&2; exit 1; }
}

# One value from a STATUS reply, for example: status_value store-agent pending
status_value() {
  control "$1" STATUS | tr '|' '\n' | sed -n "s/^$2=//p"
}

# Stops every process that is still running: a plain kill, so each closes its database cleanly.
stop_all() {
  local name pid
  for name in ${PROCESSES}; do
    [ -f "${LOG_DIR}/${name}.pid" ] || continue
    pid="$(pid_of "${name}")"
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
      await_exit "${pid}"
    fi
    rm -f "${LOG_DIR}/${name}.pid"
  done
}
trap stop_all EXIT
trap 'exit 130' INT TERM

phase "build and start the four processes"
mvn -B -q -DskipTests package
rm -rf "${DATA_DIR}" "${LOG_DIR}"
mkdir -p "${DATA_DIR}" "${LOG_DIR}"
for name in ${PROCESSES}; do
  start "${name}"
done

# TODO(Godson, #34): start the demo driver here, in the background, sending seeded sales (about
# 50 per second) to the store's sales port through every phase below, and stop it before "drain".

phase "normal running"
sleep 15

phase "lossy link: drop 20%, duplicate 10%"
link "SET|drop|20"
link "SET|duplicate|10"
sleep 20
link "SET|drop|0"
link "SET|duplicate|0"

phase "link cut for 30 seconds while sales continue"
link "CUT"
sleep 30
link "RESTORE"

phase "store killed and restarted"
crash_and_restart store-agent

phase "CFE killed and restarted"
crash_and_restart cfe

phase "master killed and restarted"
crash_and_restart master-inventory

phase "drain: wait until the store has nothing pending and the master has applied everything"
waited=0
until [ "$(status_value store-agent pending)" = "0" ]; do
  if [ "${waited}" -ge 60 ]; then
    echo "demo: the store still has events pending after 60 s" >&2
    break
  fi
  sleep 1
  waited=$((waited + 1))
done
# TODO(Godson, #34): also wait until the master has applied everything the store created.

phase "verdict"
# TODO(Godson, #34): read STATUS from store, CFE and master and print the verdict table; exit 1
# unless the run passed. Until then the raw STATUS lines are printed.
for name in store-agent cfe master-inventory; do
  echo "${name}: $(control "${name}" STATUS)"
done
