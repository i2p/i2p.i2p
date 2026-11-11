package com.southernstorm.noise.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.*;

public class Poly1305CloneTest {
    @Test
    public void rfc8439KnownAnswer() {
        byte[] key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");
        byte[] data = "Cryptographic Forum Research Group".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(hex("a8061dc1305136c6c22b8baf0c0127a9"), tag(key, data));
    }

    @Test
    public void partialBlockCloneRetainsIndependentAccumulatorAndKey() throws Exception {
        byte[] key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b");
        byte[] prefix = "shared partial prefix".getBytes(StandardCharsets.US_ASCII);
        byte[] first = " first branch".getBytes(StandardCharsets.US_ASCII);
        byte[] second = " second branch has more than one block".getBytes(StandardCharsets.US_ASCII);
        Poly1305 original = new Poly1305();
        original.reset(key, 0);
        original.update(prefix, 0, prefix.length);
        Poly1305 copy = original.clone();
        original.update(first, 0, first.length);
        byte[] originalTag = new byte[16];
        original.finish(originalTag, 0);
        original.destroy();
        copy.update(second, 0, second.length);
        byte[] copyTag = new byte[16];
        copy.finish(copyTag, 0);
        assertArrayEquals(tag(key, concatenate(prefix, first)), originalTag);
        assertArrayEquals(tag(key, concatenate(prefix, second)), copyTag);
    }

    @Test
    public void resettingCloneDoesNotReplaceOriginalKey() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        byte[] message = new byte[17];
        Poly1305 original = new Poly1305();
        original.reset(key, 0);
        original.update(message, 0, 3);
        Poly1305 copy = original.clone();
        copy.reset(new byte[32], 0);
        original.update(message, 3, message.length - 3);
        byte[] result = new byte[16];
        original.finish(result, 0);
        assertArrayEquals(tag(key, message), result);
    }

    private static byte[] tag(byte[] key, byte[] message) {
        Poly1305 poly = new Poly1305();
        poly.reset(key, 0);
        poly.update(message, 0, message.length);
        byte[] result = new byte[16];
        poly.finish(result, 0);
        return result;
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
}
