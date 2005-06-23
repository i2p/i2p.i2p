package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

import org.bouncycastle.crypto.digests.SHA256Digest;

import junit.framework.TestCase;
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
    
    public void testCopyConstructor(){
        SHA256Digest orig = new SHA256Digest();
        byte[] message = "update this!".getBytes();
        orig.update(message, 0, message.length);
        
        SHA256Digest copy = new SHA256Digest(orig);
        
        byte[] origData = new byte[32];
        orig.doFinal(origData, 0);
        byte[] copyData = new byte[32];
        copy.doFinal(copyData, 0);
        
        assertTrue(DataHelper.eq(origData, copyData));
        
    }
    
    public void testCheckName(){
        SHA256Digest digest = new SHA256Digest();
        assertEquals("SHA-256", digest.getAlgorithmName());
    }
    
    public void testManualUpdate(){
        byte[] data = "deathnotronic".getBytes();
        
        SHA256Digest one = new SHA256Digest();
        for(int i = 0; i < data.length; i++){
            one.update(data[i]);
        }
        
        SHA256Digest two = new SHA256Digest();
        two.update(data[0]);
        two.update(data, 1, data.length-1);
        
        byte[] oneData = new byte[32];
        one.doFinal(oneData, 0);
        byte[] twoData = new byte[32];
        two.doFinal(twoData, 0);
        
        assertTrue(DataHelper.eq(oneData, twoData));
    }
    
    public void test14Words(){
        byte message[] = new byte[56];
        _context.random().nextBytes(message);
        SHA256Digest orig = new SHA256Digest();
        orig.update(message, 0, message.length);
        orig.doFinal(new byte[32], 0);
    }
    
    public void testSHA(){
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte orig[] = new byte[4096];
        ctx.random().nextBytes(orig);
        Hash old = ctx.sha().calculateHash(orig);
        SHA256Digest d = new SHA256Digest();
        d.update(orig, 0, orig.length);
        byte out[] = new byte[Hash.HASH_LENGTH];
        d.doFinal(out, 0);
        assertTrue(DataHelper.eq(out, old.getData()));
    }
        
}