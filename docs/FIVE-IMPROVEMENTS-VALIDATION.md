# PR validation, parser safety, and transport recovery

This report records a local combined validation of five companion changes and
existing production fixes. This branch adds controlled recovery tooling and
evidence. Parser fixes, PR CI infrastructure and public live connectivity
verification are published separately; the combined test totals below are not
results for this tooling branch alone.
The [PR workflow](../.github/workflows/regression-tests.yml) runs JDK 17 tests
and controlled recovery comparisons on every pull request, on pushes,
and on manual dispatch. It retains reports even on failure with read-only
repository permissions.

The [validation runner](../tests/validation/README.md) replaces machine-specific
scripts under `/tmp`. It runs clean Java/Scala tests, validation-tool Python
tests, and records source hashes, command statuses, XML and HTML test reports.
It also supports clean revision comparisons and combined-branch validation.

Malformed IRC `PRIVMSG`, `NOTICE`, tagged commands and `PART` lines are dropped
before accessing missing fields. CTCP messages require a complete frame. The
regressions exercise the actual inbound and outbound filter loops, feeding bad
lines followed by a valid line and checking that the valid line is forwarded.
Existing DCC behavior remains covered by `IRCFilterTest`.

Query decoding treats percent escapes as UTF-8 bytes, rejects malformed escapes
and invalid UTF-8, and preserves literal plus signs used in helper destinations
and credentials. Both local submission handlers finish decoding all parameters
before changing the address book or blinding credentials; invalid queries return
HTTP 400. Tests cover international text, supplementary Unicode characters,
existing encoded helper links, and a valid submission followed by a malformed
trailing parameter. Remote queries unrelated to address helpers retain their
original bytes.

