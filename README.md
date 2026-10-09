# andashi / updater

The app that keeps an [andashi](https://github.com/andashi/provisioning)
phone's apps current after the first install. One instance per zone. It is
the installer of record of every app the catalog names, fetches
`apks/lock.json` from the provisioning repository, verifies every APK
against the lock's SHA-256 and the signer the laptop pinned, installs it
silently, and calls an update done only when the package manager says so.

It never installs a package the laptop did not install first. That one rule
is what bounds a bad lock: it can choose among builds the upstream really
signed, or stall, and the phone reports both.

| | |
|---|---|
| [docs/design.md](docs/design.md) | the design, threat model first |
| [docs/decisions/](docs/decisions/README.md) | the decisions and their prices |
| [AGENTS.md](AGENTS.md) | how changes reach `main` |

## Layout

```
core/    pure Kotlin: lock, policy, state machine - every decision, unit-tested with its refusal case
app/     the updater, org.andashi.updater
probe/   a throwaway app that answers one hardware question (design §16)
e2e/     emulator tests, run by hand against andashi/provisioning's emulator
bin/     pr-gate, the merge conditions as a condition
```

```bash
./gradlew :core:test                      # the decisions
./gradlew :probe:assembleDebug            # the probe
./gradlew :app:assembleRelease            # unsigned; the owner signs offline (0003)
```

## Signer

The release certificate's SHA-256 fingerprint will be published here, on
andashi.org and in provisioning's `apks/certs/org.andashi.updater.cert`
once the first release is signed. Until then there is no release.
