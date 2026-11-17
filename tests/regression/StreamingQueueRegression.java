package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/** Tests actual stream delivery; the benchmark isolates draining ready blocks. */
public final class StreamingQueueRegression {
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();
    private static int checks;

    private static MessageInputStream stream() {
        return new MessageInputStream(CTX, 64, 128, 32768);
    }

    public static void main(String[] args) throws Exception {
        CTX.logManager().setDefaultLimit("CRIT");
        MessageInputStream input = stream();
        input.messageReceived(0, new ByteArray(new byte[] {99, 1, 2, 3, 99}, 1, 3));
        check(input.available() == 3, "initial available bytes");
        byte[] first = new byte[2];
        check(input.read(first) == 2 && Arrays.equals(first, new byte[] {1, 2}), "partial block read");
        check(input.available() == 1 && input.getTotalReadySize() == 1, "partial byte accounting");
        input.messageReceived(2, new ByteArray(new byte[] {7, 8}));
        check(Arrays.equals(input.getNacks(), new long[] {1}), "out-of-order NACKs");
        check(!input.messageReceived(0, new ByteArray(new byte[] {99})), "ready duplicate ignored");
        input.messageReceived(1, new ByteArray(new byte[] {4, 5, 6}));
        check(input.getNacks() == null && input.getHighestReadyBlockId() == 2, "gap filled");
        byte[] rest = new byte[64];
        int count = input.read(rest);
        check(count == 6 && Arrays.equals(Arrays.copyOf(rest, count), new byte[] {3, 4, 5, 6, 7, 8}),
              "FIFO delivery across a partially read block and a filled gap");
        input.closeReceived();
        check(input.read() == -1 && input.available() == 0, "EOF after draining");
        input.close();
        try {
            input.read();
            throw new AssertionError("closed read accepted");
        } catch (IOException expected) {
            checks++;
        }

        MessageInputStream empty = stream();
        empty.messageReceived(0, new ByteArray());
        empty.messageReceived(1, new ByteArray(new byte[] {42}));
        check(empty.read() == 42, "empty control block preserves sequence");
        empty.close();

        // Compare receive-limit decisions before and after queue changes.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        MessageInputStream limits = stream();
        for (int id = 0; id < 512; id++) {
            for (int size : new int[] {0, 1, 64})
                digest.update((byte) (limits.canAccept(id, size) ? 1 : 0));
            if (limits.canAccept(id, 1))
                limits.messageReceived(id, new ByteArray(new byte[] {1}));
        }
        System.out.println("ADMISSION_SHA256=" + HexFormat.of().formatHex(digest.digest()));
        limits.close();

        MessageInputStream concurrent = stream();
        concurrent.setReadTimeout(5000);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                for (int id = 0; id < 500; id++)
                    concurrent.messageReceived(id, new ByteArray(new byte[] {(byte) id}));
                concurrent.closeReceived();
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        producer.setDaemon(true);
        producer.start();
        for (int id = 0; id < 500; id++)
            check(concurrent.read() == (id & 255), "concurrent delivery " + id);
        check(concurrent.read() == -1, "concurrent EOF");
        producer.join(5000);
        check(!producer.isAlive() && error.get() == null, "producer completed");
        concurrent.close();

        System.out.println("STREAM_CHECKS=" + checks);
        if (args.length > 0 && "--benchmark".equals(args[0]))
            for (int window : new int[] {32, 128, 512})
                benchmark(window);
        CTX.logManager().shutdown();
    }

    private static void benchmark(int window) throws Exception {
        MessageInputStream input = stream();
        ByteArray block = new ByteArray(new byte[64]);
        byte[] output = new byte[window * 64];
        long sequence = 0;
        long[] samples = new long[5];
        // Three warm-up batches followed by five measured batches.
        for (int batch = -3; batch < samples.length; batch++) {
            long elapsed = 0;
            for (int round = 0; round < 500; round++) {
                for (int i = 0; i < window; i++)
                    input.messageReceived(sequence++, block);
                long start = System.nanoTime();
                int count = input.read(output);
                elapsed += System.nanoTime() - start;
                if (count != output.length)
                    throw new AssertionError("benchmark lost bytes");
            }
            if (batch >= 0)
                samples[batch] = elapsed;
        }
        Arrays.sort(samples);
        System.out.println("DRAIN_NS_PER_BLOCK_" + window + "=" + samples[2] / (500.0 * window));
        input.close();
    }

    private static void check(boolean condition, String name) {
        if (!condition)
            throw new AssertionError(name);
        checks++;
    }
}
