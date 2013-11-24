package net.i2p.kademlia;
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
import net.i2p.data.Hash;

/**
 * Test KBucketSet
 *
 * @author comwiz
 */

public class KBucketSetTest extends TestCase{
	private I2PAppContext context;
	private KBucketSet<Hash> set;
	private static final int K = 8;
	private static final int B = 1;
	
	public void setUp(){
		context = I2PAppContext.getGlobalContext();
		set = new KBucketSet<Hash>(context, Hash.FAKE_HASH, K, B);
	}
	
	public void testRandom(){
		for (int i = 0; i < 1000; i++) {
            byte val[] = new byte[Hash.HASH_LENGTH];
            context.random().nextBytes(val);
            assertTrue(set.add(new Hash(val)));
        }
    }
    
    public void testSelf() {
        // new implementation will never include myself
        assertFalse(set.add(Hash.FAKE_HASH));
    }
}
