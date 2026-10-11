#!/usr/bin/env bash
# The updater end to end on the emulator (design §16): the host installs an
# app naming the updater as installer, pushes a config, the updater fetches
# a lock, verifies, installs silently as installer of record, and the
# package manager confirms - plus every refusal the design names, and the
# updater's own update.
#
#   SERIAL=emulator-5562 OVERLAY_DIR=$PROV/emulator/instances/test-fold-gpu \
#   LOCK_OWNER=<yours> e2e/updater.sh
#
# Lock and APKs are served from the host over adb reverse (the emulator has
# no usable internet for this, measured 2026-10-09), which the debug build
# accepts for http://localhost and a release build refuses. The app under
# update is the probe, built in four versions under the debug key; the
# updater itself is built twice for the self-update.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPD=org.andashi.updater.debug
PROBE=org.andashi.updater.probe
SNAPSHOT="${SNAPSHOT:-provisioned-0120}"
PORT="$(free_port)"
WORK="$(mktemp -d)"; SERVE="$WORK/serve"; mkdir -p "$SERVE"
bt="$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/* | sort -V | tail -1)"

printf '== build: the updater twice, the probe five times\n'
build() {   # $1 = module, $2 = property, $3 = versionCode, $4 = output name
  ( cd "$ROOT" && ./gradlew -q ":$1:assembleDebug" "-P$2=$3" >/dev/null 2>&1 ) || { echo "build of $1 v$3 failed" >&2; exit 2; }
  cp "$ROOT/$1/build/outputs/apk/debug/$1-debug.apk" "$WORK/$4"
}
build app updaterVersionCode 1 updater-1.apk
build app updaterVersionCode 2 updater-2.apk
for v in 1 2 3 4 5; do build probe probeVersionCode "$v" "probe-$v.apk"; done
# Tampered: the lock will name probe-3's hash and size while this is what
# the URL serves - the same size, so the size gate passes and the hash gate
# is the one that has to catch it. Bytes in the middle of the file flipped.
python3 - "$WORK/probe-3.apk" "$WORK/probe-3-tampered.apk" <<'PY'
import sys
b = bytearray(open(sys.argv[1], 'rb').read()); m = len(b) // 2
for i in range(m, m + 64): b[i] ^= 0xff
open(sys.argv[2], 'wb').write(b)
PY
[ "$(stat -c %s "$WORK/probe-3.apk")" = "$(stat -c %s "$WORK/probe-3-tampered.apk")" ] || { echo "tampered file size differs" >&2; exit 2; }
SIGNER="$("$bt/apksigner" verify --print-certs "$WORK/probe-1.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1 | tr 'A-F' 'a-f')"
check "the debug signer fingerprint was read" '[ "${#SIGNER}" = 64 ]'
printf '  ..    signer %s\n' "$SIGNER"

sha() { sha256sum "$1" | cut -d' ' -f1; }
size() { stat -c %s "$1"; }
entry() {   # $1 = pkg, $2 = versionCode, $3 = file (hash + size from it), $4 = served name, [$5 = signer]
  jq -n --arg pkg "$1" --argjson vc "$2" --arg sha "$(sha "$3")" --argjson size "$(size "$3")" --arg signer "${5:-$SIGNER}" --arg url "http://localhost:$PORT/$4" \
    '{pkg:$pkg,label:$pkg,version:("v"+($vc|tostring)),versionCode:$vc,abi:"universal",file:("universal/"+$pkg+".apk"),sha256:$sha,size:$size,signer:$signer,urls:[$url]}'
}
lock() {   # $1 = generated, $2... = entries (json)
  local gen="$1"; shift
  jq -n --arg gen "$gen" --slurpfile e <(printf '%s\n' "$@") '{lockVersion:1,generated:$gen,entries:$e}' > "$SERVE/lock.json"
  # The ETag changes with the content; python's server sets none, so the
  # updater refetches every time, which is what this test wants.
}
serve() { cp "$WORK/$1" "$SERVE/$2"; }

( cd "$SERVE" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 ) & HTTP_PID=$!

emu_acquire "$SNAPSHOT"
# After acquisition: emu_acquire installs its own EXIT trap, and a trap set
# before it would be replaced. This one releases the emulator too.
trap 'kill $HTTP_PID 2>/dev/null; rm -rf "$WORK"; emu_release' EXIT
evidence "$SNAPSHOT"
adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