The [live launcher](https://github.com/i2p/i2p.i2p/pull/218) defaults to a connectivity verdict:
successful tunnel round trips, simultaneous two-hop tunnels in both directions,
the complete requested duration, matching loaded artifact hashes, successful
process exit, and completed router shutdown. Observation mode explicitly records
that connectivity was not verified. Malformed metrics, early stops, crashes,
timeouts and incomplete shutdowns fail connectivity verification.

The [controlled recovery lab](../tests/recovery/README.md) tests real encrypted
transport sessions under packet loss/reordering, UDP blocking, mapping expiry,
port changes and reconnects. The same frozen harness is compiled against an
unchanged upstream commit and the candidate source snapshot. Each scenario
records recovery time, injected packet faults, negotiated transport versions,
peer migration and remaining establishment/session indexes. Expectations follow
the [SSU2 specification](https://i2p.net/en/docs/specs/ssu2/); TCP fallback is
checked separately as transport-selection behavior.

CI compares failure reasons with the unchanged base so pre-existing upstream
failures do not block unrelated pull requests. Such cases remain failed in the
report (`candidatePassed: false`). Missing or invalid measurements, unobserved
faults and new failures make CI fail. Default local verification requires every
candidate case to pass.

The measurements cover a loopback IPv4 lab and one public-network host. They do
not establish results for other NAT devices, IPv6 networks or censorship systems.

## Recorded validation on 2026-11-11

The clean Temurin JDK 17.0.20.1 run passed 618 JUnit tests, 46 Scala tests and
34 Python tests. One existing Scala case remains pending. The new parser tests
account for 18 JUnit cases; the 13 existing IRC/DCC cases also pass. Complete
logs, XML and HTML reports remain under `build/validation` and are published as
artifacts by the PR workflow.

The fresh 360-second normal-mode public run passed all connectivity checks:
33 successful round trips, 24 distinct samples with two-hop tunnels in both
directions, matching loaded core/router artifact hashes, a zero process exit,
and confirmed router shutdown. The final sample was at 361 seconds. The
[summary](validation/five-improvements-20261111-summary.json),
[metrics](validation/five-improvements-20261111-live-metrics.jsonl) and
[portable launch manifest](validation/five-improvements-20261111-live-launch.json)
are retained in the repository. Recheck them without starting a router:

```sh
python3 tests/live/verify_live.py \
  docs/validation/five-improvements-20261111-live-metrics.jsonl \
  --process-exit-code 0 \
  --launch-manifest docs/validation/five-improvements-20261111-live-launch.json
```

The controlled comparison used unchanged upstream commit
`53002765a300547a2c663ac3c8d50a1b7c56cf1e` (2026-11-05) and a snapshot of the
current combined working sources. The frozen harness and external dependency
hashes match across both variants. All 12 candidate runs passed, across six
scenarios and two seeds. Every candidate run ended with zero pending
establishments and zero inbound establishment bans, two UDP sessions, two
connection-ID index entries and two endpoint index entries. UDP-blocked runs
also confirmed working TCP connections at both endpoints.

| Scenario | Upstream recovery (seconds) | Candidate recovery (seconds) |
| --- | --- | --- |
| Control | 0.010–0.023 | 0.010–0.011 |
| Packet loss and reordering | 1.876–12.078 | 0.025–5.044 |
| NAT port change | >45, timed out in both runs | 0.021 in both runs |
| Expired mapping | >45, timed out in both runs | 6.028–6.033 |
| Reconnect | 0.021–0.042 | 0.035–0.180 |
| UDP blocked, TCP fallback | 10.147–10.154 | 10.042–10.048 |

Recovery time starts at fault injection and ends at the first measured
request/reply. A second request/reply must also succeed. The loopback runs
negotiated SSU2 hybrid version 3; they do not cover every supported variant.
The table reports two observations per case, with generated identities and
normal thread scheduling; it is not a throughput benchmark. Port migration and
expired-mapping runs retained their connection IDs after successful migration.
The [complete recovery report](validation/five-improvements-20261111-recovery.json)
records each result and source fingerprint. Reproduce it with the runner in
`tests/recovery`, using the pinned baseline above or another explicitly fetched
upstream commit.

## Reproducible candidate and CI publication checks

The exact measured core/router sources are archived in fork branch
`validation/recovery-candidate-20261111`, commit `828e8c31f5cb3e62fc7afa2f4377bcae40b54fb1`.
Its Java-source fingerprint matches the recorded candidate exactly. Use this
commit with `--candidate-ref` when reproducing the strict recovery comparison;
the archive combines the existing production fixes and is separate from the
focused tooling and parser pull requests.

The isolated CI branch passed 409 JUnit, 46 Scala and six validation-tool tests
(one Scala case remains pending). The recovery-gate suite now has 11 Python
tests. The standalone live Java harness also compiles against the clean
unchanged-upstream core/router classes.

A real same-source comparison of the upstream port-migration case failed
functionally in both variants while the regression gate exited zero and retained
`candidatePassed: false`. The [gate report](validation/recovery-ci-gate-20261111.json)
records this distinction. Missing measurements and new failures are separately
covered by the gate tests.

## Recovery probing under packet loss

SSU2 permits individual I2NP messages to expire while the connection remains
usable. The recovery harness now sends bounded fresh numbered probes, requires
a second independent successful request/reply, and measures packet-loss recovery
after a three-second fault window. The 45-second deadline and session/index
assertions remain in force. Four deterministic Java checks cover lost and late
replies and the request/deadline bounds.

The [first CI comparison](validation/recovery-ci-first-attempt-20261111.json)
retains a one-probe timeout on identical production sources. Historical timing
measurements above used the earlier frozen harness (`af2a678da60d88f3a0f668f3131986779c629eacbb9c8461e524ea0cf8c82e41`),
retained in commit `f52f20ea23698dd4b3d01f9f9b2574b558b52928`. They were not
rewritten to match the revised probe policy. The updated CI artifacts record
their own harness hash, probe counts and injected loss window.

The revised [local probe matrix](validation/recovery-probe-matrix-20261111.json)
passed all 16 packet-loss and UDP-blocking runs across four seeds and both
source variants. Both compiled variants also passed the four deterministic
Java probe tests. This matrix supplements the unchanged historical timing
report above.

An updated [strict comparison](validation/recovery-updated-strict-20261111.json)
with the bounded-probe harness at `01afbd402e3876aff512517084a4cc0facf6dcaf` passed
all six scenarios against the exact archived production candidate. Upstream
continued to time out on port migration and expired mappings. A fifth Java
probe check now also verifies rejection of replies observed beyond the deadline.
