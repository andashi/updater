# 0002 — Network in every zone, Anon through Tor

**Status:** accepted (2026-10-09)

## Context

The launcher has no `INTERNET` permission, on purpose: an app that
configures the phone without network is the strongest statement available.
The updater cannot make it: it has to fetch the lock and the APKs. Orbot,
Molly and Tor Browser live only in Anon, the zone built on distrust, whose
traffic may leave only through Tor; no other zone updates them. The phone is
almost never at the laptop.

## Decision

The updater holds `INTERNET` in every zone it is installed in, Anon
included, where every byte goes through Orbot's lockdown VPN like every
other app's. A zone can be set to `host-only` in its config, in which case
its instance fetches nothing and the host sweeps it over adb.

## Consequences

- The price: one more app with network and the right to install in the
  zone whose whole purpose is distrust. It is bounded by what the app does
  with the network: two kinds of URL (the lock, APKs named by the lock), an
  allowlist of hosts, no API calls, no telemetry, and every byte checked
  against a hash the lock carries and a signer the host pinned.
- The updater never asks for a VPN bypass, and must never get one: in Anon
  that would be traffic past Tor. If Tor does not reach GitHub, the answer
  is the lock's archive mirrors, then a loud failure, then `host-only`.
- Tor is slow and sometimes blocked by exits; Anon's retry budget is
  generous, and Anon updates when Anon runs, which is when it matters.
