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
import net.i2p.util.RandomSource;

/**
 * Test KBucketImpl
 *
 * @author comwiz
 */

public class KBucketImplTest extends TestCase{
	private I2PAppContext context;
	
	public void setUp(){
		context = I2PAppContext.getGlobalContext();
	}
	
	public void testLimits() {
        int low = 0;
        int high = 4;
        
        KBucketImpl bucket = new KBucketImpl(I2PAppContext.getGlobalContext(), Hash.FAKE_HASH);
        bucket.setRange(low, high);
        Hash lowerBoundKey = bucket.getRangeBeginKey();
        Hash upperBoundKey = bucket.getRangeEndKey();
        assertTrue(bucket.shouldContain(lowerBoundKey));//
        assertTrue(bucket.shouldContain(upperBoundKey));
        
    }
    
    public void testRand() {
        int low = 1;
        int high = 2000;
        
        Hash local = Hash.FAKE_HASH;
        local.prepareCache();
        KBucketImpl bucket = new KBucketImpl(I2PAppContext.getGlobalContext(), local);
        bucket.setRange(low, high);
        Hash lowerBoundKey = bucket.getRangeBeginKey();
        Hash upperBoundKey = bucket.getRangeEndKey();
        
        for (int i = 0; i < 1000; i++) {
            Hash rnd = bucket.generateRandomKey();
            assertTrue(bucket.shouldContain(rnd));//
        }
    }
    
    public void testRand2() {
        int low = 1;
        int high = 2000;
        
        byte hash[] = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(hash);
        Hash local = new Hash(hash);
        local.prepareCache();
        KBucketImpl bucket = new KBucketImpl(I2PAppContext.getGlobalContext(), local);
        bucket.setRange(low, high);
        Hash lowerBoundKey = bucket.getRangeBeginKey();
        Hash upperBoundKey = bucket.getRangeEndKey();
        
        for (int i = 0; i < 1000; i++) {
            Hash rnd = bucket.generateRandomKey();
            assertTrue(bucket.shouldContain(rnd));
        }
    }
}