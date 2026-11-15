# Opt-in peer torrent listings

The search sidebar can fetch listings from connected peers that support the
`i2p_catalog` extension. It searches a local, temporary cache. A refresh requires
an explicit POST action with a session-bound, single-use nonce. Typing a search
term or polling the sidebar never sends the term to peers or initiates a pull.

Sharing starts disabled. Select individual public torrents under **Share selected
torrents** and save the selection. Only selected, running torrents with known,
public metadata are disclosed. Private torrents and unresolved magnets are
excluded, even when a request arrives on their peer connection. Stopping Snark
clears the remote cache and protocol state; explicit local selections persist.

Listings travel over existing authenticated, encrypted I2P streaming connections.
Each item contains only a torrent's display name and 20-byte info hash. It does
not contain a file list, completion status, tracker URL, router identity, or peer
address. Cached remote results are never forwarded to other peers. A recipient
can retain or redistribute a listing after receiving it; unsharing prevents
future responses and cannot revoke a copy already delivered.

This is discovery among connected, upgraded peers. Older clients ignore the
extension. It is not a network-wide index. Peer-supplied names are unverified and
may be misleading. **Review torrent** prefills the normal add form with a locally
constructed magnet, and never starts a download automatically. Normal magnet
metadata verification still applies when the user adds it.

## Wire format, version 1

Negotiate `i2p_catalog` through BEP 10. Each side uses the other side's advertised
message ID for outgoing frames; zero disables the extension. The local receive
ID is 5. Every payload is one bencoded dictionary with no trailing data:

| Field | Value |
| --- | --- |
| `v` | Integer 1 |
| `msg_type` | Integer 0 for request, 1 for response |
| `id` | Eight random bytes, echoed by the response |
| `page` | Integer from 0 through 4 |
| `items` | Response only: up to 20 dictionaries containing `ih` (20 bytes) and `name` (strict UTF-8, 1–256 bytes) |
| `more` | Response only: integer 0 or 1 |

A pull starts at page zero. Responses must match the requesting connection, ID,
and expected page. The next page is requested only after a full page with
`more=1`; at most five pages are fetched. Requests and pagination expire after
60 seconds. Responses re-evaluate sharing selections on every page. Control
characters and bidirectional formatting controls are rejected in names.

Payloads are limited to 8192 bytes and declared strings are bounded before
allocation. Each refresh contacts at most 16 distinct peer destinations and is
rate limited to once every 30 seconds. Inbound pulls are rate limited per
connection, with at most 128 remembered connections. The cache holds at most
200 info hashes for ten minutes. Disconnect and shutdown discard request state.
No DHT records, public trackers, extra listening ports, or router netDb entries
are used by this extension.

Run `python3 tests/scripts/check_i2psnark_feature.py catalog` after building the normal
dependency jars to exercise protocol limits and actual servlet sessions offline.
