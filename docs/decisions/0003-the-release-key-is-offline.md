# 0003 — The release key is offline

**Status:** accepted (2026-10-09), superseded 2026-10-11 by [0004](0004-the-release-key-lives-in-an-approved-environment.md): the owner will not sign at a keyboard per release; the key lives in a GitHub Environment that only the owner's Approve unlocks

## Context

The updater is the installer of record of every managed app and updates
itself through the same path. Android ties every later version to the key
that signed the first. Whoever holds that key ships code to every phone.
The launcher's key lives in GitHub secrets and signs on a tag push; a CI
compromise there means a malicious launcher, which has no network. Here it
would mean a malicious installer with network.

## Decision

The updater's key is separate from the launcher's and never enters CI. CI
builds the unsigned release from the tag, reproducibly, and publishes its
hash. The owner rebuilds locally, compares, and signs the CI artifact with
the offline key. The certificate's fingerprint is published in this
repository, on andashi.org and in provisioning's `apks/certs/`.

## Consequences

- A release needs the owner at a keyboard. Releases of this app are rare;
  that is acceptable, and it is the point.
- Reproducibility becomes a release gate: two independent CI builds must
  match, and the owner's local build must match them, or there is no
  release. Until that gate exists, there is no release either.
- Losing the key means every phone needs a reinstall over adb. The key is
  kept on a hardware token or an encrypted medium with a backup, and the
  ceremony is written down before the first release.
