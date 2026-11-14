package com.southernstorm.noise.protocol;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.Test;

import net.i2p.data.Base64;

import static org.junit.Assert.*;

public class NoiseSecretDiagnosticsTest {
    @Test
    public void cipherAndHandshakeDiagnosticsDoNotExportSecrets() throws Exception {
        byte[] secret = filled(32);
        ChaChaPolyCipherState cipher = state(secret, 0);
        Field polyKey = ChaChaPolyCipherState.class.getDeclaredField("polyKey");
        polyKey.setAccessible(true);
        System.arraycopy(secret, 0, polyKey.get(cipher), 0, secret.length);
        assertFalse(cipher.toString().contains(Base64.encode(secret)));
        SymmetricState symmetric = new SymmetricState(NoiseInit.PatternID.XK);
        symmetric.mixKey(secret, 0, secret.length);
        String chainingKey = Base64.encode(symmetric.getChainingKey());
        assertFalse(symmetric.toString().contains(chainingKey));
        symmetric.destroy();
    }

    private static ChaChaPolyCipherState state(byte[] key, long nonce) {
        ChaChaPolyCipherState cipher = new ChaChaPolyCipherState();
        cipher.initializeKey(key, 0);
        cipher.setNonce(nonce);
        return cipher;
    }

    private static byte[] filled(int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 0x5a);
        return data;
    }

}
