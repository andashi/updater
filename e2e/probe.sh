#!/usr/bin/env bash
# The probe's mechanics on the emulator: install into two zones, trigger a
# run from the shell, read the record back, schedule and cancel the soak.
#
#   SERIAL=emulator-5562 OVERLAY_DIR=$PROV/emulator/instances/test-fold-gpu \
#   LOCK_OWNER=<yours> e2e/probe.sh
#
# What this proves: the receiver and the provider are reachable from the
# shell and gated, the worker promotes itself to a dataSync foreground
# service, and a fetch result lands in the record with the fields the
# hardware soak will read. What it cannot prove is the design's question
# itself - whether the fetch gets network in a lockdown zone on a phone -
# because the emulator's background networking is unlike a phone's (design
# §16). A failed fetch here is recorded, not a failed test; a missing record
# or a missing promotion is.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=org.andashi.updater.probe
SNAPSHOT="${SNAPSHOT:-provisioned-0120}"
APK="$ROOT/probe/build/outputs/apk/debug/probe-debug.apk"
[ -f "$APK" ] || { echo "build the probe first: ./gradlew :probe:assembleDebug" >&2; exit 2; }

emu_acquire "$SNAPSHOT"
evidence "$SNAPSHOT"

# Home (0) and whichever secondary full user is running (the snapshot decides
# which; Cloud by design, Anon after a measurement left it on). Found by
# state and flags, never by name or number.
second="$(running_secondary_users | head -1)"
check "a running secondary zone exists" '[ -n "$second" ]'
printf '  ..    second zone: user %s\n' "$second"

printf '\n== install\n'
adb -s "$SERIAL" install -r --user 0 "$APK" >/dev/null 2>&1
check "installed for Home" 'ash pm path --user 0 "$PKG" | grep -q "^package:"'
ash pm install-existing --user "$second" "$PKG" >/dev/null 2>&1
check "installed for the second zone" 'ash pm path --user "$second" "$PKG" | grep -q "^package:"'

# The emulator has no usable internet for this (measured 2026-10-09,
# emulator-5562: Wi-Fi PARTIAL_CONNECTIVITY, never VALIDATED, TCP to the
# outside fails, so WorkManager's CONNECTED never holds). The file is served
# from the host over adb reverse instead, and the run is asked to skip the
# network constraint. Because the file comes from the host over loopback, a
# failed fetch here IS a failed test - the mechanics are what is proven; the
# network a phone has is the whole reason the hardware soak exists.
PORT="$(free_port)"
SERVE="$(mktemp -d)"; printf '{"lockVersion":1,"entries":[]}\n' > "$SERVE/lock.json"
( cd "$SERVE" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 ) & HTTP_PID=$!
trap 'kill $HTTP_PID 2>/dev/null; rm -rf "$SERVE"; emu_release' EXIT
adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
URL="http://localhost:$PORT/lock.json"
EXPECT_SHA="$(sha256sum "$SERVE/lock.json" | cut -d" " -f1)"

run_in() {   # $1 = user id; sets $rec
  local u="$1"
  ash am broadcast --user "$u" -n "$PKG/.ProbeReceiver" -a org.andashi.updater.probe.CANCEL >/dev/null 2>&1
  sleep 1
  ash am broadcast --user "$u" -n "$PKG/.ProbeReceiver" -a org.andashi.updater.probe.RUN --es url "$URL" --es constraint none >/dev/null 2>&1
  rec="$(wait_for 120 "^Row: " content query --user "$u" --uri "content://$PKG.state/runs")"
  check "user $u: a run was recorded within 120 s" '[ -n "$rec" ]'
  printf '  ..    user %s record: %s\n' "$u" "$(sed 's/^Row: 0 run=//' <<<"$rec" | head -c 400)"
}

printf '\n== without the battery exemption: the foreground service is refused\n'
# Measured 2026-10-09: a WorkManager job is background to the activity
# manager, and Android 12+ refuses to promote a background app to a
# foreground service. The record must say so in the system's words - this
# is the failure an updater without the exemption would have, and it is
# almost certainly Obtainium's.
ash dumpsys deviceidle whitelist | grep -q "$PKG" && ash cmd deviceidle whitelist "-$PKG" >/dev/null
run_in 0
check "user 0: promotion refused, in the system's words" 'grep -q "\"foreground\":false" <<<"$rec" && grep -q "ForegroundServiceStartNotAllowedException" <<<"$rec"'

printf '\n== with the exemption, once per device: it runs, in Home and in the second zone\n'
ash cmd deviceidle whitelist "+$PKG" >/dev/null
check "the allowlist entry reads back" 'ash dumpsys deviceidle whitelist | grep -q ",$PKG,"'
for u in 0 "$second"; do
  run_in "$u"
  check "user $u: the worker ran as a foreground service" 'grep -q "\"foreground\":true" <<<"$rec"'
  check "user $u: the record carries trigger, network and a result" 'grep -q "\"trigger\":\"shell\"" <<<"$rec" && grep -q "\"network\":" <<<"$rec" && grep -qE "\"ok\":(true|false)" <<<"$rec"'
  check "user $u: the bytes that arrived are the bytes that were served" 'grep -q "\"ok\":true" <<<"$rec" && grep -q "\"sha256\":\"$EXPECT_SHA\"" <<<"$rec"'
done

printf '\n== the soak can be scheduled and cancelled\n'
ash am broadcast --user 0 -n "$PKG/.ProbeReceiver" -a org.andashi.updater.probe.SCHEDULE >/dev/null 2>&1
# The periodic job itself, not the package name anywhere in the dump: the
# one-time runs above leave history there too.
check "periodic work is registered with JobScheduler" 'wait_for 30 "Periodic: interval=\+15m" sh -c "dumpsys jobscheduler | grep -A40 \"ProbeWorker#@androidx.work.systemjobscheduler@$PKG\""'
ash am broadcast --user 0 -n "$PKG/.ProbeReceiver" -a org.andashi.updater.probe.CANCEL >/dev/null 2>&1
sleep 3
check "cancel clears the record" '[ -z "$(ash content query --user 0 --uri "content://$PKG.state/runs" 2>/dev/null | grep Row)" ]'

printf '\n== the gate\n'
# The receiver and the provider require WRITE_SECURE_SETTINGS of the caller,
# which only shell and system hold. dumpsys does not print component
# permissions, and the shell cannot pose as an app, so the gate is read from
# the manifest that was installed: aapt2 on the APK the device got.
bt="$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/* | sort -V | tail -1)"
manifest="$("$bt/aapt2" dump xmltree --file AndroidManifest.xml "$APK")"
gated() { grep -A6 "E: $1" <<<"$manifest" | grep -q 'android:permission.*="android.permission.WRITE_SECURE_SETTINGS"'; }
check "receiver is gated by WRITE_SECURE_SETTINGS in the manifest" 'gated receiver'
check "provider is gated by WRITE_SECURE_SETTINGS in the manifest" 'gated provider'
# And the provider's second lock, the uid check, refuses nobody it should
# not: the shell reads it (that is what every query above did).

summary
