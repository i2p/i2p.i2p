package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by str4d in 2015 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;

import org.junit.Test;

/**
 * @author str4d
 */
public class KeyCertificateTest {
    private static final byte[] P256_PAYLOAD = new byte[] {
                                    0, (byte) (SigType.ECDSA_SHA256_P256.getCode()),
                                    0, (byte) (EncType.EC_P256.getCode())
    };

    private static final byte[] P521_PAYLOAD = new byte[] {
                                    0, (byte) (SigType.ECDSA_SHA512_P521.getCode()),
                                    0, (byte) (EncType.ELGAMAL_2048.getCode()),
                                    0, 0, 0, 0
    };

    @Test
    public void testFromP256Payload() throws DataFormatException {
        KeyCertificate cert = new KeyCertificate(P256_PAYLOAD);
        assertThat(cert.getSigTypeCode(), is(equalTo(SigType.ECDSA_SHA256_P256.getCode())));
        assertThat(cert.getCryptoTypeCode(), is(equalTo(EncType.EC_P256.getCode())));
        assertThat(cert.getExtraSigningKeyData(), is(nullValue()));
    }

    @Test
    public void testFromEd25519Payload() throws DataFormatException {
        KeyCertificate cert = new KeyCertificate(P521_PAYLOAD);
        assertThat(cert.getSigTypeCode(), is(equalTo(SigType.ECDSA_SHA512_P521.getCode())));
        assertThat(cert.getCryptoTypeCode(), is(equalTo(EncType.ELGAMAL_2048.getCode())));
        assertThat(cert.getExtraSigningKeyData().length, is(4));
    }
}
