package net.i2p.router.networkdb.kademlia;
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
    private KBucketSet set;
	
	public void setUp(){
		context = I2PAppContext.getGlobalContext();
        set = new KBucketSet(context, Hash.FAKE_HASH);
	}
	
	public void testRandom(){
		for (int i = 0; i < 1000; i++) {
            byte val[] = new byte[Hash.HASH_LENGTH];
            context.random().nextBytes(val);
            assertTrue(set.add(new Hash(val)));
        }
    }
    
    public void testSelf() {
        assertTrue(set.add(Hash.FAKE_HASH));
    }
}