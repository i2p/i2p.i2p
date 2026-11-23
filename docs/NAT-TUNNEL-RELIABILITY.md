# NAT traversal and tunnel creation reliability

These changes address failures in SSU2 introduction and tunnel peer selection
when a router is firewalled, its UDP port is translated, or only some transports
are available.

## Connection establishment

- Inspect every advertised introducer before deciding that none is valid. An
  expired first entry no longer hides a valid later entry.
- Keep introducer lookups, connections, and relay responses pending while they
  can still complete. Reuse an existing connection attempt to an introducer
  instead of marking it rejected.
- Poll asynchronous introduction work without increasing the relay retry
  backoff. Keep the existing 15-second introduction deadline and 25-second total
  establishment limit.
- Use the stored external UDP endpoint in signed relay requests and responses.
  The local socket port may differ after NAT, and IPv4 and IPv6 can use different
  ports. Prefer the stored endpoint because a firewalled router's published
  address may omit its port.
- Apply IPv4 symmetric-NAT restrictions to IPv4. They no longer disable IPv6
  introduction or optional IPv6 handshake tokens. Capacity checks still apply.
- Refresh the keepalive window when reusing a published introducer, verify its
  identity and connection family, and respect the requested introducer count.
- Retain the introduction nonce until a validated hole punch, handshake
  completion, or timeout. A hole punch can correct the reported UDP source port
  after a relay response, including while a SessionRequest is awaiting a reply.
  Preserve the Noise state and encrypted retransmission bytes, and freeze the
  endpoint after SessionCreated authentication.
- Reject malformed or unauthenticated introduction packets before consuming
  their nonce or changing the target's state. Conditional map updates prevent
  a corrected endpoint from replacing another connection attempt.
- Treat negotiated handshake versions 2, 3, and 4 as the SSU2 transport family
  during relaying and connection-ID registration, replacement, and cleanup.
  Signed relay bodies continue to use their specified version 2 format.
- Validate decoded introduction-key lengths and peer-test block lengths before
  constructing keys or extracting hashes and IP addresses.

SSU2's signed relay messages communicate the endpoints used for hole punching;
the protocol also describes the limitations of symmetric NAT and the use of
the hole punch's observed source port. See the
[SSU2 relay specification](https://www.i2p.net/en/docs/specs/ssu2/).

## Tunnel peer selection

- Distinguish the transports a peer can initiate from addresses that accept
  incoming connections. Outbound-only NTCP2/SSU2 addresses no longer count as
  dialable destinations. Introducer routes and established sessions remain
  usable.
- Include SSU2 when inferring IPv4 outbound support for a hidden router.
- Select an established adjacent peer when the local router has no advertised
  inbound route, including the period before it obtains introducers. Apply the
  existing bootstrap reply-path restrictions in this case too.
- Apply adjacent-hop compatibility filters before exploratory peer selection
  when NTCP or SSU is disabled, as already done for IPv6-only configurations.

## Verification

Run the regression tests and repository unit tests with a compatible JDK:

```sh
env JAVA_HOME=/path/to/jdk-17 ./gradlew --offline --no-daemon :router:test test
```

The router test task now includes tunnel-pool regressions. The old standalone
build-message experiment remains excluded, and its incorrect source exclusion
path is corrected.

Tests cover translated relay ports, signed relay processing, IPv4/IPv6
separation, introducer reuse, asynchronous establishment and its deadlines,
directional transport compatibility, and connected-peer selection behind a
firewall. These tests exercise local protocol and selection behavior. They do
not measure live tunnel build success rates or prove traversal across every
NAT implementation.

Validation on 2026-11-11 with OpenJDK 17.0.20.1: **518 JUnit tests and 46
Scala tests passed**, including 81 new router regression/control tests. ScalaTest
reports one existing pending test. The router suite contains 143 passing tests.
For the initial set of 45 regressions, an isolated comparison with the original
establishment manager, introduction manager, connectivity checker and peer
selectors reproduced 36 failures. The expanded suite passes with the updated
implementations. `git diff --check` passed.

The follow-up regression suite covers both relay/hole-punch arrival orders,
authenticated source-port corrections, duplicates, endpoint collisions, late
rejections, nonce cleanup, malformed peer-test packets, and SSU2 versions 2–4.
The finite live-network harness and reproduction commands are in
[tests/live/README.md](../tests/live/README.md).

The completed UDP-only live run established two introducer-dependent peer
connections and completed 74 tunnel round trips, including tunnel renewal with
two-hop pools in both directions. See the
[live validation report](LIVE-NAT-VALIDATION.md) for the artifact hashes,
measurements, unsuccessful attempts, and testing limits.