state() { ash content query --user 0 --uri "content://$UPD.state/state" 2>/dev/null | sed -n 's/^Row: 0 json=//p'; }
diag()  { ash content query --user 0 --uri "content://$UPD.state/diagnostics" 2>/dev/null | sed -n 's/^Row: 0 json=//p'; }
app_state() { state | jq -r --arg p "$1" '.apps[] | select(.pkg==$p) | .state'; }
app_field() { state | jq -r --arg p "$1" --arg f "$2" '.apps[] | select(.pkg==$p) | .[$f]'; }
installed_vc() { ash dumpsys package "$1" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
installer_of() { ash dumpsys package "$1" | sed -n 's/.*installerPackageName=\([^ ]*\).*/\1/p' | head -1; }
check_now() { ash am broadcast --user 0 -n "$UPD/org.andashi.updater.host.ControlReceiver" -a "$UPD.action.CHECK_NOW" >/dev/null 2>&1; }
wait_state() {   # $1 = pkg, $2 = state, $3 = seconds
  local deadline=$(( $(date +%s) + $3 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do [ "$(app_state "$1")" = "$2" ] && return 0; sleep 2; done
  return 1
}
wait_vc() {   # $1 = pkg, $2 = versionCode, $3 = seconds
  local deadline=$(( $(date +%s) + $3 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do [ "$(installed_vc "$1")" = "$2" ] && return 0; sleep 2; done
  return 1
}

printf '\n== the host installs: updater first, naming itself; the probe naming the updater\n'
# The updater's first install cannot name itself: the named installer must
# exist (measured 2026-10-10: `-i` naming a package not yet installed leaves
# the installer null). So: install, then the same version again with -i.
adb -s "$SERIAL" install -r --user 0 "$WORK/updater-1.apk" >/dev/null 2>&1
adb -s "$SERIAL" install -r -i "$UPD" --user 0 "$WORK/updater-1.apk" >/dev/null 2>&1
check "updater 1 installed, installer of record is the updater itself (after the second install)" '[ "$(installed_vc $UPD)" = 1 ] && [ "$(installer_of $UPD)" = "$UPD" ]'
ash appops set --user 0 "$UPD" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
check "install appop reads back as allow" 'ash appops get --user 0 "$UPD" REQUEST_INSTALL_PACKAGES | grep -q allow'
ash cmd deviceidle whitelist "+$UPD" >/dev/null 2>&1
check "battery exemption reads back" 'ash dumpsys deviceidle whitelist | grep -q ",$UPD,"'
adb -s "$SERIAL" install -r -i "$UPD" --user 0 "$WORK/probe-1.apk" >/dev/null 2>&1
check "probe 1 installed with the updater as installer of record" '[ "$(installed_vc $PROBE)" = 1 ] && [ "$(installer_of $PROBE)" = "$UPD" ]'

printf '\n== the config, through the ingest provider, and its read-back\n'
jq -n --arg u "http://localhost:$PORT/lock.json" --arg s "$SIGNER" --arg upd "$UPD" --arg probe "$PROBE" '{
  schemaVersion:1, zone:"home",
  lock:{mode:"fetch", url:$u, maxAgeDays:14, allowedHosts:["localhost"]},
  schedule:{checkEveryHours:6},
  policy:{minimumAgeHours:0, constraints:{notInCall:false, notForeground:false, boundHours:24}},
  managed:[{pkg:$probe,label:"Probe",signer:$s},{pkg:$upd,label:"Updater",signer:$s}],
  checks:{netFalse:[]}
}' > "$WORK/updater.json"
lock "2026-10-10T00:00:00Z" "$(entry $PROBE 1 "$WORK/probe-1.apk" probe-1.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
adb -s "$SERIAL" shell content write --user 0 --uri "content://$UPD.ingest/updater.json" < "$WORK/updater.json"
CFG_SHA="$(sha "$WORK/updater.json")"
check "diagnostics report exactly the config pushed, accepted" '[ "$(wait_for 30 "$CFG_SHA" content query --user 0 --uri "content://$UPD.state/diagnostics" | sed -n "s/^Row: 0 json=//p" | jq -r .success)" = true ]'
check "the first check finds both packages current" 'wait_state $PROBE current 90 && [ "$(app_state $UPD)" = current ]'
check "the lock state names the lock fetched" '[ "$(state | jq -r .lock.generated)" = "2026-10-10T00:00:00Z" ] && [ "$(state | jq -r .lock.lastError)" = null ]'
check "the exemption is reported granted" '[ "$(state | jq -r .exemption)" = granted ]'

printf '\n== a new version in the lock: silent update, confirmed by the package manager\n'
serve probe-2.apk probe-2.apk
lock "2026-10-10T01:00:00Z" "$(entry $PROBE 2 "$WORK/probe-2.apk" probe-2.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "probe 1 -> 2 installed, no prompt, within 180 s" 'wait_vc $PROBE 2 180'
check "the state says current only after the package manager does" 'wait_state $PROBE current 30 && [ "$(app_field $PROBE installed)" = 2 ]'
check "the updater is still installer of record" '[ "$(installer_of $PROBE)" = "$UPD" ]'
check "history records the update as confirmed" 'state | jq -e ".history[] | select(.pkg==\"$PROBE\" and .event==\"updated\" and .to==2)" >/dev/null'

printf '\n== refusals\n'
# Bytes that do not hash to what the lock says: refused before any session.
serve probe-3-tampered.apk probe-3.apk
lock "2026-10-10T02:00:00Z" "$(entry $PROBE 3 "$WORK/probe-3.apk" probe-3.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "tampered bytes: failed with the hash named, nothing installed" 'wait_state $PROBE failed 120 && app_field $PROBE lastError | grep -q sha256 && [ "$(installed_vc $PROBE)" = 2 ]' 'state | jq -c ".apps[] | select(.pkg==\"$PROBE\")"'
# A lock whose signer disagrees with the pin: unverifiable, nothing fetched.
lock "2026-10-10T03:00:00Z" "$(entry $PROBE 4 "$WORK/probe-4.apk" probe-4.apk "$(printf 'ab%.0s' $(seq 32))")" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "lock signer differs from the pin: unverifiable, nothing installed" 'wait_state $PROBE unverifiable 60 && app_field $PROBE reason | grep -q "lock signer" && [ "$(installed_vc $PROBE)" = 2 ]'
# A lock older than the one held: refused, the held one stays in force.
lock "2026-10-09T00:00:00Z" "$(entry $PROBE 4 "$WORK/probe-4.apk" probe-4.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "an older lock is refused as a rollback" '[ -n "$(wait_for 60 rollback content query --user 0 --uri "content://$UPD.state/state")" ] && [ "$(state | jq -r .lock.generated)" = "2026-10-10T03:00:00Z" ]'

printf '\n== the real bytes, a newer lock: the failed package is tried again and lands\n'
# Android throttles silent updates: a second silent update of the same
# package by the same installer within a short window is answered with
# STATUS_PENDING_USER_ACTION (measured 2026-10-10: probe 1->2 and 2->4
# about 25 s apart, the second asked for confirmation). Real locks move a
# version a day; the test waits the window out instead of hiding it with
# `pm set-silent-updates-policy`.
sleep 35
serve probe-4.apk probe-4.apk
lock "2026-10-10T04:00:00Z" "$(entry $PROBE 4 "$WORK/probe-4.apk" probe-4.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "probe 2 -> 4 installed" 'wait_vc $PROBE 4 180 && wait_state $PROBE current 30' 'state | jq -c "{apps: [.apps[] | select(.pkg==\"$PROBE\")], lock, history: .history[-6:]}"'

printf '\n== not owner: a shell install without -i strips the role; the host repairs it\n'
adb -s "$SERIAL" install -r --user 0 "$WORK/probe-4.apk" >/dev/null 2>&1
lock "2026-10-10T05:00:00Z" "$(entry $PROBE 4 "$WORK/probe-4.apk" probe-4.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "the stripped role is reported at once, version match or not" 'wait_state $PROBE not-owner 60 && app_field $PROBE reason | grep -q nobody'
serve probe-4.apk probe-5.apk   # never served; the entry only has to exist for "behind"
lock "2026-10-10T06:00:00Z" "$(entry $PROBE 5 "$WORK/probe-4.apk" probe-5.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "an update due with nobody as installer is reported not-owner, not attempted" 'wait_state $PROBE not-owner 60 && app_field $PROBE reason | grep -q nobody && [ "$(installed_vc $PROBE)" = 4 ]'
adb -s "$SERIAL" install -r -i "$UPD" --user 0 "$WORK/probe-4.apk" >/dev/null 2>&1
lock "2026-10-10T07:00:00Z" "$(entry $PROBE 4 "$WORK/probe-4.apk" probe-4.apk)" "$(entry $UPD 1 "$WORK/updater-1.apk" updater-1.apk)"
check_now
check "a same-version reinstall naming the updater restores the role" '[ "$(installer_of $PROBE)" = "$UPD" ] && wait_state $PROBE current 60'

printf '\n== the updater updates itself\n'
serve updater-2.apk updater-2.apk
lock "2026-10-10T08:00:00Z" "$(entry $PROBE 4 "$WORK/probe-4.apk" probe-4.apk)" "$(entry $UPD 2 "$WORK/updater-2.apk" updater-2.apk)"
check_now
check "updater 1 -> 2 installed silently" 'wait_vc $UPD 2 180 && [ "$(installer_of $UPD)" = "$UPD" ]'
check "the new version reports itself and the history survived the restart" '[ "$(wait_for 60 "\"updaterVersionCode\":2" content query --user 0 --uri "content://$UPD.state/state" | sed -n "s/^Row: 0 json=//p" | jq -r .updaterVersionCode)" = 2 ] && state | jq -e ".history[] | select(.pkg==\"$UPD\" and .event==\"updated\" and .to==2)" >/dev/null'
check "the probe is still current under the new updater" 'wait_state $PROBE current 60'

printf '\n== install constraints: the commit waits for a quiet moment, then lands and is reconciled\n'
# The production policy: not in a call, not in the foreground. The probe is
# neither, but the system checks its constraints at its own interval -
# measured 2026-10-10 on this emulator: about two minutes between commit
# and install. The receiver then moves the record, and a delayed reconcile
# reads the package manager regardless.
jq '.policy.constraints = {notInCall:true, notForeground:true, boundHours:24}' "$WORK/updater.json" > "$WORK/updater-constrained.json"
adb -s "$SERIAL" shell content write --user 0 --uri "content://$UPD.ingest/updater.json" < "$WORK/updater-constrained.json"
check "the constrained config is accepted" '[ "$(wait_for 30 "$(sha "$WORK/updater-constrained.json")" content query --user 0 --uri "content://$UPD.state/diagnostics" | sed -n "s/^Row: 0 json=//p" | jq -r .success)" = true ]'
sleep 35   # the silent-update throttle, see above
serve probe-5.apk probe-5.apk
lock "2026-10-10T09:00:00Z" "$(entry $PROBE 5 "$WORK/probe-5.apk" probe-5.apk)" "$(entry $UPD 2 "$WORK/updater-2.apk" updater-2.apk)"
check_now
check "the session is committed and waits" 'wait_state $PROBE waiting-constraints 120' 'state | jq -c ".apps[] | select(.pkg==\"$PROBE\")"'
check "probe 4 -> 5 lands within five minutes" 'wait_vc $PROBE 5 300' 'state | jq -c ".apps[] | select(.pkg==\"$PROBE\")"'
check "the state follows the package manager" 'wait_state $PROBE current 60 && state | jq -e ".history[] | select(.pkg==\"$PROBE\" and .event==\"updated\" and .to==5 and .result==\"success\")" >/dev/null' 'state | jq -c "{apps: [.apps[] | select(.pkg==\"$PROBE\")], history: .history[-5:]}"'

printf '\n== the gates, from the installed manifest\n'
manifest="$("$bt/aapt2" dump xmltree --file AndroidManifest.xml "$WORK/updater-2.apk")"
gated() { grep -A8 "E: $1" <<<"$manifest" | grep -q "$2" && grep -A8 "E: $1" <<<"$manifest" | grep -q 'android.permission.WRITE_SECURE_SETTINGS'; }
check "ingest provider is gated" 'grep -B2 -A8 "ingest" <<<"$manifest" | grep -q WRITE_SECURE_SETTINGS'
check "state provider is gated" 'grep -B2 -A8 "\.state\"" <<<"$manifest" | grep -q WRITE_SECURE_SETTINGS'
check "control receiver is gated" 'grep -A8 "ControlReceiver" <<<"$manifest" | grep -q WRITE_SECURE_SETTINGS'
check "install status receiver is not exported" 'grep -A8 "InstallStatusReceiver" <<<"$manifest" | grep -q "exported.*=false"'

summary
