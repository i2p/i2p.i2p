# Live NAT and tunnel validation — 11 November 2026

The recorded run used the combined development workspace at `e04787750` plus
the networking fixes, reporting I2P 2.14.0. The separately submitted fix
branches target upstream `53002765a` (2.14.1) and were compiled and regression
tested independently. The live measurements describe the combined build,
rather than a separate live run of each standalone PR.

The patched router completed a live I2P run using UDP only, with introducers
forced and direct address publication disabled. It established connections
through introducers, built tunnels in both directions, passed tunnel round-trip
tests, and renewed into two-hop tunnels. The process exited with code 0, and a
host process check confirmed that the test JVM had stopped.

The run used the local host's private IPv4 network and default NAT gateway,
a separate disposable router identity, no client listeners or applications,
UPnP disabled, zero participating tunnels, and 96 KiB/s bandwidth limits in each
direction. The launcher copied all four built core/router/streaming jars to an
immutable artifact directory and recorded their hashes before starting Java.

The sampled window was **00:02:28–00:14:44 SAST on 11 November 2026**
(22:02:28–22:14:44 UTC on 10 November), approximately **735 seconds**.

| Observation | Result |
| --- | ---: |
| Maximum simultaneous SSU2 peers | 21 |
| Maximum active peers | 18 |
| Known router infos | 202 |
| Published introducers | 3 |
| Available introducer tags | 15 |
| First simultaneous inbound/outbound two-hop tunnels | 255 seconds |
| Samples with two-hop tunnels in both directions | 33 |
| Successful exploratory build events | 18 |
| Rejected / timed-out build events | 2 / 13 |
| Successful tunnel round trips | 74 |
| Average successful tunnel round-trip time | 680.5 ms |
| Introducer-dependent peer probes | 4 |
| Probes establishing UDP and sending their lookup | 2 |
| Outbound / inbound UDP establishment events | 30 / 2 |
| Allocated inbound / outbound bytes | 471,012 / 503,000 |

At the final sample, every remaining nonzero exploratory tunnel had two remote
hops. The early startup pools included one-hop and zero-hop fallback tunnels.
Build event totals include that startup activity. A build can time out and later
succeed, so those counters do not provide a deduplicated request success rate.

The two unsuccessful introduction probes had observable outcomes: one received
remote refusals for an invalid/stale introducer tag (code 5) and an address
condition (code 65); another timed out. The successful probes reached
`OB_STATE_CONFIRMED_COMPLETELY`, retained an established UDP session, and sent
their router-info lookup. Diagnostics recorded three accepted relay responses
and completed handshakes using negotiated SSU2 versions 2 and 4.

There were zero occurrences of `NullPointerException`,
`ArrayIndexOutOfBoundsException`, `ClassCastException`, `OutOfMemoryError`,
`IllegalArgumentException`, or `NoClassDefFoundError` in the router logs.
Shutdown logged the expected asynchronous socket-close exception as the UDP
receiver stopped, followed by `Shutdown(2) complete`.

The exact tested router jar SHA-256 is:

```text
fdcbc6e4be6a3e72da0dd07559b8c2cf76e0b4db0407a0e25a0919d164318a2f
```

The copied jar and repository build jar still matched after the run. All jar
hashes, unit-test counts, and aggregate diagnostics are retained in the
[machine-readable summary](validation/nat-live-20261111-summary.json).
The [identity-free time series](validation/nat-live-20261111-metrics.jsonl)
preserves every sample. Private router keys and detailed peer logs remain in
`/tmp/i2p-live-20261111/router`; they are outside the repository.

## Regression verification and scope

The full repository suite passed **518 JUnit tests and 46 Scala tests**,
including 143 router tests. There is one existing pending Scala test. The NAT
and tunnel work adds 81 router regression/control tests, including 20
signed/encrypted introduction ordering tests. `git diff --check` passed.

This live run verifies Internet connectivity, introducer-assisted establishment,
tunnel forwarding, and renewal for this host and build. The automatic
reachability status remained `UNKNOWN` throughout the forced-introducer run;
automatic NAT classification was not established by this experiment. No IPv6
peer sessions or naturally occurring hole-punch source-port correction were
observed. Those paths, the exact arrival-order races, and malformed packets are
covered by the regression tests. The results provide no before/after throughput
comparison or guarantee across every NAT implementation. Native jbigi was
unavailable, so the run used the Java fallback.

Reproduction commands and bounded-run settings are in
[tests/live/README.md](../tests/live/README.md). Implementation details are in
[NAT-TUNNEL-RELIABILITY.md](NAT-TUNNEL-RELIABILITY.md).
