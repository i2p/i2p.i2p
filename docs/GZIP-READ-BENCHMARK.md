# Gzip read performance

`LookaheadInputStream` previously called its source once per byte, including
while filling the inflater input buffer. Bulk reads now use one source read and
array copies, with one reusable scratch array the size of the withheld footer.
Circular footer order is preserved across partial reads, mixed single-byte
reads, and reinitialization.

The standalone benchmark reads 128 KiB of seeded random data using 4 KiB buffers.
It runs 100 warmup iterations and seven samples of 100 iterations in separate
JVMs with 256 MiB heaps. Each iteration checks the byte count and payload CRC32.
Measured on this machine with OpenJDK 17.0.20.1, after the tests finished:

| Path | Original MiB/s | Updated MiB/s |
| --- | ---: | ---: |
| Lookahead reads | 36.6 | 6219.0 |
| Full gzip decompression | 36.8 | 790.5 |

The gzip result is about 21.5x faster for this in-memory workload.
I2P transfer speed and latency were not measured.

Reproduce from the repository root:

```sh
./gradlew :core:classes
sh tests/scripts/benchmark-lookahead.sh 53002765a
```

Set `JAVA_HOME` to the JDK used for compilation. Seven new tests cover footer
integrity, offsets, short source reads, mixed reads, reuse, source call counts,
empty reads, argument checks, and a zero-byte lookahead.
