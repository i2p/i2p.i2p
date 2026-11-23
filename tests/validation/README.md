# Repository regression validation

Run the full JUnit/Scala regression build and the validation-tool Python tests
from a fresh checkout with JDK 17 and Python 3.12 or later:

```sh
python3 tests/validation/run.py --java-home /path/to/jdk-17
```

The Gradle wrapper downloads Gradle and dependencies on the first run. Install
`gettext` so jar tasks can compile translated messages. An already populated
Gradle cache can be used with `--offline`. No separately built project jars,
local classpath manifests, installed router or files under `/tmp` are required.

The companion `jdk17-tests.gradle` init script replaces the obsolete test
repository and pins JUnit, Mockito and the Scala library to JDK 17 compatible
versions. It configures the test JVM module access used by the regression
suite. This lets old and independent fix branches run before the normal build
compatibility change (#188) lands; production sources and packaged runtime
dependencies are unchanged.

The default command disables the Gradle build-output cache, runs `clean test`,
then Python `unittest` discovery for this
validation tool and any tests in `tests/live` and `tests/recovery`. Those
companion suites are discovered only when their test files exist in the
checkout. A failed command, failing JUnit report, missing JUnit results or source
mutation makes validation fail. Existing Scala pending cases retain their
normal ScalaTest reporting. Complete Gradle/Scala output is retained in the log.

Reports are written below `build/validation`: logs, JUnit XML, HTML test reports,
JSON totals, command exit status, JDK information and SHA-256 hashes of source,
validation tools and reports. The PR workflow uploads these reports even when
tests fail. It runs with read-only repository permissions and never packages,
publishes, deploys or starts a live Internet router.

Validate selected modules by repeating `--task`:

```sh
python3 tests/validation/run.py --task :core:test --task :router:test
```

Compare clean local revisions using disposable checkouts:

```sh
python3 tests/validation/run.py --ref upstream/master --ref fix/example \
  --output /path/to/results
```

Each ref runs its own committed test suite. Different totals are recorded as
such; this comparison alone does not prove that a new regression fails on the
baseline. A missing ref is an error. Fetch desired refs explicitly first.

Check several changes together in the specified merge order:

```sh
python3 tests/validation/run.py --combine-ref fix/one --combine-ref fix/two \
  --output /path/to/combined-results
```

Combined validation starts from the caller's committed `HEAD`, creates local
merge commits in a temporary clone, and runs the resulting source tree. Merge
conflicts stop validation instead of being silently resolved. The working
checkout and its uncommitted changes are not used in this mode. Temporary
checkouts are removed after validation; reports remain in the chosen output
folder. This replaces machine-specific split-validation scripts with commands
that can run in CI or another contributor's checkout.

Controlled transport recovery is supplied by a companion change in `tests/recovery`.
