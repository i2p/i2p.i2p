package net.i2p.client.streaming.impl;

import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 * Local receive-buffer benchmark, run by tests/scripts/benchmark-streaming.sh.
 * Measures packet enqueueing, byte-count queries, and draining 4 KiB packets.
 * This does not measure end-to-end network throughput.
 */
public final class StreamingBufferBenchmark {
    private static volatile long result;

    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("ERROR");
        for (int queued : new int[] { 32, 128, 512 }) {
            MessageInputStream in = new MessageInputStream(context, 4096, 128, 2097152);
            byte[] payload = new byte[4096];
            byte[] target = new byte[4096];
            long next = run(in, payload, target, queued, 2000, 0);
            long[] samples = new long[7];
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime();
                next = run(in, payload, target, queued, 4000, next);
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            System.out.printf("queued=%d, median=%.1f ns/packet, packets/sample=%d%n",
                              queued, (double) samples[3] / (4000 * queued), 4000 * queued);
            in.close();
        }
    }

    private static long run(MessageInputStream in, byte[] payload, byte[] target,
                            int queued, int batches, long next) throws Exception {
        long sum = 0;
        for (int batch = 0; batch < batches; batch++) {
            for (int i = 0; i < queued; i++)
                in.messageReceived(next++, new ByteArray(payload));
            for (int i = 0; i < queued; i++) {
                sum += in.available();
                sum += in.getTotalReadySize();
                sum += in.read(target);
            }
        }
        result = sum;
        return next;
    }
}
