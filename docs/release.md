# Releasing the updater

Decision [0004](decisions/0004-the-release-key-lives-in-an-approved-environment.md):
the key lives in a GitHub Environment that only the owner can unlock, CI
proves the build is reproducible before it may sign, and the owner's part
of a release is one Approve tap. Every step either verifies something or is
refused.

**Stated assumption:** the owner's GitHub account is protected by a
hardware key (YubiKey). The Approve tap is the one human look at a release,
and it is only as strong as that account.

## Once: the key and the environment

The environment `release` in andashi/updater exists with the owner as its
only required reviewer; CI can create it, because it holds no secret.
The key itself is made and stored by the owner, on the owner's machine,
and never passes through any session but the owner's shell:

```bash
bin/release-key-setup
```

The script first checks that the environment requires exactly one
reviewer, the person running it, and refuses otherwise. It asks for one
password (typed twice, never shown - a PKCS12 keystore protects its key
with the keystore password and ignores a separate key password), runs
`keytool` to create `updater-release.p12`, prints the signer fingerprint,
and sets the three secrets at environment scope with
`gh secret set --env release`: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`. The keystore file stays on the owner's disk as
the backup - GitHub secrets are write-only - and is kept with its password
apart from it. Losing both means every phone needs a reinstall over adb; it
is the one thing in this project that cannot be regenerated.

The fingerprint the script prints is the pin. It goes, by hand, into:

- this repository's README ("Signer");
- andashi.org, next to the launcher's;
- `apks/certs/org.andashi.updater.cert` in andashi/provisioning, through a
  pull request, lower-case hex without colons.

## Every release

1. **Tag.** `git tag v0.1.0 && git push origin v0.1.0` on a commit on
   `main` whose checks are green. `release.yml` runs the tests, builds the
   unsigned release on two runners, and refuses to go on if they differ.
2. **Approve.** The signing job waits in the `release` environment. GitHub
   asks the owner to approve the deployment - in the mobile app, that is
   one tap. Nothing before that tap has the key.
3. **Sign and publish.** The job verifies it holds the artifact both runners
   agreed on, signs it with v2 and v3 signatures, prints the signer
   fingerprint, and publishes the release: the signed APK, the unsigned
   APK, `SHA256SUMS` for both, the certificate dump and the tool versions.
   A re-run of the workflow for the same tag updates the same release.
4. **The phones.** The lock refresh in andashi/provisioning finds the asset
   the next morning, verifies it against the pinned signer, and the
   updaters install it as they install everything else, themselves
   included.

## What refuses

- CI: a build that differs between runners; a tag that is not `v*`.
- The environment: a signing job nobody approved never gets the key.
- The signing job: an artifact that does not match the hash the runners
  agreed on.
- The phones: anything not signed by the pinned key, by Android's rule
  before the updater's.
