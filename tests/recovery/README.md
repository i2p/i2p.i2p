# Controlled transport recovery

Run with JDK 17, Python 3.12 or later, Git, and permission to open local TCP/UDP
sockets. The test starts two disposable routers on private network ID 222,
binds their transport listeners to loopback, disables reseeding, and exchanges
their signed RouterInfos directly. Each router has its own JVM. A UDP proxy
injects faults into real encrypted SSU2 traffic. The overlay services are dummy
implementations; this measures transport recovery, not tunnel performance.

Fetch an unchanged upstream revision, then compare it with the current source
tree, including uncommitted and new Java files:

```sh
git fetch https://github.com/i2p/i2p.i2p.git master
python3 tests/recovery/run_recovery.py --baseline-ref FETCH_HEAD \
  --java-home /path/to/jdk-17 --output build/recovery-run
```

The baseline is always extracted from a Git commit and is never patched. Use
`--candidate-ref COMMIT` to test a committed candidate instead of the default
`WORKTREE`. `--baseline-repo PATH` selects an existing local repository that
contains the baseline. Every run compiles separate core/router source snapshots
with the same harness and checksum-pinned external libraries. `--offline` uses
the existing Gradle dependency cache. No installed router, prebuilt project jars,
root privileges, firewall changes, containers, or scripts under `/tmp` are needed.

The default runs all six scenarios with seeds 1 and 2. A seed determines the
packet-drop schedule; generated router identities and thread scheduling vary.
Select a smaller run with `--scenarios reconnect udp-blocked --seeds 1`.

| Scenario | Injected fault | Required evidence |
| --- | --- | --- |
| `control` | None | Authenticated request/reply and another successful request/reply |
| `packet-loss-reordering` | Three-second burst dropping approximately one packet in five, with reordering; then restores the link | Observed drops and reordering, followed by successful request/reply |
| `nat-port-change` | Changes the proxy's public source port during an established session | Peer endpoint changes, connection IDs remain, and request/reply succeeds |
| `expired-mapping` | Drops traffic sent to the old mapping and allocates a new public source port | An observed packet to the expired mapping, migration and successful request/reply |
| `reconnect` | Removes both established UDP peer sessions | A fresh working SSU2 session |
| `udp-blocked` | Drops all UDP after a working SSU2 session | Observed UDP loss and successful request/reply through NTCP2 fallback |

Each scenario verifies the controlled SSU2 path before injecting faults and
allows 45 seconds for a request/reply after injection. `recoveryMs` measures
from fault injection to the first measured request/reply, including any fault
setup or burst submission. Fresh numbered requests are attempted at most once
per second until an authenticated reply arrives or the deadline expires. A
second independently numbered request/reply must also succeed. The
45-second bound is a test budget, not a protocol requirement. Both endpoints must finish with no
pending establishments or inbound establishment bans, and their connection-ID
and endpoint indexes must not outnumber the remaining UDP sessions. The lab records
negotiated versions, including the SSU2 hybrid variants supported by the sources.
It emits success only after both router JVMs confirm shutdown and exit zero.
The runner terminates the controller and its child process group on timeout.

The expected migration, retransmission, and session replacement behavior comes
from the [SSU2 specification](https://i2p.net/en/docs/specs/ssu2/), especially
"Connection Migration", "Retransmission", and "Multiple Sessions". Migration
validates the new endpoint using the current session's keys and connection IDs.
NTCP2 fallback is an I2P transport-selection expectation, outside the SSU2
protocol itself.

The specification permits an individual I2NP message to be lost without closing
the session. Bounded fresh probes distinguish a lost message from a connection
that cannot recover. Four deterministic Java checks cover immediate replies,
lost first requests, late replies and deadlines before either variant starts
network scenarios. Reports include the probe request count and injected loss
window. These checks keep the 45-second deadline and all state assertions.

`report.json` records the two source revisions, whether candidate sources came
from the working tree, source/harness/dependency SHA-256 hashes, per-scenario
recovery times and residual state, and a paired comparison. A baseline failure
is reported without hiding a candidate improvement. Any candidate failure,
malformed/missing result, wrong run identity, abnormal exit, or timeout makes
the runner exit nonzero. Compilation failures also exit nonzero and retain the
compiler log.

PR CI uses `--gate regressions`: it fails for any new failure reason relative to
the unchanged base. Existing baseline failures remain recorded with
`candidatePassed: false`; a green comparison reports `noRegressions: true`
without claiming that every candidate case passed. Missing measurements,
crashes, invalid results, unobserved injected faults, and incomplete scenario
pairs fail this gate too. The default `--gate candidate` continues to require
every candidate scenario to pass.

Use a new output directory for each run. It is private because the runtime
subdirectories contain disposable router identities and local diagnostic logs.
Only `report.json`, compiler/probe-test logs, controller `process.log` and scenario `result.json` files are uploaded
by the PR workflow. The lab cannot establish behavior across real NAT devices,
IPv6 networks, public-network congestion, or censorship systems. Public-network
two-hop tunnel verification is provided separately by `tests/live/run_live.py`.
