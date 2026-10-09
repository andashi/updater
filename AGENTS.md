# Working agreements for AI agents

Read [docs/design.md](docs/design.md) before changing anything. The rules
below are the ones andashi/provisioning and andashi/home work by; they are
copied here so that this repository cannot drift from them. The recurring
defect all three guard against is a step that reports success for work it
did not do.

## Language

**All code and all documentation in English.** Identifiers, comments, commit
messages, docs, issues, pull requests. Chat with the owner may be German;
artifacts never are.

## Commits

No AI attribution in commits: no `Co-Authored-By` naming a tool, no
`Claude-Session:`, no "Generated with" footer. A commit message reads like one a
human engineer wrote. Some tools instruct the opposite, so it is enforced twice:

```bash
git config core.hooksPath .githooks     # once per clone
```

`.githooks/commit-msg` rejects the commit before it exists, and
`.github/workflows/commit-hygiene.yml` checks every commit of a pull request.
Both are taken over from andashi/provisioning unchanged
(`tests/commit-msg.test.sh` proves the hook).

## Pull requests

Everything reaches `main` through a pull request reviewed by CodeRabbit. Large
work goes up as a **stack** (`gh stack`), one reviewable layer per pull
request, each with its own tests, merged bottom-up through a pr-gate like
provisioning's `bin/pr-gate`: full review of base..head, no open threads,
bot-resolved threads read by a person, green checks including `main`,
merge-async pinned to the head, merged branch deleted.

**Refer to an issue with `Refs #N`** unless the pull request really closes it:
GitHub's closing-keyword parser ignores negation.

## Code

Secure, clean, documented code is the top priority. Comments explain *why*.
A measurement is recorded with date, instance and the identity it ran under
(`shell` or `root`); production GrapheneOS has no root, so a result obtained
as root proves nothing about the phone. A step never reports success it did
not verify: an update is current when the package manager says so, not when
the session was committed (design §3.3).

## Tests

- **Unit** (`core/`, no Android import): every decision gets its refusal
  cases, in the same change as the decision.
- **Emulator e2e** (`e2e/`): against a GrapheneOS emulator instance of
  andashi/provisioning, never in CI. Acquire the lock first
  (`emulator/device-lock.sh acquire <owner> <serial>`), assert the shell
  identity, and paste instance, snapshot and result into the pull request.
  `pm grant`, `adb root` and `setprop` are announced to the owner before they
  run.

Screenshots, dumps and logs are evidence for a pull request or an issue,
never files in the repository.
