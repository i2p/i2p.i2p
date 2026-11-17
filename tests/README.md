# Tests and security checks

Keep test programs, fixtures, runners and generated evidence under `tests/`.

- `regression/`: standalone Java regressions supplied with individual fixes.
- `scripts/`: existing project checks and the NTCP key-log check.
- `audit/`: security scanner, GitHub Actions worker tooling and their unit tests.
- `results/`: ignored generated reports, logs and temporary test output.

Run the audit tooling tests with:

```sh
python3 -m unittest discover -s tests/audit -p 'test_*.py'
python3 tests/audit/workflow_definitions.py --check
python3 tests/audit/audit.py security --output tests/results/security
python3 tests/audit/pattern_scan.py --output tests/results/patterns/report.json
python3 tests/audit/audit.py regression --output tests/results/regression
```

The regex scan covers Java/C production source and writes path/line/rule matches
under `results/patterns/`. Review its matches with the guidance in
[audit/PATTERNS.md](audit/PATTERNS.md); counts are review candidates and require
caller/control-flow checks before treating them as defects.

See [the worker documentation](audit/README.md) for prerequisites, other commands
and coverage limits. GitHub requires workflow entry points under
`.github/workflows/`; those entry points call the tooling here.
