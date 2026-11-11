# Symmetric NAT, tethering, and restrictive networks

This is a historical report from the combined development workspace at
`e04787750` plus its uncommitted fixes. Its test totals and live measurements
describe that combined build; they are not new live tests of this tooling
branch or any individual production PR. The standalone branches target
upstream `53002765a` and have separate isolated regression checks.
Source fingerprints are retained in [the provenance manifest](validation/followup-source-provenance.json).

For the subsequent installed encryption and filtered-network improvements, see
[the crypto follow-up](CRYPTO-FILTERED-NETWORKS.md).

Follow-up changes on 2026-11-11 build on the existing
[NAT traversal fixes](NAT-TUNNEL-RELIABILITY.md). USB tethering needs no separate
transport: the relevant conditions are the phone/carrier's address translation,
per-destination UDP mappings, firewall policy, and available IPv4/IPv6 routes.

## Changes

- **Preserve symmetric-NAT sessions.** Refresh each idle destination's UDP
  mapping even when one mapping preserves the listening port. Use the stored
  external endpoint when deciding whether a port is translated; published
  firewalled addresses may omit it. IPv6 keepalives no longer consume the IPv4
  keepalive budget, or vice versa. Keep existing timers and ordinary IPv4
  port-preserving optimization.
- **Select reachable tunnel neighbors while status is unknown.** Derive local
  inbound capability from published endpoints and introducers across every
  reachability status. Outbound-only routers use established adjacent sessions
  while bootstrapping or recovering connectivity. An enabled transport or an
  unknown status does not establish that unsolicited connections can arrive.
- **Avoid fresh hole punches for symmetric-NAT tunnel neighbors.** When known
  symmetric NAT leaves only IPv4 UDP inbound routes, use established adjacent
  sessions even when introducers are published. Independent TCP or IPv6 inbound
  routes retain normal selection, as do ordinary firewalled introducer routes.
  This is a conservative local selection policy; it does not disable relay
  processing or prevent other peers from attempting introductions.
- **Recover from lost token exchanges.** Expiring a TokenRequest, bad-token
  request, or Retry no longer blocks that endpoint for 39 minutes. Those states
  have not performed the expensive Noise handshake. Existing admission limits,
  blocklists, capacity checks, deadlines, and expensive-handshake timeout protection
  remain. Cleanup uses conditional map removal to preserve replacement attempts.
- **Keep alternate transports usable.** Temporary capacity failure no longer
  throws a null-pointer exception with DEBUG transport logging. Reject malformed
  NTCP2 handshake keys/IVs during address selection so a later valid address can
  be used. Any remaining bad NTCP address ban applies to NTCP, allowing SSU2
  fallback.

## Operating limits

