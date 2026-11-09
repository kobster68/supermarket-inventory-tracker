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
set -euo pipefail

TACTIC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
cd "${TACTIC_DIR}"

DATA_DIR="demo-data"   # relative to tactic/
LOG_DIR="demo-logs"    # relative to tactic/

phase() {
  echo "== $* =="
}

# TODO(Leon, #33): build with "mvn -B -q -DskipTests package", clear ${DATA_DIR} and ${LOG_DIR},
# then start master-inventory, cfe, fault-link and store-agent in that order, each in the
# background with its log in ${LOG_DIR}, waiting for its READY line and recording its PID.
phase "build and start the four processes"

# TODO(Godson, #34): the demo driver sends seeded sales (about 50 per second) to the store's
# sales port through every phase below.
phase "normal running"
phase "lossy link: drop 20%, duplicate 10%"
phase "link cut for 30 seconds while sales continue"
phase "store killed and restarted"
phase "CFE killed and restarted"
phase "master killed and restarted"
phase "drain: wait until the store has nothing pending and the master has applied everything"

# TODO(Godson, #34): read STATUS from store, CFE and master and print the verdict table; exit 1
# unless the run passed.
phase "verdict"

echo "demo.sh is a skeleton: the phases above are not implemented yet." >&2
exit 1
