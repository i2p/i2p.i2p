# Encryption hardening and filtered-network recovery

This is a historical report from the combined development workspace at
`e04787750` plus its uncommitted fixes. Its test totals and live measurements
describe that combined build; they are not new live tests of this tooling
branch or any individual production PR. The standalone branches target
upstream `53002765a` and have separate isolated regression checks.
Source fingerprints are retained in [the provenance manifest](validation/followup-source-provenance.json).

Changes made on 2026-11-11 preserve the existing NTCP2 and SSU2 wire protocols.
No new cipher suites, key lengths, packet fields, or padding negotiation are
introduced. The implementation continues to use existing ChaCha20-Poly1305 and
supported X25519/ML-KEM hybrid modes.

## Filtered-network behavior

- Replayed and all-zero NTCP2 message-1 prefixes now use the existing bounded,
  randomized no-response drain used for authentication failures, rather than
  deterministic immediate rejection. The drain consumes coalesced and fragmented
  input correctly, retains existing handshake timeouts and connection limits,
  and does not perform further cryptographic work. Existing IP-block policy is
  retained. This reduces probing signals; failure detection still happens at
  different offsets, so it does not make every invalid connection identical.
- A router with no active peer connections no longer globally bans a remote
  peer merely because local transports produce no bid. Failed-message callbacks
  still run, and existing peer rejection remains when other peers are reachable.
  This avoids retaining new peer bans through a local outage or startup.
- HTTPS reseeding now enforces the existing decoded-data limits: 1 MiB for
  bundles and 2 MiB for in-memory responses, including gzip expansion. HTTP and
  HTTPS fetches use the existing seven-minute per-host deadline, preventing an
  indefinite trickle from holding up subsequent hosts. Failed partial downloads
  are removed. TLS verification, signed SU3 verification, proxy authentication,
  and SSL session reuse are preserved.

