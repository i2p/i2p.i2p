package net.i2p.crypto;

import java.util.Arrays;

/** Synthetic CPU benchmark; keys/nonces here must never be used for real traffic. */
public final class ChaCha20Benchmark {
    private static volatile int checksum;

    public static void main(String[] args) {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        Arrays.fill(key, (byte) 0x5a);
        System.out.println("bytes,median_ns_per_call");
        int[] lengths = args.length == 1 ? new int[] { Integer.parseInt(args[0]) } :
                        new int[] { 8, 32, 64, 256, 1024, 4096 };
        for (int length : lengths) {
            byte[] input = new byte[length];
            byte[] output = new byte[length];
            int iterations = Math.max(20000, 4000000 / length);
            for (int warmup = 0; warmup < 3; warmup++)
                run(key, nonce, input, output, iterations);
            long[] samples = new long[7];
            for (int sample = 0; sample < samples.length; sample++) {
                long start = System.nanoTime();
                run(key, nonce, input, output, iterations);
                samples[sample] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            System.out.printf(java.util.Locale.US, "%d,%.1f%n", length,
                              (double) samples[samples.length / 2] / iterations);
        }
        System.out.println("checksum=" + checksum);
    }

    private static void run(byte[] key, byte[] nonce, byte[] input, byte[] output, int iterations) {
        int total = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            nonce[0] = (byte) iteration;
            nonce[1] = (byte) (iteration >>> 8);
            nonce[2] = (byte) (iteration >>> 16);
            ChaCha20.encrypt(key, nonce, input, 0, output, 0, input.length);
            total += output[iteration % output.length];
        }
        checksum = total;
    }
}
