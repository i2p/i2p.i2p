package net.i2p.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChaCha20Test {
    @Test
    public void rfc8439EncryptionVector() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++)
            key[i] = (byte) i;
        byte[] nonce = hex("000000000000004a00000000");
        byte[] plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
                            "for the future, sunscreen would be it.").getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = new byte[plaintext.length];
        ChaCha20.encrypt(key, nonce, plaintext, 0, ciphertext, 0, plaintext.length);
        assertArrayEquals(hex("6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                              "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                              "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                              "5af90bbf74a35be6b40b8eedf2785e42874d"), ciphertext);
    }

    @Test
    public void headerSizesAndBlockBoundariesMatchIndependentProvider() throws Exception {
        Random random = new Random(8439);
        for (int length : new int[] { 0, 1, 7, 8, 15, 16, 31, 32, 63, 64, 65, 255, 256, 1024, 4096 }) {
            byte[] key = new byte[32];
            byte[] nonce = new byte[12];
            byte[] plaintext = new byte[length];
            random.nextBytes(key);
            random.nextBytes(nonce);
            random.nextBytes(plaintext);
            Cipher reference = Cipher.getInstance("ChaCha20");
            reference.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                           new ChaCha20ParameterSpec(nonce, 1));
            byte[] expected = reference.doFinal(plaintext);
            byte[] nonceBuffer = new byte[17];
            System.arraycopy(nonce, 0, nonceBuffer, 3, nonce.length);
            byte[] buffer = new byte[length + 12];
            Arrays.fill(buffer, (byte) 0x55);
            System.arraycopy(plaintext, 0, buffer, 5, length);
            ChaCha20.encrypt(key, nonceBuffer, 3, buffer, 5, buffer, 5, length);
            assertArrayEquals("length " + length, expected, Arrays.copyOfRange(buffer, 5, 5 + length));
            assertEquals(0x55, buffer[4]);
            assertEquals(0x55, buffer[5 + length]);
            ChaCha20.decrypt(key, nonceBuffer, 3, buffer, 5, buffer, 5, length);
            assertArrayEquals(plaintext, Arrays.copyOfRange(buffer, 5, 5 + length));
        }
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
}