The [NTCP2 specification](https://www.i2p.net/en/docs/specs/ntcp2/) describes
randomized failure handling and probing resistance. These changes do not add
an HTTPS disguise, an external proxy route, or a guaranteed way around China's
firewall. At least one reachable bootstrap path and peer route is still needed.

## Encryption protection and compatibility

- Cipher and Noise symmetric-state diagnostics no longer expose authentication
  key material or the chaining key. Debug-only capture of the encryption key is
  removed as well.
- Poly1305 clones now own independent copies of every mutable buffer. Destroying
  or advancing a cloned handshake branch cannot erase or modify the original
  branch's authentication state.
- Cipher inputs and associated-data ranges are validated before writes or state
  changes. Destroyed cipher states reject further use. Packet authentication
  still completes before any plaintext is released, and the packet nonce only
  advances after success. SSU2's explicit nonce setting for packet reordering
  remains supported.
- Version parsing matches complete advertised tokens. Values such as `12`,
  `14`, or `34` cannot masquerade as versions `2`, `4`, or `3`. NTCP2 accepts its
  supported base version regardless of position in an advertised version list.
- SSU2 selects the existing preferred ML-KEM-768 hybrid when supported and when
  both path MTUs permit it. IPv6 introducer addresses with no host now use their
  IPv6 capabilities when applying the MTU threshold. Advertised smaller hybrid
  or base modes remain available under their existing rules. Malformed keys,
  unsupported versions, or unusable MTUs no longer hide a later valid address.

This hardens the implementation rather than increasing mathematical key sizes.
See the existing [SSU2 hybrid specification](https://www.i2p.net/en/docs/specs/ssu2-hybrid/)
for the version mapping and MTU requirements.

## Measured CPU work

ChaCha20 computed its initial block twice before encrypting the first block.
Removing the unused first computation preserves the counter sequence and exact
ciphertext. A standalone benchmark compiles baseline and updated sources into
separate directories and runs each size/implementation in a fresh JDK 17 JVM
with a 128 MiB heap. Results are medians of seven measured batches after three
warmup batches. Each batch uses `max(20000, 4000000 / bytes)` calls.

| Bytes per operation | Baseline ns | Updated ns | Baseline / updated |
| --- | ---: | ---: | ---: |
| 8 | 619.7 | 325.4 | 1.90× |
| 32 | 572.9 | 347.4 | 1.65× |
| 64 | 697.4 | 481.1 | 1.45× |
| 256 | 1384.7 | 1226.1 | 1.13× |
| 1024 | 3727.0 | 3589.8 | 1.04× |
| 4096 | 13339.5 | 13592.2 | 0.98× |

The small operations are relevant to SSU2 header protection. Large operations
show essentially unchanged performance, including a roughly 2% slower sample
at 4096 bytes. These measurements do not establish end-to-end throughput,
latency, or tunnel-build improvements. Baseline and updated checksums matched.

Reproduce after compiling core classes:

```sh
env JAVA_HOME=/path/to/jdk-17 ./gradlew --offline --no-daemon :core:classes
env JAVA_HOME=/path/to/jdk-17 sh tests/scripts/benchmark-chacha20.sh HEAD
```

## Verification

The full suite passed **600 JUnit tests**, including **223 router tests**, and
**46 Scala tests**, with one existing pending Scala test. This turn adds 47
regression/control tests. Three outage-recovery tests and four probing tests
reproduced their failures before their corresponding fixes.

Crypto checks include RFC ChaCha20 and Poly1305 vectors; byte-for-byte agreement
with JDK ChaCha20-Poly1305 across 264 combinations of sizes, associated-data
lengths, offsets, and nonces; per-byte ciphertext/tag tampering; no plaintext
release on failed authentication; invalid-input atomicity; nonce exhaustion;
destroyed-state rejection; and clone isolation. Reseed tests simulate a failed
first host and verify that only the next successfully verified bundle imports.

```sh
env JAVA_HOME=/path/to/jdk-17 ./gradlew --offline --no-daemon \
  :router:test test :router:jar :apps:streaming:jar
```

For deployment, the packaged core jar retains every installed dependency and
resource and replaces only `net/i2p/crypto/ChaCha20.class` with the tested class.
The staged router jar is the complete tested build jar. The live harness's
`--core-jar` option validates the exact packaged core bytes before installation.

Both final live runs completed, exchanged tunnel traffic with external peers,
formed two-hop tunnels in both directions, and shut down normally:

| Observation | Normal TCP + UDP | TCP only, UDP disabled |
| --- | ---: | ---: |
| Sampled duration | 241 s | 241 s |
| Successful tunnel round trips | 15 | 18 |
| Successful exploratory build events | 9 | 6 |
| Rejected / timed-out build events | 0 / 2 | 0 / 1 |
| Observed SSU2 handshake versions | 2, 3, 4 | No UDP sessions |
| First simultaneous two-hop tunnels | 241 s | 16 s |

The normal run detected symmetric NAT. Version 3/4 SSU2 sessions exercise
existing hybrid modes; they do not establish what every network peer supports.
Counts include startup fallback tunnels and are not a controlled performance
comparison. No null-pointer, array-index, missing-class, out-of-memory,
illegal-argument, illegal-state, or bad-padding exceptions appeared in either
run's logs.

The [machine-readable report](validation/crypto-filtered-20261111-summary.json)
contains the test counts, measurements, exception checks, and artifact hashes.
The benchmark output and both identity-free live time series are retained in
`validation/`.

Validated packaged jar SHA-256 hashes:

```text
i2p.jar     d4b4abe2a4ba11c0e0a2c1a3c503382f22b17b4d94c5fb5a29a13a64ffcb3c63
router.jar  432b86d07065ea4ba321d90356864bd4faa47a2b7f1cb64bd85c513d8b17b9ee
```

## Installed application

Both validated jars are installed in `/home/kali/i2p/lib`, and their hashes still
match the staged/live-tested artifacts. The existing service restarted with
Wrapper and Java both `STARTED`. Configuration and router identity were
preserved. Rollback copies of both jars and the previous build receipt are in
`/tmp/i2p-crypto-filtered-20261111/installed/`.

Post-start console checks returned HTTP 200 for the console, configuration,
tunnel manager, and web applications. The installed router reported
**SymmetricNAT**, 5 client tunnels, 9 exploratory tunnels, 9 TCP connections and
12 UDP connections. A request to `http://stats.i2p/` through the local proxy
returned HTTP 200 with the expected title in 4.53 seconds. These observations
establish compatibility and working traffic on this connection; they do not
measure resilience against a national censorship system.
