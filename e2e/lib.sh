#!/usr/bin/env bash
# Sourced by every e2e script. The emulator belongs to andashi/provisioning;
# this repository only borrows an instance, through that repository's lock,
# and proves the identity every adb call runs under (AGENTS.md "Tests").
#
#   PROV=~/Development/andashi/provisioning   where the emulator scripts live
#   SERIAL=emulator-5562                      the instance (console port = serial)
#   OVERLAY_DIR=$PROV/emulator/instances/test-fold-gpu
#   LOCK_OWNER=<unique to this run>
set -uo pipefail
: "${PROV:=$HOME/Development/andashi/provisioning}"
: "${SERIAL:?set SERIAL (emulator-<port>)}" "${OVERLAY_DIR:?set OVERLAY_DIR}" "${LOCK_OWNER:?set LOCK_OWNER}"
export SERIAL OVERLAY_DIR LOCK_OWNER ADB_SERIAL="$SERIAL"
# Pinned for every adb call in this process: a second emulator must never
# receive a command meant for this one.
export ANDROID_SERIAL="$SERIAL"

pass=0; fail=0
check() { if eval "$2"; then printf '  ok    %s\n' "$1"; pass=$((pass+1)); else printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); fi; }
summary() { printf '\n%d ok, %d failed\n' "$pass" "$fail"; [ "$fail" = 0 ]; }

ash() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }

# The claim is about the shell (uid 2000). Taken from provisioning's
# tests/e2e/identity.sh; the emulator's run.sh roots adbd on every start.
as_shell() {
  local id _
  id="$(ash id -u 2>/dev/null)"
  if [ "$id" != 2000 ]; then
    adb -s "$SERIAL" unroot >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do sleep 1; id="$(ash id -u 2>/dev/null)"; [ "$id" = 2000 ] && break; done
  fi
  [ "$id" = 2000 ] || { echo "e2e: adbd does not run as shell (uid ${id:-?}) - refusing to test" >&2; exit 2; }
  printf '  ..    adbd identity: uid %s (shell)\n' "$id"
}

# Lock, start (from a snapshot), release on exit. The host's load is read
# first: an emulator on a busy host times out in ways that look like bugs.
emu_acquire() {   # $1 = snapshot
  local load
  load="$(cut -d' ' -f1 /proc/loadavg)"
  printf '  ..    host load %s on %s cores\n' "$load" "$(nproc)"
  "$PROV/emulator/device-lock.sh" acquire "$LOCK_OWNER" "$SERIAL" || exit 2
  trap 'emu_release' EXIT
  SNAPSHOT="$1" GPU="${GPU:-host}" FOLDABLE=1 "$PROV/emulator/run.sh" start >/dev/null 2>&1 \
    || { echo "e2e: emulator did not start" >&2; exit 2; }
  adb -s "$SERIAL" wait-for-device
  # Online is not booted: a snapshot restore answers adb before the package
  # manager does. Wait for the boot flag and for pm to answer.
  local _
  for _ in $(seq 1 90); do
    [ "$(ash getprop sys.boot_completed 2>/dev/null)" = 1 ] && ash pm path android >/dev/null 2>&1 && break
    sleep 2
  done
  [ "$(ash getprop sys.boot_completed 2>/dev/null)" = 1 ] || { echo "e2e: emulator did not finish booting" >&2; exit 2; }
  as_shell
}
emu_release() {
  "$PROV/emulator/run.sh" stop >/dev/null 2>&1 || true
  "$PROV/emulator/device-lock.sh" release "$LOCK_OWNER" "$SERIAL" >/dev/null 2>&1 || true
}

# Wait, against a deadline, for a shell command's output to match a pattern,
# then print that output. "No result found." is output too, so a pattern is
# required - matching on non-empty would call an empty provider a result.
wait_for() {   # $1 = seconds, $2 = grep -E pattern, $3... = command (run through ash)
  local deadline=$(( $(date +%s) + $1 )) pattern="$2"; shift 2
  while [ "$(date +%s)" -lt "$deadline" ]; do
    out="$(ash "$@" 2>/dev/null)"
    grep -qE "$pattern" <<<"$out" && { printf '%s' "$out"; return 0; }
    sleep 2
  done
  return 1
}

# Evidence header for the pull request: instance, snapshot, build, identity.
evidence() {   # $1 = snapshot
  printf '  ..    instance %s, snapshot %s, build %s (API %s), %s\n' "$SERIAL" "$1" \
    "$(ash getprop ro.build.version.release)" "$(ash getprop ro.build.version.sdk)" "$(date -u +%Y-%m-%dT%H:%MZ)"
}
