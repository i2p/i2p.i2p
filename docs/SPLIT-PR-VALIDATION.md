# Independent pull request validation — 11 November 2026

All 14 production changes (13 new PRs and the tunnel reachability follow-up) were compiled and tested independently against clean upstream `53002765a`. The isolated suites passed 105 JUnit tests. These checks use external Maven jars and freshly compiled upstream sources, without modified workspace classes or jars. The Gradle test-tooling changes remain in #188.

| Change | Isolated tests passed |
| --- | ---: |
| Crypto: validate AEAD ranges and reject destroyed cipher reuse | 7 |
| Crypto: avoid a redundant ChaCha20 block per operation | 2 |
| Crypto: keep Noise secret keys out of diagnostic strings | 1 |
| NTCP2: skip malformed addresses and preserve alternate transports | 9 |
| NTCP2: apply bounded probing resistance to invalid prefixes | 6 |
| Transports: avoid peer bans during local connectivity outages | 5 |
| Crypto: isolate mutable Poly1305 state when cloning | 4 |
| Reseed: bound decoded responses and remove partial downloads | 7 |
| Reseed: bound each fetch so stalled hosts cannot block bootstrap | 4 |
| SSU2: allow reconnection after lost token exchanges | 6 |
| Transports: preserve fallback after transient bids with debug logging | 4 |
| Tunnels: select reachable neighbors during startup and symmetric NAT | 31 |
| SSU2: negotiate exact versions and honor IPv6 introduction MTUs | 12 |
| SSU2: refresh destination mappings behind symmetric NAT | 7 |

The combined suite passed **166 tests, zero failures**, including regression coverage from #213, #215, #216 and #217. Combined reseed testing adapts the bounds-only mock from `fetch()` to the deadline overload; the assertions and response data are retained. The live harness compiles against clean upstream APIs, all four transport mode configurations passed local checks, and Python parsing/help checks passed.

The production coverage audit accounts for all 24 modified/new production targets exactly. The cipher validation/diagnostics pair and reseed bounds/deadline pair have small textual overlaps; their reviewed unions reproduce the original workspace bytes. No new live network run was performed for the split branches. The other retained reports record their historical combined builds, exact artifact hashes and testing limits.

[Machine-readable results and coverage](validation/split-pr-20261111.json).
