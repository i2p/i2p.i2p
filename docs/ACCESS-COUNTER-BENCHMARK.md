# Access-counter flood handling

The access filter now retains only the newest timestamps needed by its largest
configured threshold for each destination. Rejected attempts replace old
samples in a primitive circular buffer. Window checks take constant time and
purging advances the buffer head without shifting an array of boxed values.
Unknown destinations using a static allow/deny default require no history when
there are no effective recorders. Explicit rules still take precedence.

This is a per-destination bound determined by the configured thresholds. It does
not impose a global destination limit or establish protection against arbitrary
numbers of distinct identities.

The standalone benchmark submits 100,000 attempts against a threshold of 100
attempts per minute. It checks every admission decision and then expires the
entire history. It runs four warmup iterations and reports the median of seven
samples in separate JVMs for the original and updated implementations, with
256 MiB heaps. OpenJDK 17.0.20.1 on this machine produced:

| Measurement | Original | Updated |
| --- | ---: | ---: |
| Retained timestamps after the burst | 100,000 | 100 |
| Bytes allocated during recording/checking | 3,680,928 | 1,888 |
| Record/check rate, million attempts/second | 44.453 | 44.807 |
| Purge time, milliseconds | 510.366 | 0.003 |

The main gain is bounded memory and removal of the cleanup CPU spike. The
record/check rate is similar in this workload. These are local counter costs;
live I2P throughput and end-to-end latency were not measured.

Reproduce from the repository root, using the JDK whose results you wish to
compare:

```sh
JAVA_HOME=/path/to/jdk sh tests/scripts/benchmark-access-counter.sh 53002765a
```

The script compiles both versions in a temporary directory and removes it on
exit. It does not require Gradle or contact a router. An optional second argument
sets the number of attempts. Allocation measurement uses the JDK's per-thread
allocation counters.
