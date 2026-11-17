import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import java.util.zip.CRC32;
import net.i2p.util.LookaheadInputStream;
import net.i2p.util.ResettableGZIPInputStream;

/** Local buffer and gzip throughput; does not measure I2P transfer speed. */
public final class LookaheadGzipBenchmark {
    private static volatile long result;
    private static final int ITERATIONS = 100;

    public static void main(String[] args) throws Exception {
        byte[] data = new byte[128 * 1024];
        new Random(42).nextBytes(data);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(compressed);
        gzip.write(data);
        gzip.close();
        byte[] packed = compressed.toByteArray();
        for (boolean decompress : new boolean[] { false, true }) {
            run(decompress, data, packed, 100);
            long[] samples = new long[7];
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime();
                run(decompress, data, packed, ITERATIONS);
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            long bytes = (long) ITERATIONS * (decompress ? data.length : data.length - 8);
            double seconds = samples[3] / 1_000_000_000.0;
            System.out.printf("%s: %.1f MiB/s (median of 7 samples)%n",
                    decompress ? "gzip" : "lookahead", bytes / (1024.0 * 1024.0 * seconds));
        }
    }

    private static void run(boolean decompress, byte[] data, byte[] packed, int iterations) throws Exception {
        byte[] target = new byte[4096];
        long count = 0;
        CRC32 crc = new CRC32();
        CRC32 expected = new CRC32();
        expected.update(data, 0, decompress ? data.length : data.length - 8);
        for (int i = 0; i < iterations; i++) {
            crc.reset();
            if (decompress) {
                ResettableGZIPInputStream in = new ResettableGZIPInputStream(new ByteArrayInputStream(packed));
                int read;
                while ((read = in.read(target)) >= 0) {
                    count += read;
                    crc.update(target, 0, read);
                }
                in.destroy();
            } else {
                LookaheadInputStream in = new LookaheadInputStream(8);
                in.initialize(new ByteArrayInputStream(data));
                int read;
                while ((read = in.read(target)) >= 0) {
                    count += read;
                    crc.update(target, 0, read);
                }
                in.close();
            }
            if (crc.getValue() != expected.getValue())
                throw new AssertionError("Payload checksum mismatch");
        }
        long expectedCount = (long) iterations * (decompress ? data.length : data.length - 8);
        if (count != expectedCount)
            throw new AssertionError("Read " + count + ", expected " + expectedCount);
        result = count ^ crc.getValue();
    }
}
