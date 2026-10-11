# 0004 — The release key lives in an approved environment

**Status:** accepted (2026-10-11), supersedes the "never enters CI" of
[0003](0003-the-release-key-is-offline.md)

## Context

0003 kept the updater's signing key offline: CI builds, the owner rebuilds,
compares and signs at a keyboard. The owner will not run a local signing
step per release; it has to be as comfortable as possible without being
insecure. What 0003 was defending against stands: a key that any CI run
can read makes a CI compromise a malicious updater on every phone.

## Decision

The key is a secret of the GitHub Environment `release` in andashi/updater,
not a repository secret, and the environment's only required reviewer is
the owner. The signing job runs in that environment: it waits for the
owner's Approve - one tap, in the mobile app if need be - and only then
receives the key. CI builds the unsigned release twice, on independent
runners, and the signing job refuses to run unless both builds are one
APK; it publishes the hashes of the unsigned and the signed APK and the
signer's fingerprint. It is the updater's own key, not Andashi Home's.
The one-time setup is the owner's: a script creates the keystore and sets
the secrets from the owner's shell, and the key and its passwords never
pass through any other session.

## Consequences

- Per release, the owner's part is the Approve tap and nothing else. That
  tap is the one human look at an updater release, and it is kept.
- The scheme rests on the owner's GitHub account, which is protected by a
  hardware key. That is a stated assumption of docs/release.md, not a
  detail.
- A CI compromise can still not sign: a workflow that was changed to leak
  the key would have to be approved by the owner, in the environment, on
  a tag push. A pull request cannot run the signing job.
- Reproducibility stays a gate; the owner's local rebuild is no longer part
  of it, two independent runners are.
- Andashi Home signs with plain repository secrets; moving it to the same
  scheme is a later follow-up.
