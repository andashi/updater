# Decisions

Each decision names its price. The format is provisioning's.

| | |
|---|---|
| [0001](0001-the-lock-is-fetched-the-pins-are-pushed.md) | the lock is fetched over HTTPS, the signer pins are pushed by the host |
| [0002](0002-network-in-every-zone-anon-through-tor.md) | the updater has network in every zone, Anon through Tor, `host-only` as the escape |
| [0003](0003-the-release-key-is-offline.md) | superseded by 0004: the release key never enters CI |
| [0004](0004-the-release-key-lives-in-an-approved-environment.md) | the release key is an environment secret only the owner's Approve unlocks; CI builds twice and signs only when both agree |
