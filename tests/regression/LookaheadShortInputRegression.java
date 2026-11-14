package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.util.Arrays;

public final class LookaheadShortInputRegression {
    public static void main(String[] args) throws Exception {
        LookaheadInputStream stream = new LookaheadInputStream(8);
        for (int size : new int[] {8, 9, 256}) {
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) i;
            stream.initialize(new ByteArrayInputStream(bytes));
            if (!Arrays.equals(stream.readAllBytes(), Arrays.copyOf(bytes, size - 8)))
                throw new AssertionError("valid stream data changed");
            if (!Arrays.equals(stream.getFooter(), Arrays.copyOfRange(bytes, size - 8, size)))
                throw new AssertionError("valid footer changed");
        }
        int failures = 0;
        try {
            stream.initialize(new ByteArrayInputStream(new byte[] {1, 2, 3}));
            failures++;
            System.out.println("FAIL: short initialization did not throw EOFException");
        } catch (EOFException expected) { }
        if (!Arrays.equals(stream.getFooter(), new byte[8]) || stream.read() != -1) {
            failures++;
            System.out.println("FAIL: failed initialization retained previous footer data");
        }
        System.out.println("CHECKS=5; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
}
