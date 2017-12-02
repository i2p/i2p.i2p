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
import net.i2p.data.SessionKey;
import net.i2p.util.RandomSource;

/**
 * @author Comwiz
 */
public class AES256Test extends TestCase{
    private I2PAppContext _context;
    private byte[] iv;
    
    protected void setUp() {
        _context = I2PAppContext.getGlobalContext();
    }
    
    public void testMultiple(){
        for(int i = 0; i < 100; i++){
            
            SessionKey key = _context.keyGenerator().generateSessionKey();
            
            byte[] iv = new byte[16];
            _context.random().nextBytes(iv);
            
            byte[] plain = new byte[256];
            _context.random().nextBytes(plain);
            
            byte[] e = new byte[plain.length];
            _context.aes().encrypt(plain, 0, e, 0, key, iv, plain.length);
            byte[] d = new byte[e.length];
            _context.aes().decrypt(e, 0, d, 0, key, iv, d.length);
            boolean same = true;
            assertTrue(DataHelper.eq(plain, d));
        }
    }
    
    @SuppressWarnings("deprecation")
    public void testLong(){
        I2PAppContext ctx = new I2PAppContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        RandomSource.getInstance().nextBytes(iv);
        
        
        byte lbuf[] = new byte[1024];
        RandomSource.getInstance().nextBytes(lbuf);
        byte le[] = ctx.aes().safeEncrypt(lbuf, key, iv, 2048);
        byte ld[] = ctx.aes().safeDecrypt(le, key, iv);
        assertTrue(DataHelper.eq(ld, lbuf));
    }
    
    public void testShort(){
        I2PAppContext ctx = new I2PAppContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        RandomSource.getInstance().nextBytes(iv);
        
        byte sbuf[] = new byte[16];
        RandomSource.getInstance().nextBytes(sbuf);
        byte se[] = new byte[16];
        ctx.aes().encrypt(sbuf, 0, se, 0, key, iv, sbuf.length);
        byte sd[] = new byte[16];
        ctx.aes().decrypt(se, 0, sd, 0, key, iv, se.length);
        assertTrue(DataHelper.eq(sd, sbuf));
    }
}
