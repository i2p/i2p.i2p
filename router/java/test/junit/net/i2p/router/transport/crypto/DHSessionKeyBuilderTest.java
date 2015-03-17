package net.i2p.router.transport.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.SessionKey;
import net.i2p.util.RandomSource;

public class DHSessionKeyBuilderTest extends TestCase {
    public void testDHSessionKeyBuilder(){
        I2PAppContext ctx = new I2PAppContext();
        for (int i = 0; i < 5; i++) {
            DHSessionKeyBuilder builder1 = new DHSessionKeyBuilder();
            DHSessionKeyBuilder builder2 = new DHSessionKeyBuilder();
            BigInteger pub1 = builder1.getMyPublicValue();
            BigInteger pub2 = builder2.getMyPublicValue();
            try {
                builder2.setPeerPublicValue(pub1);
                builder1.setPeerPublicValue(pub2);
            } catch (DHSessionKeyBuilder.InvalidPublicParameterException ippe) {
                assertTrue(ippe.getMessage(), true);
            }
            SessionKey key1 = builder1.getSessionKey();
            SessionKey key2 = builder2.getSessionKey();

            assertEquals(key1, key2);

            byte iv[] = new byte[16];
            RandomSource.getInstance().nextBytes(iv);
            String origVal = "1234567890123456"; // 16 bytes max using AESEngine
            byte enc[] = new byte[16];
            byte dec[] = new byte[16];
            ctx.aes().encrypt(origVal.getBytes(), 0, enc, 0, key1, iv, 16);
            ctx.aes().decrypt(enc, 0, dec, 0, key2, iv, 16);
            String tranVal = new String(dec);
            assertEquals(origVal, tranVal);
        }
    }
}
