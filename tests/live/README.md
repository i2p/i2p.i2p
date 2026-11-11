# Live router validation

Build and run the unit suite first with a compatible JDK:

```sh
env JAVA_HOME=/path/to/jdk-17 ./gradlew --offline --no-daemon :router:test test :router:jar :apps:streaming:jar
```

Launch an isolated, finite router on a Linux host with internet access:

```sh
python3 tests/live/run_live.py --java-home /path/to/jdk-17 \
  --runtime /tmp/i2p-live-test/router --duration 720 --mode ssu2-only --skip-build
```

The launcher copies the built core, router, and streaming jars to a private
artifact directory, records their SHA-256 hashes, and compiles the harness
against that snapshot. It prints the artifact and runtime paths. It retains
console output in the artifact directory and metrics in `RUNTIME/metrics.jsonl`.
The runtime uses a separate disposable router identity, no external I2CP
listener or client applications, no UPnP, no participating tunnels, and
96 KiB/s bandwidth limits in both directions. The default transport port is
24741; use `--port` to select another available port.

Use `--core-jar /path/to/staged/i2p.jar` when validating a packaged core jar
that retains bundled runtime dependencies. Its bytes are copied and hashed in
the same way as the default build jar.

Modes:

- `ssu2-only`: disables NTCP and forces introducer publication, exercising UDP
  connections and the firewalled bootstrap path.
- `firewalled`: forces introducers while allowing outgoing NTCP as well as UDP.
- `normal`: allows the router to discover its network status normally.
- `ntcp2-only`: disables UDP and inbound TCP address publication, exercising
  tunnel building entirely over outgoing TCP connections. This models the
  transport options available when a firewall blocks UDP; it does not emulate
  packet inspection or a national censorship system.

The harness samples every 15 seconds, records exploratory tunnel lengths,
build statistics and successful tunnel round trips, and attempts at most twelve
connections to live peers that advertise valid SSU2 introducers. Each probe
sends a normal router-info lookup; connection success is recorded separately
from message-send callbacks. To stop early, create `RUNTIME/STOP`. Shutdown
releases the test router's sockets and persists its disposable state.

Summarize the metrics without printing peer or router identities:

```sh
python3 tests/live/summarize_live.py /tmp/i2p-live-test/router/metrics.jsonl
```

Tunnel lengths in samples count remote hops, so `[2, 2]` represents two tunnels
with two remote hops each. Build totals include bootstrap activity; successful
nonzero tunnels and round trips provide the evidence of actual forwarding over
the live network. A timed-out build can subsequently succeed, so the percentage
of successful outcome events does not deduplicate individual build requests.
The packet-level regression suite separately covers precise
arrival-order, authentication, truncated-packet and source-port correction
cases that a live network run may not encounter. A run on one host does not
establish results for every NAT type or for unavailable IPv6 connectivity.