Keep outgoing TCP and UDP enabled when both work. A router behind symmetric
NAT can build inbound and outbound I2P tunnels over sessions it initiates to
reachable neighbors; an inbound I2P tunnel does not require an unsolicited
incoming transport connection. Some pairs of NATs still cannot hole-punch a
direct UDP connection. The
[SSU2 specification](https://www.i2p.net/en/docs/specs/ssu2/) describes relay
processing, symmetric NAT source-port correction, and connection migration.

When UDP is blocked, outgoing NTCP2 requires a reachable peer TCP endpoint.
Neither arbitrary TCP nor arbitrary UDP is guaranteed to pass a restrictive
firewall. These changes do not add an HTTPS-shaped or pluggable transport and
have not been tested against China's censorship system. If every peer route is
blocked, changing tunnel selection alone cannot establish a connection.

Bootstrap reachability is separate from peer reachability. If reseeding is
blocked, the console's reseed configuration supports HTTPS/SOCKS proxying and
importing a reseed file. See the official
[console configuration guide](https://www.i2p.net/en/docs/guides/router-console-config/).
A reseed proxy only fetches initial peer information: this Java router's NIO
NTCP implementation does not route peer connections through the reseed proxy
or Java HTTP/SOCKS proxy properties.

## Reproducing verification

Run the full suite and build jars with JDK 17:

```sh
env JAVA_HOME=/path/to/jdk-17 ./gradlew --offline --no-daemon \
  :router:test test :router:jar :apps:streaming:jar
```

The new regressions cover keepalive selection, all local reachability states,
connected adjacent tunnel peers, cheap-handshake timeout recovery, preservation
of expensive-handshake protection, alternate TCP addresses, and transport
fallback. Four timeout/replacement tests reproduced failures before the expiry
fix; four reachability tests reproduced failures before the local mask fix.

The bounded live harness now also supports `ntcp2-only`, disabling UDP and
inbound TCP address publication in its disposable router:

```sh
python3 tests/live/run_live.py --java-home /path/to/jdk-17 \
  --runtime /tmp/i2p-ntcp-only/router --duration 360 \
  --mode ntcp2-only --skip-build
python3 tests/live/summarize_live.py /tmp/i2p-ntcp-only/router/metrics.jsonl
```

This exercises the outgoing TCP path on the available Internet connection. It
does not emulate national censorship, two symmetric NAT gateways, a physical
phone reconnect, or packet inspection. The harness's exit code records orderly
completion; inspect tunnel lengths and round-trip counters for connectivity.
The earlier [live NAT run](LIVE-NAT-VALIDATION.md) and
[installed build validation](NORMAL-INSTALL-VALIDATION.md) describe earlier
artifacts, not these follow-up changes.

## Completed verification and installation

The final build passed **553 JUnit tests**, including **178 router tests**, and
**46 Scala tests**; one existing Scala test remains pending. This follow-up adds
35 router regression/control tests. `git diff --check` passed.

Two disposable final-build runs completed and shut down normally. Both formed
two-hop exploratory tunnels in both directions. Counts include startup fallback
tunnels; timeout events can precede a later successful build and are not a
deduplicated request failure rate.

| Final-build observation | Normal TCP + UDP | Outgoing TCP only |
| --- | ---: | ---: |
| Sampled runtime | 195 s | 195 s |
| Automatic status | Symmetric NAT (`DIFFERENT`) | `OK` with UDP disabled |
| Maximum active peers | 12 | 6 |
| Maximum SSU2 peers | 10 | 0 |
| First simultaneous two-hop tunnels | 90 s | 30 s |
| Successful tunnel round trips | 16 | 8 |
| Successful build events | 8 | 10 |
| Rejected / timed-out build events | 3 / 38 | 1 / 8 |

These runs establish working paths and still show unsuccessful builds. They do
not establish a general improvement percentage. Earlier runs before the final
symmetric-NAT adjacency refinement are retained separately in the
[machine-readable report](validation/nat-followup-20261111-summary.json).
The final normal and TCP-only time series are also retained under `validation/`.
None of the four runs logged a null-pointer, array-index, missing-class,
out-of-memory, or illegal-argument exception.

The tested router jar SHA-256 is:

```text
b63cdb3e1ddd565eeef9b066da2c6fd8cd37fbc26ef177e31f3be7b84749b35e
```

This exact jar is now installed at `/home/kali/i2p/lib/router.jar`. The existing
service restarted and reported both Wrapper and Java `STARTED`. The installer
preserved the configuration and router identity. The previous jar and build
receipt are in `/tmp/i2p-nat-followup-20261111/installed/` as `router.jar.before`
and `UPDATED-BUILD.before.json`.

After application startup, the installed console reported **SymmetricNAT**, 5
client tunnels, 10 exploratory tunnels, 21 TCP connections and 13 UDP
connections. The console and web applications returned HTTP 200. A request to
`http://stats.i2p/` through `127.0.0.1:4444` returned HTTP 200 and the expected
site title in 1.74 seconds. The proxy was initially unavailable while the
application tunnels started; the later request verified recovery.

The SymmetricNAT status can remain because the upstream NAT is unchanged.
Working I2P tunnels do not require that status to become `OK`. Physical USB
reconnects and China's censorship system remain untested.
