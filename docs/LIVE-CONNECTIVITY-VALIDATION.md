# Verified live connectivity, 2026-11-11

A fresh normal-mode run of the finite harness completed its requested 360-second
window, sampled through 361 seconds, passed every default connectivity check,
and exited zero after confirmed router shutdown. It recorded 33 successful
tunnel round trips and 24 distinct samples with two-hop exploratory tunnels in
both directions. Loaded core/router SHA-256 hashes matched the launch manifest.

This is a public-network test of the new harness against the previously
validated combined production build, including the companion networking fixes.
It is not an isolated live test of each production PR. The isolated harness
regressions comprise 23 deterministic Python tests; no production router
sources are changed by the connectivity follow-up.

Retained evidence:

- [Metrics](validation/live-connectivity-20261111-metrics.jsonl)
- [Launch manifest](validation/live-connectivity-20261111-launch.json)
- [Verdict and hashes](validation/live-connectivity-20261111-summary.json)

Recheck the evidence without starting a router:

```sh
python3 tests/live/verify_live.py docs/validation/live-connectivity-20261111-metrics.jsonl \
  --process-exit-code 0 --launch-manifest docs/validation/live-connectivity-20261111-launch.json
```

The run used one host and one connection. Controlled transport recovery is
covered separately by the companion loopback fault-injection suite.
