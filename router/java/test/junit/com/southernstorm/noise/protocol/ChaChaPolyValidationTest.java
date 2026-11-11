package com.southernstorm.noise.protocol;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChaChaPolyValidationTest {
    @Test
    public void independentProviderMatchesPacketSizesOffsetsAndNonces() throws Exception {
        Random random = new Random(8439);
        for (int length : new int[] { 0, 1, 15, 16, 17, 63, 64, 65, 256, 1400, 4096 }) {
            for (int adLength : new int[] { 0, 1, 15, 16, 17, 32 }) {
                for (long nonce : new long[] { 0, 1, 0x4746454443424140L, -2L }) {
                    byte[] key = new byte[32];
                    byte[] ad = new byte[adLength];
                    byte[] message = new byte[length];
                    random.nextBytes(key);
                    random.nextBytes(ad);
                    random.nextBytes(message);
                    byte[] expected = reference(key, nonce, ad, message);
                    byte[] keyBuffer = padded(key, 3);
                    byte[] adBuffer = padded(ad, 4);
                    byte[] messageBuffer = padded(message, 5);
                    byte[] result = new byte[length + 30];
                    Arrays.fill(result, (byte) 0x55);
                    ChaChaPolyCipherState sender = new ChaChaPolyCipherState();
                    sender.initializeKey(keyBuffer, 3);
                    sender.setNonce(nonce);
                    assertEquals(length + 16, sender.encryptWithAd(adBuffer, 4, adLength,
                                 messageBuffer, 5, result, 7, length));
                    assertArrayEquals(expected, Arrays.copyOfRange(result, 7, 7 + expected.length));
                    assertEquals(0x55, result[6]);
                    assertEquals(0x55, result[7 + expected.length]);
                    ChaChaPolyCipherState receiver = state(key, nonce);
                    assertEquals(length, receiver.decryptWithAd(adBuffer, 4, adLength,
                                 result, 7, result, 7, expected.length));
                    assertArrayEquals(message, Arrays.copyOfRange(result, 7, 7 + length));
                }
            }
        }
    }

    @Test
    public void everyCiphertextAndTagByteIsAuthenticatedBeforePlaintextRelease() throws Exception {
        byte[] key = new byte[32];
        byte[] ad = { 1, 2, 3 };
        byte[] message = new byte[65];
        byte[] ciphertext = reference(key, 0, ad, message);
        ChaChaPolyCipherState receiver = state(key, 0);
        for (int index = 0; index < ciphertext.length; index++) {
            byte[] corrupted = ciphertext.clone();
            corrupted[index] ^= 1;
            byte[] output = filled(message.length);
            expect(BadPaddingException.class, () -> receiver.decryptWithAd(ad, corrupted, 0,
                                                                          output, 0, corrupted.length));
            assertArrayEquals(filled(message.length), output);
        }
        byte[] wrongAd = { 1, 2, 4 };
        byte[] output = filled(message.length);
        expect(BadPaddingException.class, () -> receiver.decryptWithAd(wrongAd, ciphertext, 0,
                                                                      output, 0, ciphertext.length));
        assertArrayEquals(filled(message.length), output);
        assertEquals(message.length, receiver.decryptWithAd(ad, ciphertext, 0, output, 0, ciphertext.length));
        assertArrayEquals(message, output);
    }

    @Test
    public void invalidEncryptionRangesDoNotWriteOrAdvanceNonce() throws Exception {
        byte[] key = new byte[32];
        byte[] message = new byte[20];
        byte[] ad = new byte[16];
        byte[] expected = reference(key, 7, ad, message);
        for (int variant = 0; variant < 9; variant++) {
            ChaChaPolyCipherState cipher = state(key, 7);
            byte[] output = filled(64);
            final int which = variant;
            expect(variant == 8 ? ShortBufferException.class : IndexOutOfBoundsException.class, () -> {
                switch (which) {
                    case 0: cipher.encryptWithAd(ad, message, 0, output, 0, -1); break;
                    case 1: cipher.encryptWithAd(ad, message, -1, output, 0, 1); break;
                    case 2: cipher.encryptWithAd(ad, message, Integer.MAX_VALUE, output, 0, 1); break;
                    case 3: cipher.encryptWithAd(ad, message, 0, output, 0, 21); break;
                    case 4: cipher.encryptWithAd(ad, -1, 1, message, 0, output, 0, 20); break;
                    case 5: cipher.encryptWithAd(ad, 0, Integer.MAX_VALUE, message, 0, output, 0, 20); break;
                    case 6: cipher.encryptWithAd(null, 0, 1, message, 0, output, 0, 20); break;
                    case 7: cipher.encryptWithAd(ad, message, 0, output, -1, 20); break;
                    default: cipher.encryptWithAd(ad, message, 0, output, 40, 20); break;
                }
            });
            assertArrayEquals("variant " + variant, filled(64), output);
            byte[] actual = new byte[expected.length];
            cipher.encryptWithAd(ad, message, 0, actual, 0, message.length);
            assertArrayEquals("nonce after variant " + variant, expected, actual);
        }
    }

    @Test
    public void invalidKeyDoesNotPartiallyReplaceExistingKey() throws Exception {
        byte[] key = filled(32);
        ChaChaPolyCipherState cipher = state(key, 3);
        expect(IndexOutOfBoundsException.class, () -> cipher.initializeKey(new byte[31], 0));
        expect(IndexOutOfBoundsException.class, () -> cipher.initializeKey(new byte[32], -1));
        byte[] actual = new byte[17];
        cipher.encryptWithAd(null, new byte[1], 0, actual, 0, 1);
        assertArrayEquals(reference(key, 3, new byte[0], new byte[1]), actual);
    }

    @Test
    public void invalidDecryptionRangesDoNotWriteOrAdvanceNonce() throws Exception {
        byte[] key = new byte[32];
        byte[] ciphertext = reference(key, 5, new byte[0], new byte[20]);
        ChaChaPolyCipherState cipher = state(key, 5);
        byte[] output = filled(20);
        expect(IndexOutOfBoundsException.class, () -> cipher.decryptWithAd(null, ciphertext, 0, output, 0, -1));
        expect(IndexOutOfBoundsException.class, () -> cipher.decryptWithAd(null, ciphertext, -1, output, 0, 20));
        expect(IndexOutOfBoundsException.class, () -> cipher.decryptWithAd(null, ciphertext, 0, output, -1, 20));
        expect(ShortBufferException.class, () -> cipher.decryptWithAd(null, ciphertext, Integer.MAX_VALUE,
                                                                    output, 0, 20));
        expect(ShortBufferException.class, () -> cipher.decryptWithAd(null, ciphertext, 0,
                                                                    output, Integer.MAX_VALUE, 20));
        assertArrayEquals(filled(20), output);
        cipher.decryptWithAd(null, ciphertext, 0, output, 0, ciphertext.length);
        assertArrayEquals(new byte[20], output);
    }

    @Test
    public void exhaustedNonceFailsBeforeWritingOutput() throws Exception {
        ChaChaPolyCipherState cipher = state(new byte[32], -2L);
        cipher.encryptWithAd(null, new byte[0], 0, new byte[16], 0, 0);
        byte[] output = filled(16);
        expect(IllegalStateException.class, () -> cipher.encryptWithAd(null, new byte[0], 0, output, 0, 0));
        assertArrayEquals(filled(16), output);
    }

    @Test
    public void destroyedStateCannotEncryptCopyOrBeRevived() throws Exception {
        ChaChaPolyCipherState cipher = state(filled(32), 0);
        cipher.destroy();
        assertFalse(cipher.hasKey());
        byte[] output = filled(32);
        expect(IllegalStateException.class, () -> cipher.encryptWithAd(null, new byte[1], 0, output, 0, 1));
        expect(IllegalStateException.class, () -> cipher.decryptWithAd(null, new byte[17], 0, output, 0, 17));
        expect(IllegalStateException.class, () -> cipher.initializeKey(new byte[32], 0));
        expect(IllegalStateException.class, () -> cipher.setNonce(0));
        assertArrayEquals(filled(32), output);
        for (String name : new String[] { "input", "output" }) {
            Field field = ChaChaPolyCipherState.class.getDeclaredField(name);
            field.setAccessible(true);
            assertArrayEquals(new int[16], (int[]) field.get(cipher));
        }
        Field field = ChaChaPolyCipherState.class.getDeclaredField("polyKey");
        field.setAccessible(true);
        assertArrayEquals(new byte[32], (byte[]) field.get(cipher));
    }

    private static ChaChaPolyCipherState state(byte[] key, long nonce) {
        ChaChaPolyCipherState cipher = new ChaChaPolyCipherState();
        cipher.initializeKey(key, 0);
        cipher.setNonce(nonce);
        return cipher;
    }

    private static byte[] reference(byte[] key, long nonce, byte[] ad, byte[] message) throws Exception {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++)
            iv[4 + i] = (byte) (nonce >>> (8 * i));
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(iv));
        cipher.updateAAD(ad);
        return cipher.doFinal(message);
    }

    private static byte[] padded(byte[] data, int offset) {
        byte[] result = new byte[data.length + offset + 1];
        System.arraycopy(data, 0, result, offset, data.length);
        return result;
    }

    private static byte[] filled(int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 0x5a);
        return data;
    }

    private interface CheckedAction { void run() throws Exception; }

    private static void expect(Class<? extends Exception> type, CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Expected " + type.getName());
        } catch (Exception expected) {
            assertTrue(expected.toString(), type.isInstance(expected));
        }
    }
}
