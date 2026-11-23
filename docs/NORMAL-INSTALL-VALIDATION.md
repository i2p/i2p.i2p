# Installed build validation — 11 November 2026

This is a historical report from the combined development workspace at
`e04787750` plus its uncommitted fixes. Its test totals and live measurements
describe that combined build; they are not new live tests of this tooling
branch or any individual production PR. The standalone branches target
upstream `53002765a` and have separate isolated regression checks.
Source fingerprints are retained in [the provenance manifest](validation/followup-source-provenance.json).

For the later installed NAT and restrictive-network update, its artifact hash,
and current verification results, see
[the follow-up report](NAT-RESTRICTIVE-NETWORKS.md).
The subsequent [crypto update](CRYPTO-FILTERED-NETWORKS.md) records the latest
installed core/router artifacts and compatibility checks.

The updated **2.14.0-2-rc** workspace build is installed in `/home/kali/i2p`
and remains running as the current user's I2P service. Configuration and the
new router identity are in `/home/kali/.i2p`. No previous installed I2P instance
or configuration was present.

- Console: <http://127.0.0.1:7657/>
- HTTP proxy: `127.0.0.1:4444`
- Start / stop / status: `~/i2p/i2prouter start`, `stop`, or `status`
- Installation receipt and jar hashes: `~/i2p/UPDATED-BUILD.json`

This is the full application installation with the normal console, tunnel
manager, address book, mail UI, BitTorrent UI, and local webserver. It uses
normal TCP and UDP transport settings and automatic reachability detection.
The earlier forced-introducer test configuration and disposable identity were
left separate. Boot-time autostart was not enabled. Automatic software updates
are disabled to preserve this custom build; automatic browser launch is also
disabled.

## Results

The installed service reported both its native wrapper and Java process as
`STARTED`. The console, I2CP interface, proxies, and local webserver listen only
on loopback addresses. Native CPUID and jbigi crypto libraries loaded.

| Check | Observed result |
| --- | --- |
| Console, tunnels/configuration, and five web apps | HTTP 200 |
| First request to console root | Expected redirect to the first-run welcome page |
| Browser rendering of home, console, tunnel manager, address book | Four normal pages; no JavaScript or resource errors |
| Client / exploratory tunnels | 11 / 15 at the final full-sidebar sample |
| TCP / UDP connections | 2 / 9 at the final sample |
| Known routers | 161 at the final sample |
| `http://stats.i2p/` through the local proxy | HTTP 200; expected statistics-site title |
| `http://i2p-projekt.i2p/` through the local proxy | HTTP 302 to `/en/` |
| `http://i2p-projekt.i2p/en/` through the local proxy | HTTP 200; 21,576-byte project page in 1.27 seconds |

Automatic network detection identifies the connection as **SymmetricNAT**.
Outbound I2P browsing and client tunnel traffic worked with that condition.
The run does not establish unrestricted inbound transport reachability or
remove the upstream NAT behavior.

No null-pointer, missing-class, out-of-memory, or JSP exceptions appeared in the
router log during observation. One unreachable reseed host was handled by
fallback to other reseeds, which supplied 122 router infos. A single startup
JobQueue ordering diagnostic was logged at ERROR level; the application stayed
running and subsequent UI and proxy checks passed.

## Build verification

The complete Linux package was staged using the repository's Ant
`preppkg-linux-only` target and JDK 17. Two application jars were rebuilt in
clean isolated directories to remove unrelated Gradle build output from their
archives. Their canonical class bytes matched the original staged versions.
All installed jars and wars passed ZIP integrity checks.

All **13 patched router and streaming class files matched the previously
unit-tested Gradle artifacts byte-for-byte**. Installed jar hashes still matched
the installation receipt after the live application checks. The previous full
source suite passed 518 JUnit tests and 46 Scala tests; those results and the
separate forced-introducer experiment are documented in
[LIVE-NAT-VALIDATION.md](LIVE-NAT-VALIDATION.md).

The [machine-readable normal-install summary](validation/normal-install-20261111-summary.json)
records the HTTP responses, browser results, class comparison, and installed
jar hashes. Detailed validation output and private screenshots are retained in
`/tmp/i2p-install-20261111/`. Router keys and configuration stay outside the
repository.
