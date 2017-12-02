package net.i2p.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;
import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Test KBucketSet.
 * Newer tests ported from KBSTest in i2p.zzz.kademlia branch
 *
 * @author comwiz
 * @since 0.9.10 moved from net.i2p.router.networkdb.kademlia
 */

public class KBucketSetTest extends TestCase{
	private I2PAppContext context;
	private KBucketSet<Hash> set;
        private Hash usHash;
        private Log log;
	private static final int K = 8;
	private static final int B = 1;
	
	public void setUp(){
		context = I2PAppContext.getGlobalContext();
		log = context.logManager().getLog(KBucketSet.class);
		byte[] us = new byte[Hash.HASH_LENGTH];
		context.random().nextBytes(us);
		usHash = new Hash(us);
                // We use the default RandomTrimmer so add() will never fail
		set = new KBucketSet<Hash>(context, usHash, K, B);
		// tests may be run in any order so prime it
		addRandom(1000);
	}
	
	public void testRandom(){
		addRandom(1000);
	}

	private void addRandom(int count) {
		for (int i = 0; i < count; i++) {
			byte val[] = new byte[Hash.HASH_LENGTH];
			context.random().nextBytes(val);
			Hash h = new Hash(val);
			// in the highly unlikely chance we randomly generate a hash equal to us
			assertTrue(set.add(h) || h.equals(usHash));
		}
	}
    
    public void testSelf() {
        // new implementation will never include myself
        assertFalse(set.add(usHash));
    }

    /** @since 0.9.10 */
    public void testConcurrent() {
        int count = 2500;
        int n = 4;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new RTester(count);
        }
        for (int i = 0; i < n; i++) {
            threads[i].start();
        }
        for (int i = 0; i < n; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException ie) {}
        }
    }

    /** @since 0.9.10 */
    private class RTester extends Thread {
        private final int _count;
        public RTester(int count) {
            _count = count;
        }
        public void run() {
            addRandom(_count);
        }
    }

    /** @since 0.9.10 */
    public void testAudit() {
        int errors = 0;
        for (KBucket<Hash> b : set.getBuckets()) {
            for (Hash sds : b.getEntries()) {
                int range = set.getRange(sds);
                if (range < b.getRangeBegin() || range > b.getRangeEnd()) {
                    log.error("Hash " + sds + " with range " + range +
                              " does not belong in " + b);
                    errors++;
                }
            }
        }
        assertTrue(errors == 0);
    }

    /** @since 0.9.10 */
    public void testOrder() {
        int bits = Hash.HASH_LENGTH * 8;
        int errors = 0;
        int lastEnd = -1;
        for (KBucket<Hash> b : set.getBuckets()) {
            int beg = b.getRangeBegin();
            if (beg != lastEnd + 1) {
                log.error("Out of order: " + b);
                errors++;
            }
            lastEnd = b.getRangeEnd();
        }
        if (lastEnd != (bits * (1 << (B-1))) - 1) {
            log.error("Out of order: last=" + lastEnd);
            errors++;
        }
        assertTrue(errors == 0);
    }

    /** @since 0.9.10 */
    public void testGenRandom() {
        int errors = 0;
        for (KBucket<Hash> b : set.getBuckets()) {
            for (int j = 0; j < 4000; j++) {
                Hash rand = set.generateRandomKey(b);
                int range = set.getRange(rand);
                if (range < b.getRangeBegin() || range > b.getRangeEnd()) {
                    log.error("Generate random key failed range=" + range + " for " + rand + " meant for bucket " + b);
                    errors++;
                }
            }
        }
        assertTrue(errors == 0);
    }

    /** @since 0.9.10 */
    public void testExplore() {
        List<Hash> keys = set.getExploreKeys(-1000);
        assertTrue(keys.size() > 0);
    }

    /** @since 0.9.10 */
    public void testClosest() {
        byte val[] = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < 23; i++) {
            context.random().nextBytes(val);
            Hash h = new Hash(val);
            List<Hash> c = set.getClosest(h, i);
            assertTrue(c.size() == i);
        }
    }
}
