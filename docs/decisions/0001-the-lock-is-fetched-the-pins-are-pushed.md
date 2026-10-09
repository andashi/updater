# 0001 — The lock is fetched; the pins are pushed

**Status:** accepted (2026-10-09)

## Context

The updater needs two inputs: which build of each app is current (the
lock), and which signing key each app must carry (the pin). The lock on
`main` changes daily without a person (provisioning's lock refresh). A key
that signs it would either sit in CI, where it signs whatever CI commits, or
with a person, who would then gate every daily refresh. Neither buys more
than Android already enforces: an update installs only under the signer of
the installed app.

## Decision

`apks/lock.json` is fetched over HTTPS from the provisioning repository's
`main`, unsigned in v1, with a monotonic `generated`, a maximum age, and a
heartbeat bound to the lock's hash. The signer pins come from the host over
adb, pushed into each zone with the managed set, never from the lock. The
lock must agree with the pins, the APK with both. The updater never
first-installs a package, so a lock can only choose among upstream-signed
builds of packages the host already installed, or freeze.

## Consequences

- An attacker holding GitHub or CI cannot run code on the phone through
  this path; they can delay, and the phone says so.
- A pin changes only through the laptop. That is one cable per key rotation
  per app, which is rare, and it is the price of the lock being unable to
  move a pin.
- The verification sits behind one interface, so a signed lock (a CI key for
  version moves, an offline key for pin changes) can be added later without
  touching the loop. It is not built now.
- The first install on the host still trusts the lock's hash alone; that is
  provisioning's to settle, not this app's.
