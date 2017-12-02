package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.SessionKey;

public class HMACSHA256Test extends TestCase{
    private I2PAppContext _context;
    
    protected void setUp() {
        _context = I2PAppContext.getGlobalContext();
    }
    
    public void testMultiple(){
        int size = 1;
        for(int i = 0; i < 16; i++){
            SessionKey key = _context.keyGenerator().generateSessionKey();
            
            byte[] message = new byte[size];
            size*=2;
            _context.random().nextBytes(message);
            
            byte[] output = new byte[32];
            _context.hmac().calculate(key, message, 0, message.length, output, 0);
        }
    }
}
