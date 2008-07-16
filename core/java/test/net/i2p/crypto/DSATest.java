package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import java.io.ByteArrayInputStream;

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

public class DSATest extends TestCase{
    private I2PAppContext _context;
    
    protected void setUp() {
        _context = new I2PAppContext();
    }
    
    public void testMultiple(){
        for(int i = 0; i < 100; i++){
            byte[] message = new byte[256];
            _context.random().nextBytes(message);
            
            Object[] keys = KeyGenerator.getInstance().generateSigningKeypair();
            SigningPublicKey pubkey = (SigningPublicKey)keys[0];
            SigningPrivateKey privkey = (SigningPrivateKey)keys[1];
            
            Signature s = DSAEngine.getInstance().sign(message, privkey);
            Signature s1 = DSAEngine.getInstance().sign(new ByteArrayInputStream(message), privkey);
            
            assertTrue(DSAEngine.getInstance().verifySignature(s, message, pubkey));
            assertTrue(DSAEngine.getInstance().verifySignature(s1, new ByteArrayInputStream(message), pubkey));
            assertTrue(DSAEngine.getInstance().verifySignature(s1, message, pubkey));
            assertTrue(DSAEngine.getInstance().verifySignature(s, new ByteArrayInputStream(message), pubkey));
        }
    }
}