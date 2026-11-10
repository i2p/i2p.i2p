# Streaming receive-buffer benchmark

`MessageInputStream` now uses `ArrayDeque` for ready packets instead of removing
index zero from an `ArrayList`. Removing a packet no longer shifts the remaining
queue. A byte counter maintained under the existing data lock makes
`available()`, `getTotalReadySize()`, and ready-byte calculations in flow control
constant time. The counter handles partial reads, gap filling, and local close.

The standalone benchmark enqueues batches of 4 KiB packets, then queries both
byte-count methods and reads each packet. Each implementation runs in a separate
JVM with a 256 MiB heap, 2,000 warmup batches and seven samples of 4,000 batches.
Results below are medians measured on this machine with OpenJDK 17.0.20.1,
after the test suite completed.

| Queued packets | Original ns/packet | Updated ns/packet | Speedup |
| --- | ---: | ---: | ---: |
| 32 | 200.6 | 122.8 | 1.63x |
| 128 | 187.9 | 99.8 | 1.88x |
| 512 | 339.0 | 145.1 | 2.34x |

These measurements cover local receive-buffer work. They do not measure I2P
transfer speed, tunnel latency, retransmission rate, or transport throughput.
The benchmark calls the buffering layer directly; the 512-packet case exercises
queue scaling beyond the default receive-buffer capacity.

Reproduce from the repository root with a JDK compatible with the Gradle wrapper:

```sh
./gradlew --offline --no-daemon :apps:streaming:classes
sh tests/scripts/benchmark-streaming.sh 53002765a
```

Set `JAVA_HOME` if the default JDK differs from the build JDK. The script compiles
the original `MessageInputStream` from the chosen Git revision into a temporary
directory and compares it with the currently compiled implementation. It removes
its temporary files when finished.
