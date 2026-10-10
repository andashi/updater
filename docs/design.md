# andashi updater — design

**Status:** accepted 2026-10-09 (decisions [0001](decisions/0001-the-lock-is-fetched-the-pins-are-pushed.md),
[0002](decisions/0002-network-in-every-zone-anon-through-tor.md),
[0003](decisions/0003-the-release-key-is-offline.md)); being built. Everything
marked *measure* is a claim about Android or GrapheneOS that has to be proven
on the emulator (shell identity asserted) before it is relied on; what has
been measured says so with date and instance.

## 1. The problem

After `provision/run.sh` nothing on the phone updates itself. Four zones have
no store, Obtainium knows no app after provisioning, may not install, cannot
update an app the chain installed without a person confirming once per app,
and its background updates fail silently
([provisioning#18](https://github.com/andashi/provisioning/issues/18),
measured 2026-09-30). On the day the lock was introduced, 10 of about 20
checkable apps were behind upstream, Signal, Bitwarden, Orbot and Tor Browser
among them.

The requirement, from the owner: **no managed app stays unpatched. Every app
either updates within a bounded time without a person, or the phone says so,
loudly.** A device owner is not wanted
([0004](https://github.com/andashi/provisioning/blob/main/docs/decisions/0004-adb-from-a-host.md),
boundaries.md): the device stays unmanaged.

This document is the design of the app that meets that requirement, with the
threat model first, because the updater becomes the most powerful app andashi
ships: it holds `INTERNET` and the right to install in every zone, Anon
included, and it is the installer of record of every managed app.

## 2. Scope of v1, and what stays out

In:

- one instance per zone (one per Android user: Home, Cloud, Gadgets, Ops,
  Lab, Anon; not Work — see §13);
- update every *managed* app (installed by the chain from `apks/lock.json`)
  to the version the lock names, silently, verified by hash and signer, and
  confirm the result by reading the package manager back;
- a state the host reads, exactly like the launcher: per app, per zone, what
  is installed, what the lock says, what failed and why;
- a notification whenever a person is needed or something is wrong;
- a report of what the updater does *not* manage: apps in the zone that are
  not in the catalog, managed apps it does not own, `net: false` apps that
  hold `INTERNET` after all (five lines, read-only, and boundaries.md asks for
  exactly this check);
- its own update, through the same path as every other app.

Out, deliberately:

- first installs. A normal app cannot first-install without a system dialog
  per app; the host can, over adb, naming the updater as installer. The
  updater never installs a package that is not already installed in its user
  (§6). That one rule bounds what a bad lock can do (§3).
- replacing Obtainium, Accrescent, sandboxed Play or the GrapheneOS App Store.
  They keep their apps; the updater keeps the catalog's.
- anything that needs a privilege a normal app does not have (revoking a
  permission, stopping a zone, changing a setting). It reports; the host acts.
- split APKs, app bundles, delta updates. The lock names single APKs.
- a settings UI that can weaken policy. Policy comes from the host, read-only
  on the phone.

The name may become broader later (the owner expects more security checks to
land here). The architecture keeps a `checks` slot open for that (§15) without
building any of it now; the application id has to be chosen now and can never
change (§17, question 0).

## 3. Threat model

The question for each attacker: what can they make the phone *run* that it
would not otherwise run, and what can they *keep it from* running.

### 3.1 What the design relies on

Three facts carry the weight, in this order:

1. **Android's signer continuity.** An update installs only if its signing
   certificate matches the installed app's, or the new APK carries a v3
   signing lineage signed by the old key. No installer, privileged or not,
   gets around this; the shell does not either. For an app that is already
   installed, *nobody who lacks the upstream's signing key can replace it with
   their own code* — whatever the lock, the network or the updater say.
2. **The updater never first-installs.** Continuity only protects a package
   that exists. A lock that names a package the host never installed into
   this user is ignored for installation and named in the report. So the set
   of packages on a phone is decided over adb, by the person with the cable,
   and never by a file fetched from the internet.
3. **The signer pins come from the host, not from the lock.** The host
   pushes the managed set per zone *with* each package's pinned signer
   (`apks/certs/`, provenance-audited on the maintainer's machine), through a
   shell-gated provider. The fetched lock must agree with the pin, and the
   downloaded APK must agree with both. The lock cannot move a pin; only the
   laptop can.

Everything else — the hash in the lock, TLS, the host allowlist, the
monotonic lock sequence — narrows what remains, which is: *among the builds
the upstream really signed, which one runs, and when.*

### 3.2 Attackers

| Attacker controls | Can | Cannot |
|---|---|---|
| **GitHub, the provisioning repo, CI, the daily lock refresh** (a lock of their choosing on `main`) | Choose among builds the upstream signed: hold the phone on an older lock version (bounded by §11: no downgrade, max age, monotonic sequence); point downloads at any allow-listed host; make every update fail, loudly; add noise to the report. | Install code not signed by the upstream key of an installed app. Install any new package. Change a signer pin. Reach a zone whose updater is in host-only mode. Produce an updater release, *if* the updater's key is offline (§12) — with a CI-held key this attacker ships a malicious updater to every phone, which is the single strongest argument in this document. |
| **The network** (MITM, a hostile VPN app in the zone, a Tor exit, a captive portal) | Deny: block the lock, block downloads, serve garbage. In Anon through Tor, delay for hours. | Alter what gets installed: TLS to system CAs only (user-added CAs are not trusted, §10), and the hash from the lock decides before a byte reaches the package manager. Learn much: the updater fetches two kinds of URL and sends nothing else (§10). |
| **A compromised app in the same zone** | What any app can: nothing against the updater's components — every exported component is gated to the shell uid; cannot write its config, cannot read its state, cannot trigger a check. If it is itself an installer (Obtainium), it can update a managed app to another upstream-signed build, after which the updater is no longer installer of record and reports `not-owner` (§7). | Make the updater install anything: the updater takes no input from apps. Cross into another zone: the profile wall is the OS's. |
| **A compromised upstream** (stolen signing key, poisoned release) | Ship code to every phone that runs the app — through this updater within a day of the lock refresh, through Obtainium or a store the same day. | Nothing stops this at the device; what limits it is the lock refresh's `review` verdict on a changed signer, which does not catch a *stolen* key. A minimum age (§11) buys a window in which the ecosystem notices; the price is a window in which a real fix does not arrive. |
| **The laptop** (the adb key) | Everything, by design ([0004](https://github.com/andashi/provisioning/blob/main/docs/decisions/0004-adb-from-a-host.md)): install any package into any user with any installer of record, push any config and any pin to the updater, point it at any lock. | Nothing new. The updater adds no power the shell did not already have; its shell-gated interface is as strong as the launcher's, which is as strong as adb. |
| **A person holding the unlocked phone** in a zone | See the status, trigger a check, open the system's install dialog when one is pending. | Change policy, add a package, change the lock URL, disable verification: there is no UI for any of it. |
| **A bug in the updater itself** | The worst case is a verification bypass, which would make the lock attacker as strong as the network attacker — still bounded by signer continuity (3.1 fact 1). | Elevate beyond its permissions: it holds `INTERNET`, the install appop and `QUERY_ALL_PACKAGES`, nothing privileged. |

What this table says about *where to spend effort*: on the updater's own
release key (§12), on never first-installing (§6), on the verification path
being small, pure and tested with refusal cases (§16) — and not on signing the
lock, which only re-states what Android already enforces for updates (§17,
question 1). The one place where a signed lock *would* matter is the first
install on the host, where `from-lock.sh` compares the hash alone; that is
provisioning's problem and is named there.

### 3.3 Proxy success, the recurring defect

Every failure this project has had shares one shape: a step read a proxy and
called it success. Obtainium logs "Done installing updates" while nothing was
installed. The rule for this app, stated once:

**An update is `current` when, and only when, the package manager of this
user reports the lock's `versionCode` for that package after the install
session's status receiver reported success.** Not when the session was
committed, not when the download finished, not when the log said so. Every
other state has a name (§7) and is reported under it.

## 4. The trust chain, end to end

```
maintainer machine        GitHub (main)              phone, per zone
─────────────────         ─────────────              ─────────────────────────
fetch.sh ─ verify.sh ─►   apks/lock.json  ──HTTPS──► lock cache ──┐
pin.sh ─ provenance.sh    (daily refresh, "safe"                  │ must agree
                           commits without a person)              ▼
apks/certs/<pkg>.cert ───── laptop ──adb (shell)──► managed set + signer pins
config/apps.json                                      (shell-gated ingest)
                                                               │
                                                               ▼
                           upstream release ──HTTPS──► APK bytes ──► sha256 == lock
                           (GitHub assets,                        ──► signer == pin
                            torproject, f-droid)                  ──► pkg, versionCode == lock
                                                                  ──► PackageInstaller
                                                                  ──► read back versionCode
```

Three inputs, three trust levels:

| Input | Arrives | Trusted because |
|---|---|---|
| managed set, signer pins, policy, lock URL | from the laptop over adb into the ingest provider | adb is the root of trust of the whole distribution |
| the lock | fetched over HTTPS from the provisioning repo (or pushed by the host in host-only mode) | it agrees with the pins; it is monotonic; Android continuity bounds it anyway |
| APK bytes | fetched from the upstream URLs the lock names | the hash matches the lock, the signer matches the pin, the parsed package name and version match the lock |

## 5. Architecture

### 5.1 Two halves, like the launcher

The pattern is the launcher contract
([launcher.md](https://github.com/andashi/provisioning/blob/main/docs/architecture/launcher.md)):
a write-only ingest provider, an explicit broadcast, a read-only state
provider, all three exported and gated to the shell uid by
`WRITE_SECURE_SETTINGS` as the caller's permission plus a uid check in code.
The updater holds that permission itself no more than the launcher does.

```
host (bin/andashi, provisioning)                 phone, one instance per zone
────────────────────────────────                 ────────────────────────────────
content write  updater.json ─────────────────►  ingest provider (shell only)
am broadcast   CHECK_NOW ────────────────────►  receiver (shell only) ──► worker
content query  state ◄───────────────────────   state provider (shell only)
adb install -r -i <updater> --update-ownership   (first installs, stopped zones)
```

### 5.2 On the device

| Component | Does | Exported |
|---|---|---|
| `IngestProvider` | accepts `updater.json` (config, §9.1) and, in host-only mode, `lock.json`; writes atomically into app-private storage | yes, shell-gated |
| `StateProvider` | serves `state` (§9.2) and `diagnostics` as JSON | yes, shell-gated |
| `ControlReceiver` | `CHECK_NOW`; enqueues the worker and nothing else | yes, gated by the manifest permission alone: inside `onReceive` the calling uid is the app's own, so a uid check is impossible there (measured 2026-10-09, probe on emulator-5562) |
| `CheckWorker` | periodic: fetch lock, validate, compare with installed packages, decide (§7) | no |
| `InstallWorker` | expedited, foreground (data sync): download, verify, open session, commit, await result | no |
| `InstallStatusReceiver` | receives the session result; the only place that may move a package to `current` | no (PendingIntent) |
| `StartReceiver` | `BOOT_COMPLETED` for this user: a zone that just started catches up at once (§8) | no (system) |
| `StatusActivity` | a list: per app the state, last check, a *Check now* button, the pending user-action if any | launcher icon |
| notifications | one channel for *action needed*, one for *failures*, one low-priority for the foreground service | — |

The decision logic (`core/`) is pure Kotlin with no Android import: lock
parsing and validation, policy, the state machine. Android is behind three
interfaces — `Packages` (what is installed, signer, installer of record),
`Fetcher` (bytes from a URL into a file, with the size bound), `Installer`
(sessions). That is what makes the refusal cases unit-testable (§16).

### 5.3 On the host

Nothing new in kind. The provisioning repo gains:

- a catalog entry for the updater (source `obtainium`, i.e. our GitHub
  release, all six zones), installed *first* in `10-apps.sh`, pinned in
  `apks/certs/` - installed twice on a fresh device, the second time with
  `-i` naming itself, because the first cannot (§6);
- `-i <updater>` (and `--update-ownership`, *measure*) on every install of a
  managed app, so the updater is installer of record from day one; the same
  for `install-existing`, which carries the installer of record per package;
- `appops set --user N <updater> REQUEST_INSTALL_PACKAGES allow` and
  `pm grant POST_NOTIFICATIONS`, read back (both measured as shell on #18);
  `cmd deviceidle whitelist +<updater>` once per device, read back from
  `dumpsys deviceidle whitelist` (§8: without it no foreground service
  starts from a background job);
- a generator `config/gen-updater.sh` → `config/updater/<zone>.json` from
  `apps.json`, `profiles.json` and `apks/certs/`, checked in, diffed by
  `make check` like the launcher files;
- a step `50-updater-config.sh`: push, broadcast, read back;
- `andashi status` reads the state provider in running zones and `pm` for
  stopped ones; `andashi update` broadcasts `CHECK_NOW` to running zones and
  waits for the state to settle, and sweeps stopped zones over adb (§8).

## 6. Who installs what

| Case | Path | Why |
|---|---|---|
| first install of a managed app into a zone | host, `adb install -r -i <updater> --update-ownership --user N` (or `pm install-existing --user N` when the package is on the device already) | a normal app cannot first-install silently; the shell can, and it sets the installer of record (#18). Measured 2026-10-02, emulator-5562, shell: `-i` with `--update-ownership` succeeds without a prompt; `install-existing` carries the installer per package |
| update in a running zone | the updater, `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED` | silent when: installer of record, `REQUEST_INSTALL_PACKAGES` allowed, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (normal permission, declared), updater targets API 31+, app targets API 29+, same signer, and (Android 14+) nobody else holds update ownership |
| update of a package that lives only in stopped zones | host, same adb command, on the stopped user | nothing runs in a stopped user; `adb install -i <pkg> --user 14 <apk>` into stopped Lab succeeded, the user stayed stopped, data is created on first unlock (measured 2026-10-02, shell) |
| update of a package shared with a running zone | whichever running zone's updater gets there first; the code path is device-wide | a package is installed once per device; Ops' Bitwarden is Home's Bitwarden |
| the updater itself | the updater, same session path; the host at provisioning | self-update as installer of record is silent; the process dies on commit, so the in-flight session is persisted first and reconciled on the next start. Its first install cannot name itself as installer, because the named installer must already exist (measured 2026-10-10, emulator-5562, shell: installer stays null); the host installs it, then installs the same version again with `-i`, which moves the installer (§6, measured 2026-10-02) |
| anything Obtainium/Accrescent/Play/the GrapheneOS store installed | theirs | decision 0012; the updater reports them as `foreign` and never touches them |

The owner's line "andashi install should probably use the app directly"
resolves to: the app performs every update it *can* perform, and the host
drives it (`andashi update` = broadcast + wait + read back) rather than
re-implementing the install. Where the app cannot run — first installs, stopped
zones — the host installs, naming the app as installer, so that the next
update is the app's again. Handing APK bytes from the host to the app through
the provider was weighed and dropped for v1: it buys one code path at the cost
of streaming every APK through binder, and the stopped-zone case needs adb
anyway. The ingest accepts a lock in host-only mode; APKs stay with adb.

**The shell strips the installer of record, silently.** Measured 2026-10-02:
`adb install -r --user 0 <apk>` *without* `-i` on a package the updater owns
(with `--update-ownership`) succeeds, prompts nothing, and resets the
installer to null. Update ownership protects against other apps, not against
the shell. So every host install path must pass `-i <updater>`: the per-user
install in `10-apps.sh`, the device-wide upgrade in `ensure_pinned_version`
(which has no `-i` today), `andashi apply` and `make update`. The updater
reports the damage as `not-owner` with installer null, and the host repairs
it without a prompt: a same-`versionCode` reinstall with `-i` moves the
installer of record (measured on Bitwarden and DAVx5; also the migration path
for phones provisioned before `-i`). Nothing on this build reads update
ownership back — no `dumpsys` field, no `pm` getter — so ownership is
measured only by its effects (§13).

**Anon is the only zone whose apps no other zone updates.** Orbot, Molly and
Tor Browser live there alone. Every other zone's apps are also in Home or
Cloud, which always run. So the question "does Anon need network?" is the
question "who updates Tor Browser?" (§17, question 2).

## 7. The update loop and its states

Per managed package, the state is one of:

| State | Meaning | Reported as |
|---|---|---|
| `current` | installed `pkg`, `versionCode` **and the APK's SHA-256** match the lock, the signer matches the pin, **and** installer of record is the updater | fine |
| `held` | an update is due but younger than `minimumAgeHours` (§11); nothing is fetched until the window has passed | pending, with the time it opens |
| `behind` | installed < lock; an update is due | pending |
| `ahead` | installed > lock; somebody updated past the lock, or the lock went back | warning, never downgraded |
| `downloading` / `verifying` / `installing` / `waiting-constraints` | in flight, with the session id | pending |
| `user-action` | the system wants a tap (`STATUS_PENDING_USER_ACTION`); notification posted with the system's intent — never an activity start from the background, which GrapheneOS blocks ("Background activity launch blocked", logged for Obtainium on 2026-10-02) | **action needed** |
| `not-owner` | installer of record is somebody else, or null after a shell install without `-i`; a silent update is impossible | warning; the host fixes it by a same-version reinstall with `-i` (§6) |
| `failed` | with the reason, attempt count, next retry | failure after the retry budget |
| `missing` | managed, not installed in this user | warning (the host's job) |
| `unverifiable` | no lock entry for this ABI, or lock and pin disagree | **failure** |

The loop, per check:

1. **Lock.** Fetch `lock.json` with `If-None-Match`; on 304 keep the cached
   one. Validate shape (`lockVersion == 1`), every entry (64-hex sha256,
   positive `versionCode`, `size` within bounds, every URL `https` on an
   allow-listed host), monotonic `generated` against the cached lock (an
   older lock is refused and reported), age against `maxAgeDays`. Then, for
   every managed package: the lock's `signer` must equal the pin from the
   host, or the package is `unverifiable`.
2. **Compare.** Read the installed `versionCode`, the installed APK's
   SHA-256 (hashed once per installed build and cached by its
   `lastUpdateTime`), signer and installer of record for every managed
   package. Decide the state. A lock that names the same `versionCode` with
   other bytes - a rebuild the maintainer took through `review` - is
   `behind`, not `current`: the phone converges to the locked bytes. Nothing
   is downloaded for `current`, `ahead`, `held`, `not-owner`,
   `unverifiable`.
3. **Download** into app-private storage, streaming through SHA-256,
   refusing a body longer than `size`. URLs in order; the first that yields
   the locked bytes wins. A partial file is kept for a `Range` retry (v1.1;
   v1 restarts).
4. **Verify** the file: sha256 == lock; `getPackageArchiveInfo` with signing
   certificates → package name == lock, `versionCode` == lock, exactly one
   signer, SHA-256 of its certificate == pin. Any mismatch deletes the file
   and records the reason.
5. **Install.** Open a session (`MODE_FULL_INSTALL`, `setAppPackageName`,
   `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`, `setPackageSource(STORE)`),
   write the file into the session **re-hashing while writing** and abandon
   the session if the second hash differs (closes the file-swap window
   entirely). Re-read the installed `versionCode` immediately before commit:
   if it already carries the target's `versionCode` and bytes — another zone
   got there first — abandon as `current`. Commit through install constraints (§11) or directly.
6. **Confirm.** The status receiver's `STATUS_SUCCESS` triggers a read-back;
   only the lock's `versionCode` with the lock's bytes under the updater's
   ownership sets `current`. Anything else is its own state with the
   system's message attached. The process may have been killed in
   the meantime (self-update, low memory); the persisted in-flight record is
   reconciled against the package manager on every start.

Every transition is appended to a bounded history (what, when, from which
lock, result) that the state provider serves. That history is the audit trail
the host has never had.

## 8. Background, scheduling, stopped zones

- **Periodic check:** WorkManager, every 6 h by default (host-configurable,
  not below 15 min), constraint `NetworkType.CONNECTED`, with retry and
  exponential backoff. A VPN zone whose tunnel is down looks "connected" to
  the constraint and fails the fetch; backoff handles it, and the state says
  `lock: unreachable since …`.
- **Download and install** run as expedited one-time work inside a
  foreground service of type `dataSync` (declared, with
  `FOREGROUND_SERVICE_DATA_SYNC`). Android 15 caps `dataSync` at six hours
  per day — plenty.
- **The exemption that makes it possible.** Measured 2026-10-09 on
  emulator-5562 (probe, shell identity asserted): a job started by
  WorkManager is *background* to the activity manager, and Android 12+
  refuses to promote a background app to a foreground service ("Background
  started FGS: Disallowed", `ForegroundServiceStartNotAllowedException`).
  With the package on the device-idle allowlist — the "unrestricted"
  battery setting, which the shell sets with
  `cmd deviceidle whitelist +<pkg>` — the same start is "Allowed,
  SYSTEM_ALLOW_LISTED", the service runs, the fetch lands. The entry is per
  package and device-wide: added once, it held in Home and in Anon through
  Orbot's lockdown VPN. So the host grants the exemption at provisioning,
  reads it back from `dumpsys deviceidle whitelist`, and the updater
  reports `exemption: missing` loudly if it ever finds itself without it.
  Measured 2026-10-10 on the same instance: the entry survives
  `install -r` of the same or a newer version (so a self-update keeps it)
  and is dropped by `pm clear` and by uninstall; after either, the host
  grants it again. Uninstalling the updater also resets the installer of
  record of every app it installed to null, so an uninstall is never a
  repair: the host would have to reinstall every managed app with `-i`.
  This is almost certainly Obtainium's missing piece too: the one GrapheneOS
  Pixel report that said "unrestricted fixed it" (#1550, 2025-08-28) is
  this exemption.
- **A zone that starts** receives `BOOT_COMPLETED` for its user: measured
  2026-10-02 on `am start-user -w 13`, the broadcast history shows
  `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED` and `USER_UNLOCKED` to user 13,
  and a WorkManager reschedule receiver in that user got it. With a PIN on
  the zone, `BOOT_COMPLETED` waits for the unlock (standard Android, not
  measured here) — which is the right moment anyway, since nothing can be
  installed into locked storage. The updater runs a check at once rather
  than waiting for the window. For Anon
  that means: switch to Anon, and within minutes Tor Browser, Orbot and
  Molly are current — or the notification says why not.
- **A stopped zone** runs nothing, and the three-running-users rule says it
  must not be started for this ([0003](https://github.com/andashi/provisioning/blob/main/docs/decisions/0003-cloud-owns-the-third-slot.md)).
  Its packages that also live in a running zone are updated there. Its
  exclusive packages wait for the zone to start, or for a host sweep:
  `andashi update --zone anon` installs over adb from the host inventory
  (`make from-lock`), naming the updater, without starting the zone. `andashi
  status` names the gap meanwhile ("Anon: 3 behind, zone stopped").
- **Two running zones, one package:** both may download; the second abandons
  before commit (§7 step 5). The waste is one download; a channel between
  zones to avoid it would be a channel between zones, which the model
  forbids. Check windows are jittered per zone so it is rare.

## 9. The host interface

### 9.1 `updater.json` (ingest, host → phone), schema 1

```jsonc
{
  "schemaVersion": 1,
  "zone": "anon",
  "lock": {
    "mode": "fetch",                       // fetch | host-only
    "url": "https://raw.githubusercontent.com/andashi/provisioning/main/apks/lock.json",
    "heartbeatUrl": "https://raw.githubusercontent.com/andashi/provisioning/lock-heartbeat/heartbeat.json",
    "maxAgeDays": 14,
    "allowedHosts": ["raw.githubusercontent.com", "github.com",
                     "objects.githubusercontent.com", "release-assets.githubusercontent.com",
                     "dist.torproject.org", "archive.torproject.org", "f-droid.org",
                     "developers.yubico.com"]
  },
  "schedule": { "checkEveryHours": 6, "requireUnmetered": false, "requireCharging": false },
  "policy": {
    "minimumAgeHours": 0,                  // §11
    "constraints": { "notInCall": true, "notForeground": true, "boundHours": 24 }
  },
  "managed": [
    { "pkg": "org.torproject.torbrowser", "label": "Tor Browser",
      "signer": "20061f04…" },
    { "pkg": "org.torproject.android",    "label": "Orbot", "signer": "…" },
    { "pkg": "im.molly.app",              "label": "Molly", "signer": "…" }
  ],
  "checks": { "netFalse": ["org.andashi.home", "helium314.keyboard", "app.lawnchair.lawnicons"] }
}
```

Generated per zone from the catalog: `managed` is every app of that zone's
`profiles` with a source the lock covers (`obtainium`, `fdroid`,
`torproject`, `direct` - a vendor's own download directory with a detached
signature, provisioning#27) plus the updater itself; `signer` is
`apks/certs/<pkg>.cert`. `allowedHosts` is generated from a curated list in
the distribution's configuration, never from the lock's URLs, and `make
check` refuses a lock whose URLs leave that list: the lock must not be able
to widen where a phone connects (§10).
`checks.netFalse` is every `net: false` app of the zone. A hand-edited file
fails `make check` against the generator, as the launcher files do.

### 9.2 `state` (state provider, phone → host)

```jsonc
{
  "schemaVersion": 1, "zone": "anon", "updater": { "versionCode": 3, "installer": "org.andashi.updater" },
  "config": { "sha256": "…", "appliedAt": "2026-10-02T08:00:12Z" },
  "lock": { "generated": "2026-10-01T05:21:03Z", "fetchedAt": "…", "source": "fetch",
            "stale": false, "lastError": null },
  "lastCheck": "…", "lastCheckOk": "…",
  "apps": [
    { "pkg": "org.torproject.torbrowser", "state": "current", "installed": 2025090700,
      "locked": 2025090700, "installer": "org.andashi.updater", "updateOwner": "org.andashi.updater",
      "since": "…" },
    { "pkg": "im.molly.app", "state": "failed", "installed": 7000, "locked": 7010,
      "error": "sha256 mismatch from https://github.com/…", "attempts": 3, "nextTry": "…" }
  ],
  "foreign": [ { "pkg": "com.example.sideloaded", "installer": "dev.imranr.obtainium" } ],
  "checks": { "netFalse": { "ok": ["org.andashi.home"], "violations": ["helium314.keyboard"] } },
  "history": [ { "at": "…", "pkg": "…", "from": 7000, "to": 7010, "lock": "…", "result": "success" } ]
}
```

`diagnostics` answers "did you load exactly my config": the sha256 of the
last ingested `updater.json` and success or the error. The host compares
`config.sha256` before trusting anything else in `state`, the same two-query
pattern as the launcher.

### 9.3 Broadcasts

`<applicationId>.action.CHECK_NOW`, sent by the host to the receiver
component explicitly, per user. It enqueues a check and returns; the host
polls `state.lastCheck` until it moves. There is no "install this" broadcast:
what to install is decided by lock and policy, never by a command.

## 10. Network

- Two kinds of request and nothing else: the lock URL, and APK URLs from the
  lock. No GitHub API calls (release assets and raw files have no API rate
  limit, which matters through Tor), no telemetry, no version check against
  anything but the lock.
- `HttpURLConnection` on the platform TLS stack; Network Security Config:
  cleartext off, trust anchors `system` only. A user-installed CA (Lab) does
  not get to speak for GitHub.
- Hosts outside `allowedHosts` are refused, so a lock cannot make a phone
  outside Anon contact an arbitrary server and reveal its address.
- Redirects are followed only to allow-listed hosts (GitHub asset downloads
  redirect to `objects.githubusercontent.com` / `release-assets.githubusercontent.com`).
- In Anon everything goes through Orbot's lockdown VPN. Tor is slow and
  GitHub sometimes rate-limits exits; the retry budget is generous there and
  the lock's `urls[]` give the archive mirrors for Tor Browser.
- Every zone's traffic goes through that zone's VPN. The updater does not ask
  for, and could not get, a bypass.

## 11. Policy

- **When:** an update is due the moment the lock names a higher
  `versionCode`. The lock itself is at most a day behind upstream, and the
  requirement is bounded time for *patches*. `minimumAgeHours` exists for the
  owner who wants a window (§3.2, compromised upstream) and defaults to 0.
  Its clock is the moment this zone's updater first saw that `versionCode`
  in a valid lock (recorded per package), not the lock's `generated` or any
  upstream date the phone cannot verify; until the window has passed the
  package is `held`, with the time it opens, and nothing is fetched.
  Holding a version is the catalog's `release_tag`: the lock then does not
  move, and the updater has nothing to do.
- **How gently:** Android 14's install constraints
  (`setNotInCallRequired`, `setAppNotForegroundRequired`,
  `setAppNotInteractingRequired`) are used so Signal is not killed mid-call.
  They are bounded: after `boundHours` the updater commits regardless and
  says so in a notification, because an update that waits forever for an
  idle app is an unpatched app. *Measure* what the API does at timeout.
- **Downgrades:** refused, always, including when the lock goes back. `ahead`
  is reported; the host decides (uninstall and reinstall, or move the lock).
- **Signer change:** the pin is the host's. If the lock's signer differs from
  the pin, the package is `unverifiable` until the host pushes a new pin —
  which happens when the maintainer took a `review` lock after looking. If
  the APK's signer differs from the pin, it is refused before any session
  exists. Android would refuse it too, without lineage; the pin check is
  there so the refusal has our words and happens before bytes reach the
  package manager.
- **Lock freshness:** `generated` must be monotonic across fetches
  (rollback). `generated` only moves when the lock changes, so by itself a
  quiet fortnight upstream looks like a freeze. The freshness signal is
  therefore a separate **heartbeat** (proposed by the provisioning side,
  2026-10-02): the refresh workflow force-pushes a one-commit branch
  `lock-heartbeat` on *every* run, `unchanged` included, with
  `{checked, lockSha256, mainCommit}`. The updater fetches both; a heartbeat
  counts only if its `lockSha256` is the hash of the lock the updater holds,
  so an old lock cannot be paired with a fresh heartbeat. `maxAgeDays`
  against `checked` turns a silent refresh into a `stale` warning and a
  notification; a missing heartbeat is `unknown`, reported, not alarmed.
  Same trust as the lock, no history noise on `main`.
- **Silent-update throttle:** Android answers a second silent update of
  the same package by the same installer within a short window with
  `STATUS_PENDING_USER_ACTION` (measured 2026-10-10 on emulator-5562: two
  updates about 25 s apart, the second asked for confirmation). The updater
  does not work around it - it is the system's rate limit on exactly this
  power - and a lock moves a package a version a day, so it is only ever
  seen when a maintainer re-locks twice in a minute. The state then says
  `user-action` and the notification carries the tap.
- **Retry budget:** per package and lock version, exponential backoff, a
  failure notification after the third miss, then a daily retry until the
  lock moves.

## 12. The updater's own release, key and self-update

This app is the one whose compromise matters most, and it is the one whose
signing key the owner controls. Therefore:

- **A separate key, not the launcher's**, and **offline**: the release APK
  is signed on the owner's machine (hardware token where possible), never in
  CI. A CI-held key makes a CI compromise a malicious updater on every phone
  (§3.2, first row). Releases of this app are rare enough that a person at a
  keyboard is a price worth paying, and the launcher's own release workflow
  already documents the alternative with its risks (andashi/home#137).
- **Reproducible build:** CI builds the unsigned release from the tag with a
  pinned JDK, Gradle and AGP, twice on independent runners, and fails the
  release if the two differ. The owner rebuilds locally, compares with the
  CI artifact (zip entries outside the signing block byte for byte), and
  signs *that*. The release carries the SHA-256 of the unsigned and the
  signed APK, so anybody can repeat the comparison.
- **Pin provenance:** the certificate fingerprint is published in this
  repository's README, on andashi.org, and pinned in provisioning's
  `apks/certs/`. Three places that would all have to lie together.
- **Self-update** goes through the same path as every other app: the lock
  names the updater, the updater is its own installer of record (the host
  installed it with `-i <updater>`), the session is silent. The commit kills
  the process; the in-flight record is persisted before commit and
  reconciled from the package manager on the next start, so the history never
  loses the update that installed the version writing the history.
- **If the updater breaks itself** (a bad release that cannot run), the host
  path still works: `adb install -r -i <updater>` of the previous release —
  downgrade needs `-d` and is deliberate — and the host sees the breakage
  because the state provider stops answering.

## 13. Coexistence

| Installer | Keeps | Updater's stance |
|---|---|---|
| Obtainium | ad-hoc installs per zone (0012) | reported as `foreign`; must not own managed apps. Measured 2026-10-02: with another package as installer and update owner, an Obtainium update shows the plain system dialog "Update this app?" — no ownership wording — and one tap moves installer and ownership to Obtainium. So ownership buys one prompt, not a wall; the updater then reports `not-owner`, and the host repairs it with a same-version `-i` reinstall (§6). Keeping Obtainium out of managed apps for good is a provisioning choice: generate its import without them. |
| Accrescent | its apps | `foreign` |
| sandboxed Play | Play apps | `foreign`; Play updates them |
| GrapheneOS App Store / OS | system apps, Vanadium, camera | not in the managed set; the OS updates them |
| Work (managed profile) | Shelter's domain; its one lock-sourced app, RethinkDNS, is also in Cloud | no updater instance in Work; Cloud's instance updates the shared code |

A browser's sideloading per zone could be closed with the install appop
(`appops set --user N <browser> REQUEST_INSTALL_PACKAGES deny`); that is a
provisioning decision, noted here because the updater's report will show
what gets through.

## 13a. Your catalog, your lock

A phone provisioned from a private catalog (`CONFIG_DIR`, provisioning's
"your catalog, not the template") must not run against this project's lock:
the updater would report `unverifiable` for every app the template does not
know and `missing` for nothing it should, which is safe and useless. The
lock URL and the pins come from the host, so the answer is the host's:

- **A fork that runs the refresh.** The lock refresh is a workflow in the
  provisioning repository; a fork carries it and commits its own
  `apks/lock.json` on its own `main`, daily, with its own pins. The
  generated `updater.json` points `lock.url` (and `heartbeatUrl`) at that
  fork. Nothing in the updater knows which repository it is reading.
- **No fork, no CI: `host-only`.** The host already produces a verified
  lock for the first install (`make lock`, `make from-lock`); pushed through
  the ingest provider it is the lock the zone uses, and the phone updates
  from it as far as it goes. The price is the one 0002 names for this mode:
  updates arrive when the laptop does. `andashi status` says so.
- **Both.** A private catalog can mix: the template's apps against the
  template's lock would need one lock per source, which the config does not
  model. One zone, one lock. A catalog that wants apps outside its lock
  lists them with a source the updater does not manage, and the report
  names them as foreign.

What provisioning owes for this: the generator takes the lock URL from the
catalog's configuration rather than hard-coding this repository's, and
`make lock` is documented as the host-only path.

## 14. Reporting

- **On the phone:** the status activity, and notifications only for
  *action needed* (a system prompt waits, a `boundHours` commit is imminent)
  and *failure* (retry budget spent, lock stale or unverifiable, a signer
  disagreement, a package the updater does not own or that is missing from
  this user - each once, when the state is entered, because the host must
  act and the phone must say so). Success is silent; the status screen has
  the history.
- **On the host:** `andashi status` shows per zone `current / behind /
  failed / action needed / foreign`, the lock's age, and for stopped zones
  what `pm` says against the host inventory. `andashi status` is the one
  place where "is anything stale?" has an answer for the whole phone.
- **Never:** a success that the package manager did not confirm (§3.3).

## 15. Later scope, and how v1 keeps the door open

`checks` in the config and in the state is a map keyed by check name. v1
ships one check, `netFalse` (`PackageManager.checkPermission`, read-only),
because it is five lines and the first thing boundaries.md asked an on-device
companion for. Candidates for later, each its own decision with a price:
installer-of-record audit for *all* apps, permission drift against the
catalog's `perms`, VPN lockdown state per zone, a QS tile for the laptop door
(runtime-configuration.md). None of it is built now; the schema has the slot
and the state provider serves whatever is there.

## 16. Tech, tests, measurements

- **Kotlin**, `minSdk 36` like the launcher (the emulator runs Android 17 /
  API 37; the target follows what the toolchain supports at the first
  release), AndroidX WorkManager, `org.json` from the platform, no HTTP
  library, no DI framework, no analytics. Dependencies are the attack surface
  of a reproducible build; the fewer, the better.
- **`core/` has no Android import** and is tested with JUnit. Every decision
  gets its refusal cases: hash mismatch, signer mismatch, pin disagreement,
  downgrade, lock rollback, stale lock, unknown host, cleartext URL, body
  longer than `size`, package name mismatch, a package not in the managed
  set, a package not installed (no first install), `ahead`, `not-owner`.
- **Emulator e2e** (`e2e/`, bash, like provisioning's): a local HTTP server
  on the host serves a lock and APKs over `adb reverse`, so the test needs
  no internet and can serve the wrong bytes on purpose. Scenario: install an
  old version with `-i <updater>`, push a config, serve a lock with the new
  version, broadcast `CHECK_NOW`, wait for `state`, assert the package
  manager's `versionCode` — and the negative: serve a tampered APK, assert
  the old version stays and the state says why. Instance, snapshot and shell
  identity are asserted and pasted into the pull request.
- **Measured 2026-10-02** by the provisioning session on emulator-5562
  (Fold, snapshot `provisioned-0120`, Android 17 / API 37), every call as
  shell (uid 2000, asserted before and after):
  1. `adb install -r -i <pkg> --update-ownership --user 0`: success, no
     prompt, installer of record set; the flag is accepted but nothing on
     this build reads the owner back (§6).
  2. Same-`versionCode` reinstall with `-i`: installer moves from null to the
     named package, silently (Bitwarden; DAVx5 on 2026-09-30).
  3. `adb install -i <pkg> --user 14 <apk>` into stopped Lab: success, user
     stays stopped, data created on first unlock.
  4. `am start-user -w 13`: `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`,
     `USER_UNLOCKED` delivered to user 13; a WorkManager receiver got it.
  5. Obtainium updating an app owned by another installer: plain "Update
     this app?" dialog, one tap moves installer and ownership (§13).
  6. Shell install without `-i` resets the installer to null, no prompt (§6).
  7. `pm install-existing` carries the installer of record per package.
  8. `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and `ENFORCE_UPDATE_OWNERSHIP`
     are `normal` and granted to Obtainium, Accrescent and the GrapheneOS
     store; Play has neither.
- **The one assumption this design makes without proof:** a `dataSync`
  foreground service in a zone with VPN lockdown gets network on real
  hardware. The emulator cannot say (its background networking is unlike a
  phone's), and no path avoids the question. The fetch sits behind the
  `Fetcher` interface so that, should hardware say no, the trigger changes
  (a tap on the notification, or `host-only`) and the core does not. Until a
  Pixel has answered, every "works" in this document means "works on the
  emulator".
- **Measured 2026-10-09 with the probe** (emulator-5562, shell): the
  emulator's Wi-Fi is `PARTIAL_CONNECTIVITY` and never `VALIDATED`; TCP to
  the internet fails while ICMP answers. WorkManager's `CONNECTED`
  constraint therefore never holds on the emulator, and a job that waits
  for it waits forever. Every emulator e2e serves its files from the host
  over `adb reverse` and runs without the network constraint; the
  constraint itself is a hardware measurement. Also: inside a
  `BroadcastReceiver` the calling uid is the app's own, so receivers are
  gated by the manifest permission alone (§5.2).
- **Still to measure**, with the first test APK (shell identity asserted):
  1. A silent self-update as installer of record of oneself.
  2. Network from a `dataSync` foreground service in a VPN-lockdown zone.
  3. Install constraints: behaviour at timeout.
  4. `BOOT_COMPLETED` on a zone with a PIN: delivered at unlock, and the
     catch-up check runs then.

## 17. Decisions for the owner

Each with a recommendation; each becomes a decision record with its price.

0. **Application id and name.** Decided 2026-10-09: `org.andashi.updater`, label "Andashi Updater" (not to be mistaken for the OS update). Forever: signer continuity and installer of
   record are bound to it. The owner expects the scope to grow beyond
   updating. Recommendation: pick a noun broad enough for "the app that keeps
   the zones honest", in the `org.andashi.` namespace, now.
1. **Trust channel for the lock.** Recommendation: v1 fetches over HTTPS from
   the provisioning repo; pins come from the host; the lock is monotonic and
   age-checked; Android continuity is the floor. Not a signed lock in v1: a
   CI-held key signs what CI commits, a human-held key makes the daily
   refresh wait for a human, and either only re-states what continuity
   enforces. The interface keeps a verification slot so a two-key scheme (CI
   key for version moves, offline key for pin changes) can be added without
   touching the loop.
2. **`INTERNET` in every zone, Anon included.** Recommendation: yes, through
   each zone's VPN, Tor in Anon, with `mode: host-only` available per zone
   for whoever disagrees. The alternative is a Tor Browser that updates only
   when a laptop is plugged in. This contradicts the launcher's no-network
   stance and needs its own decision record: the price is one more app with
   network and install rights in the zone built on distrust, and the
   mitigation is §10's two-URL-kinds rule and the allowlist.
3. **Download sources.** Recommendation: the upstream URLs from the lock,
   proven at lock time, host-allow-listed on the phone. No mirror: nothing is
   conveyed by this project (0013), and a mirror is a service to run.
4. **Timing.** Recommendation: immediate, gentle through install
   constraints, bounded to 24 h. `minimumAgeHours` exists, defaults to 0.
5. **Stopped zones.** Recommendation: catch-up on zone start plus the host
   sweep; never start a zone for an update.
6. **The updater's key.** Recommendation: a separate offline key, releases
   signed by the owner over a reproducible CI build. The strongest single
   control in this document.
7. **v1 scope of checks.** Recommendation: `netFalse` only, because it is
   trivial and already asked for; everything else is a later decision.
8. **Tech.** Recommendation as in §16. The one real choice: reproducible
   build as a release gate from the first release, or added later. Later is
   never.

## 18. Asks to the provisioning side

- `-i <updater>` and `--update-ownership` in `10-apps.sh` and for
  `install-existing`; the updater installed first; the appop and the
  notification grant per zone, read back.
- A generator for `config/updater/<zone>.json` and a push step, mirroring
  `45-launcher-config.sh`.
- The `lock-heartbeat` branch (§11): force-pushed by the refresh workflow on
  every run, `{checked, lockSha256, mainCommit}`. Not a daily commit to
  `main`: `bin/pr-gate` refuses a pull request whose base is behind `main`,
  so that would cost every open pull request a rebase and a re-review a day.
- The device-idle allowlist entry for the updater, once per device, read
  back (§8). It is what lets the install run as a foreground service at all.
- `-i <updater>` on *every* install path, including `ensure_pinned_version`'s
  device-wide upgrade and whatever `andashi apply` and `make update` run
  through (§6: the shell strips the installer silently).
- Obtainium's generated import without the managed apps, so the one-tap
  takeover in §13 is not offered in the first place — in the *same* change
  as the `-i <updater>` installs, not before: until the updater is on the
  phone, Obtainium's one tap is the only way a managed app gets updated at
  all, and removing it earlier would leave those apps with no updater.
- `andashi status` and `andashi update` as in §5.3.
- The four measurements §16 still lists as open.
