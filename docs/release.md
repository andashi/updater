# Releasing the updater

Decision [0003](decisions/0003-the-release-key-is-offline.md): the key never
enters CI. CI proves the build is reproducible and publishes the unsigned
artifact; the owner rebuilds, compares, signs, attaches. Every step either
verifies something or is refused.

## Once: the key

On a machine the owner trusts, offline if possible. The key is RSA 4096 or
EC P-256 in a PKCS12 keystore; a hardware token through PKCS#11 is better
and `apksigner` supports it (`--provider-class sun.security.pkcs11.SunPKCS11`).
With a file keystore:

```bash
keytool -genkeypair -v -keystore updater-release.p12 -storetype PKCS12 \
  -alias updater -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -validity 10950 -dname "CN=andashi updater"
keytool -list -v -keystore updater-release.p12 -alias updater | grep 'SHA256:'
```

The fingerprint printed last is the pin. It goes, by hand, into:

- this repository's README ("Signer");
- andashi.org, next to the launcher's;
- `apks/certs/org.andashi.updater.cert` in andashi/provisioning, through a
  pull request, lower-case hex without colons.

Keep the keystore and its password apart, each with a backup the owner can
reach without the other being online. Losing the key means every phone
needs a reinstall over adb (0003); it is the one thing in this project
that cannot be regenerated.

## Every release

1. **Tag.** `git tag v0.1.0 && git push origin v0.1.0` on a commit on
   `main` whose checks are green. CI (`release.yml`) runs the tests, builds
   the unsigned release on two runners, refuses if they differ, and opens a
   *draft* release with `updater-unsigned.apk`, `SHA256SUMS` and the tool
   versions.
2. **Rebuild and compare.** `bin/release-sign v0.1.0 updater-release.p12`
   checks out the tag, builds the unsigned release locally with the same
   pinned toolchain, downloads CI's artifact, and refuses to go on unless
   the three hashes (runner a, runner b, local) are one hash.
3. **Sign.** The same script signs CI's artifact - not the local build, so
   what ships is byte for byte what two runners and the owner agreed on -
   with `apksigner`, v2 and v3 signatures, and prints the signer
   fingerprint, which must equal the pin.
4. **Attach and publish.** `gh release upload v0.1.0 updater-v0.1.0.apk`,
   then publish the draft. The lock refresh in andashi/provisioning finds
   the asset the next morning, verifies it against the pinned signer, and
   the phones update themselves from it.

## What refuses

- CI: a build that differs between runners.
- `bin/release-sign`: a local build that differs from CI's, a tag that is
  not on `main`, a signer fingerprint that is not the pin recorded in the
  README.
- The phones: anything not signed by the pinned key, by Android's rule
  before the updater's.
