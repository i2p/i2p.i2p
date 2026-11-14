# Cyber Sec audit workers

Ten separate GitHub Actions workflows audit the integrated code. They run on
pull requests, pushes to master/main, manual dispatch, and staggered weekly
schedules. The build worker has Java 17 and 21 jobs; the others select Java 17.

| Worker | What it actually checks | Output and limits |
| --- | --- | --- |
| Security | The 32-rule Java/C scanner covers exception handling, memory, input, resources, concurrency and security APIs | Review candidates with path/line/rule and rule guidance; a match is not a confirmed vulnerability. Read/decode errors or zero scanned files fail the worker. |
| Bugs | Regression-prone POST character counts, absolute payload-size calculations, single-character SAM decoding buffers | Focused source checks; unknown bugs require further review. |
| Regression | All included standalone Java regressions and the NTCP key-log source check | Executes the current working source, not proposed patches or a mock implementation. Missing required tests fail. |
| Compatibility | Streaming delivery/admission, NTCP frames/callbacks, packet offsets, HTTP bodies, tunnel delivery, crypto round trips and frame integrity | Compares saved baseline digests and exercises ElGamal, ECIES long and ECIES short records; does not replace live testing against other router releases. |
| Privacy | Captured HTTP and encrypted lease-set diagnostics, NTCP exception payloads, tunnel build/reply keys and fragment contents, failed-stream state, key-log source patterns | Detects the covered disclosures; does not certify every log or every privacy property. |
| Dependencies | Exact Gradle Maven coordinates, unresolved dynamic versions, bundled JAR SHA-256 inventory; online OSV advisory lookup | Only exact identified Maven versions are queried. Vendored sources and unmapped JARs remain unresolved. Network/incomplete-response errors fail rather than declaring no vulnerabilities. |
| Workflows | Permission, trigger, action-pin, credential, timeout, and generated-configuration policy | Gates these ten workers; records existing publishing workflows for separate review without rewriting their permissions. |
| Build | The core/router/ministreaming/streaming/addressbook graph plus the SAM reader, Python syntax, core Ant XML syntax | Runs on Java 17 and 21 in GitHub. This is not the full installer, webapp, Ant package, or full JUnit suite. |
| Performance | Stream queue drain at depths 32/128/512 and tunnel build scheduling with available/occupied slots and slow failed configuration | Checks demand, batch budget and completion wakeups; keeps timings and request-launch counts without an end-to-end speed claim. |
| Fuzz | 3,000 NTCP and 3,000 streaming parser cases, mixing bounded random input with valid and mutated writer output | Fixed seed, maximum input sizes, heap and process bounds; expected documented parse errors are accepted, unexpected failures stop the check. |

Each workflow has `contents: read`, no repository write permissions, no secrets,
no `pull_request_target`/`workflow_run`, an ephemeral GitHub-hosted runner,
disabled checkout credential persistence, a timeout, and seven-day evidence
artifacts. Actions are pinned to full commit SHAs. There is no automatic issue,
PR, deployment, fix commit, comment, email, or external code upload. Dependency
advisory requests send package coordinates and versions to the fixed OSV API.

## Local execution

The tooling uses Python's standard library, a JDK supporting release 17, and the
source/tests from the code PRs. It needs no downloaded Java test dependencies.

```sh
python3 -m unittest discover -s tests/audit -p 'test_*.py'
python3 tests/audit/workflow_definitions.py --check
python3 tests/audit/audit.py regression --output tests/results/regression
python3 tests/audit/audit.py compatibility --output tests/results/compatibility
python3 tests/audit/audit.py privacy --output tests/results/privacy
python3 tests/audit/audit.py fuzz --output tests/results/fuzz
python3 tests/audit/audit.py performance --output tests/results/performance
python3 tests/audit/audit.py build --output tests/results/build
python3 tests/audit/audit.py security --output tests/results/security
python3 tests/audit/audit.py bugs --output tests/results/bugs
python3 tests/audit/audit.py workflows --output tests/results/workflows
python3 tests/audit/audit.py dependencies --output tests/results/dependencies-offline
```

Add `--online` to the dependency command to query advisories, as configured in
its workflow. An offline inventory explicitly records that advisory lookup was
not performed. Unit tests exercise complete, incomplete, and advisory-positive
API responses using local stubs; they do not claim a successful live query.

Reports are JSON under the selected output directory. Source-pattern reports
omit matched source contents and secrets; tests use synthetic markers. Security
triage may have `passed: true` and `status: triage-required`: that means the
triage job ran, not that its candidates have been cleared. Regression and
compatibility failures remain blocking failures.

The security worker runs the [32-rule pattern scanner](PATTERNS.md). To inspect
its rules or run it separately:

```sh
python3 tests/audit/pattern_scan.py --list
python3 tests/audit/pattern_scan.py
```

Its findings need caller/control-flow review. Successful triage does not clear
the reported candidates or certify the code as safe.

## Maintaining the workers

The [tunnel review](../../docs/reviews/tunnels/README.md) also provides an offline
original/isolated/combined comparison runner and preserved source snapshots.
The ordinary regression worker runs all three tunnel fixtures; compatibility,
privacy and performance select the relevant fixtures as shown above.

`workflow_definitions.py` is the reviewed source of the ten YAML files. Update
the generator, regenerate, inspect the resulting diff, and run its `--check`
mode plus the unit tests. Existing application/release workflows are not
generated by this script. Compatibility contracts come from the original
regression output; do not regenerate them from a failing implementation to
make a job green. Add a concrete regression for every confirmed new bug.

The automation PR depends on the standalone regressions supplied with the code
fixes. It can be reviewed as a separate draft while those fixes are pending;
merge it after their regressions are available. Missing required fixtures fail
instead of silently reducing coverage. The queue regression is included here
because the corresponding production proposal is already covered by PR #199.
All test code is under `tests/`; generated evidence is ignored under
`tests/results/`. GitHub workflow entry points remain under `.github/workflows/`.
Local testing used OpenJDK 25 with `--release 17`; hosted Java 17/21 runtime checks
remain pending. Dependency inventory and advisory lookup cover only
the exact Maven coordinates detected by this worker; they are not a complete
vulnerability assessment.
