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
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
/**
 * @author Comwiz
 */
public class SHA256Test extends TestCase{
    private I2PAppContext _context;
    
    protected void setUp() {
        _context = new I2PAppContext();
    }
    
    public void testMultiple(){
        int size = 1;
        for(int i = 0; i < 24; i++){
            byte[] message = new byte[size];
            size*=2;
            _context.random().nextBytes(message);
            
            SHA256Generator.getInstance().calculateHash(message);
        }
    }

    /**
     * Check if the behaviour remains the same.
     */
    public void testMultipleEquality(){
        byte[] data = "blahblah".getBytes();

        Hash firstHash = SHA256Generator.getInstance().calculateHash(data);

        for(int i=0; i<5; i++){
            Hash h = SHA256Generator.getInstance().calculateHash(data);
            assertEquals(firstHash, h);
        }
    }
        
}
