package com.southernstorm.noise.protocol;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChaChaPolyCloneIsolationTest {
    @Test
    public void destroyingCloneDoesNotInvalidateOriginalCipher() throws Exception {
        byte[] key = filled(32);
        ChaChaPolyCipherState original = state(key, 11);
        ChaChaPolyCipherState copy = original.clone();
        copy.destroy();
        assertTrue(original.hasKey());
        byte[] actual = new byte[17];
        original.encryptWithAd(null, new byte[1], 0, actual, 0, 1);
        assertArrayEquals(reference(key, 11, new byte[0], new byte[1]), actual);
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

    private static byte[] filled(int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 0x5a);
        return data;
    }

}
