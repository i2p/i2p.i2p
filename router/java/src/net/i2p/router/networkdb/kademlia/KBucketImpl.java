package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

class KBucketImpl implements KBucket {
    private Log _log;
    /**
     *  set of Hash objects for the peers in the kbucketx
     *
     *  jrandom switched from a HashSet to an ArrayList with this change:
     *  2005-08-27  jrandom
     *    * Minor logging and optimization tweaks in the router and SDK
     *
     *  Now we switch back to a ConcurrentHashSet and remove all the
     *  synchronization, which may or may not be faster than
     *  a synchronized ArrayList, with checks for existence before
     *  adding a Hash. But the other benefit is it removes one
     *  cause of profileMangager/netDb deadlock.
     */
    private final Set<Hash> _entries;
    /** we center the kbucket set on the given hash, and derive distances from this */
    private LocalHash _local;
    /** include if any bits equal or higher to this bit (in big endian order) */
    private int _begin;
    /** include if no bits higher than this bit (inclusive) are set */
    private int _end;
    /** when did we last shake things up */
    private long _lastShuffle;
    private static final int SHUFFLE_DELAY = 10*60*1000;
    private I2PAppContext _context;
    
    public KBucketImpl(I2PAppContext context, LocalHash local) {
        _context = context;
        _log = context.logManager().getLog(KBucketImpl.class);
        _entries = new ConcurrentHashSet(2); //all but the last 1 or 2 buckets will be empty
        _lastShuffle = context.clock().now();
        setLocal(local);
    }
    
    /** for testing - use above constructor for production to get common caching */
    public KBucketImpl(I2PAppContext context, Hash local) {
        this(context, new LocalHash(local));
    }
    
    public int getRangeBegin() { return _begin; }
    public int getRangeEnd() { return _end; }
    public void setRange(int lowOrderBitLimit, int highOrderBitLimit) {
        _begin = lowOrderBitLimit;
        _end = highOrderBitLimit;
    }
    public int getKeyCount() {
        return _entries.size();
    }
    
    public LocalHash getLocal() { return _local; }
    private void setLocal(LocalHash local) {
        _local = local; 
        // we want to make sure we've got the cache in place before calling cachedXor
        _local.prepareCache();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Local hash reset to " + DataHelper.toHexString(local.getData()));
    }
    
    private byte[] distanceFromLocal(Hash key) {
        if (key == null) 
            throw new IllegalArgumentException("Null key for distanceFromLocal?");
        return _local.cachedXor(key);
    }
    
    public boolean shouldContain(Hash key) {
        byte distance[] = distanceFromLocal(key);
        // rather than use a BigInteger and compare, we do it manually by 
        // checking the bits
        boolean tooLarge = distanceIsTooLarge(distance);
        if (tooLarge) {
            if (false && _log.shouldLog(Log.DEBUG))
                _log.debug("too large [" + _begin + "-->" + _end + "] " 
                           + "\nLow:  " + BigInteger.ZERO.setBit(_begin).toString(16)
                           + "\nCur:  " + DataHelper.toHexString(distance)
                           + "\nHigh: " + BigInteger.ZERO.setBit(_end).toString(16));
            return false;
        }
        boolean tooSmall = distanceIsTooSmall(distance);
        if (tooSmall) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("too small [" + _begin + "-->" + _end + "] distance: " + DataHelper.toHexString(distance));
            return false;
        }
        // this bed is juuuuust right
        return true;
        
        /*
        // woohah, incredibly excessive object creation! whee!
        BigInteger kv = new BigInteger(1, distanceFromLocal(key));
        int lowComp = kv.compareTo(_lowerBounds);
        int highComp = kv.compareTo(_upperBounds);
        
        //_log.debug("kv.compareTo(low) = " + lowComp + " kv.compareTo(high) " + highComp);
        
        if ( (lowComp >= 0) && (highComp < 0) ) return true;
        return false;
        */
    }
    
    private final boolean distanceIsTooLarge(byte distance[]) {
        int upperLimitBit = Hash.HASH_LENGTH*8 - _end;
        // It is too large if there are any bits set before the upperLimitBit
        int upperLimitByte = upperLimitBit > 0 ? upperLimitBit / 8 : 0;
        
        if (upperLimitBit <= 0)
            return false;
        
        for (int i = 0; i < distance.length; i++) {
            if (i < upperLimitByte) {
                if (distance[i] != 0x00) {
                    // outright too large
                    return true;
                }
            } else if (i == upperLimitByte) {
                if (distance[i] == 0x00) {
                    // no bits set through the high bit
                    return false;
                } else {
                    int upperVal = 1 << (upperLimitBit % 8);
                    if (distance[i] > upperVal) {
                        // still too large, but close
                        return true;
                    } else if (distance[i] == upperVal) {
                        // ok, it *may* equal the upper limit,
                        // if the rest of the bytes are 0
                        for (int j = i+1; j < distance.length; j++) {
                            if (distance[j] != 0x00) {
                                // nope
                                return true;
                            }
                        }
                        // w00t, the rest is made of 0x00 bytes, so it
                        // exactly matches the upper limit.  kooky, very improbable,
                        // but possible
                        return false;
                    }
                }
            } else if (i > upperLimitByte) {
                // no bits set before or at the upper limit, so its
                // definitely not too large
                return false;
            }
        }
        _log.log(Log.CRIT, "wtf, gravity broke: distance=" + DataHelper.toHexString(distance) 
                           + ", end=" + _end, new Exception("moo"));
        return true;
    }
    
    /** 
     * Is the distance too small?
     *
     */
    private final boolean distanceIsTooSmall(byte distance[]) {
        int beginBit = Hash.HASH_LENGTH*8 - _begin;
        // It is too small if there are no bits set before the beginBit
        int beginByte = beginBit > 0 ? beginBit / 8 : 0;
        
        if (beginByte >= distance.length) {
            if (_begin == 0)
                return false;
            else
                return true;
        }
        
        for (int i = 0; i < distance.length; i++) {
            if ( (i < beginByte) && (distance[i] != 0x00) ) {
                return false;
            } else {
                if (i != beginByte) {
                    // zero value and too early... keep going
                    continue;
                } else {
                    int beginVal = 1 << (_begin % 8);
                    if (distance[i] >= beginVal) {
                        return false;
                    } else {
                        // no bits set prior to the beginVal
                        return true;
                    }
                }
            }
        }
        _log.log(Log.CRIT, "wtf, gravity broke!  distance=" + DataHelper.toHexString(distance) 
                           + " begin=" + _begin
                           + " beginBit=" + beginBit 
                           + " beginByte=" + beginByte, new Exception("moo"));
        return true;
    }
    
    public Set<Hash> getEntries() {
        Set<Hash> entries = new HashSet(_entries);
        return entries;
    }
    public Set<Hash> getEntries(Set toIgnoreHashes) {
        Set<Hash> entries = new HashSet(_entries);
        entries.removeAll(toIgnoreHashes);
        return entries;
    }
    
    public void getEntries(SelectionCollector collector) {
        Set<Hash> entries = new HashSet(_entries);
        for (Hash h : entries) {
                collector.add(h);
        }
    }
    
    public void setEntries(Set<Hash> entries) {
        _entries.clear();
        _entries.addAll(entries);
    }
    
    /**
     *  Todo: shuffling here is a hack and doesn't work since
     *  we switched back to a HashSet implementation
     */
    public int add(Hash peer) {
            _entries.add(peer);
/**********
            // Randomize the bucket every once in a while if we are floodfill, so that
            // exploration will return better results. See FloodfillPeerSelector.add(Hash).
            if (_lastShuffle + SHUFFLE_DELAY < _context.clock().now() &&
                !SearchJob.onlyQueryFloodfillPeers((RouterContext)_context)) {
                Collections.shuffle(_entries, _context.random());
                _lastShuffle = _context.clock().now();
            }
***********/
        return _entries.size();
    }
    
    public boolean remove(Hash peer) {
        return _entries.remove(peer);
    }
    
    /**
     * Generate a random key to go within this bucket
     *
     * WARNING - Something is seriously broken here. testRand2() fails right away.
     * ExploreKeySelectorJob is now disabled, ExploreJob just searches for a random
     * key instead.
     */
    public Hash generateRandomKey() {
        BigInteger variance = new BigInteger((_end-_begin)-1, _context.random());
        variance = variance.setBit(_begin);
        //_log.debug("Random variance for " + _size + " bits: " + variance);
        byte data[] = variance.toByteArray();
        byte hash[] = new byte[Hash.HASH_LENGTH];
        if (data.length <= Hash.HASH_LENGTH) {
            System.arraycopy(data, 0, hash, hash.length - data.length, data.length);
        } else {
            System.arraycopy(data, data.length - hash.length, hash, 0, hash.length);
        }
        Hash key = new Hash(hash);
        data = distanceFromLocal(key);
        hash = new byte[Hash.HASH_LENGTH];
        if (data.length <= Hash.HASH_LENGTH) {
            System.arraycopy(data, 0, hash, hash.length - data.length, data.length);
        } else {
            System.arraycopy(data, data.length - hash.length, hash, 0, hash.length);
        }
        key = new Hash(hash);
        return key;
    }
    
    public Hash getRangeBeginKey() {
        BigInteger lowerBounds = getLowerBounds();
        if ( (_local != null) && (_local.getData() != null) ) {
            lowerBounds = lowerBounds.xor(new BigInteger(1, _local.getData()));
        }
        
        byte data[] = lowerBounds.toByteArray();
        byte hash[] = new byte[Hash.HASH_LENGTH];
        if (data.length <= Hash.HASH_LENGTH) {
            System.arraycopy(data, 0, hash, hash.length - data.length, data.length);
        } else {
            System.arraycopy(data, data.length - hash.length, hash, 0, hash.length);
        }
        Hash key = new Hash(hash);
        return key;
    }
    
    public Hash getRangeEndKey() {
        BigInteger upperBounds = getUpperBounds();
        if ( (_local != null) && (_local.getData() != null) ) {
            upperBounds = upperBounds.xor(new BigInteger(1, _local.getData()));
        }
        byte data[] = upperBounds.toByteArray();
        byte hash[] = new byte[Hash.HASH_LENGTH];
        if (data.length <= Hash.HASH_LENGTH) {
            System.arraycopy(data, 0, hash, hash.length - data.length, data.length);
        } else {
            System.arraycopy(data, data.length - hash.length, hash, 0, hash.length);
        }
        Hash key = new Hash(hash);
        return key;
    }
    
    private BigInteger getUpperBounds() {
        return BigInteger.ZERO.setBit(_end);
    }
    private BigInteger getLowerBounds() {
        if (_begin == 0)
            return BigInteger.ZERO;
        else
            return BigInteger.ZERO.setBit(_begin);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("KBucketImpl: ");
        buf.append(_entries.toString()).append("\n");
        buf.append("Low bit: ").append(_begin).append(" high bit: ").append(_end).append('\n');
        buf.append("Local key: \n");
        if ( (_local != null) && (_local.getData() != null) )
            buf.append(toString(_local.getData())).append('\n');
        else
            buf.append("[undefined]\n");
        buf.append("Low and high keys:\n");
        buf.append(toString(getRangeBeginKey().getData())).append('\n');
        buf.append(toString(getRangeEndKey().getData())).append('\n');
        buf.append("Low and high deltas:\n");
        buf.append(getLowerBounds().toString(2)).append('\n');
        buf.append(getUpperBounds().toString(2)).append('\n');
        return buf.toString();
    }
    
    /**
     * Test harness to make sure its assigning keys to the right buckets
     *
     * WARNING - Something is seriously broken here. testRand2() fails right away.
     */
    public static void main(String args[]) {
        testRand2();
        testRand();
        testLimits();
        
        try { Thread.sleep(10000); } catch (InterruptedException ie) {}
    }
    
    private static void testLimits() {
        int low = 1;
        int high = 3;
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(KBucketImpl.class);
        KBucketImpl bucket = new KBucketImpl(I2PAppContext.getGlobalContext(), Hash.FAKE_HASH);
        bucket.setRange(low, high);
        Hash lowerBoundKey = bucket.getRangeBeginKey();
        Hash upperBoundKey = bucket.getRangeEndKey();
        boolean okLow = bucket.shouldContain(lowerBoundKey);
        boolean okHigh = bucket.shouldContain(upperBoundKey);
        if (okLow && okHigh)
            log.debug("Limit test ok");
        else
            log.error("Limit test failed!  ok low? " + okLow + " ok high? " + okHigh);
    }
    
    private static void testRand() {
        //StringBuilder buf = new StringBuilder(2048);
        int low = 1;
        int high = 3;
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(KBucketImpl.class);
        LocalHash local = new LocalHash(Hash.FAKE_HASH);
        local.prepareCache();
        KBucketImpl bucket = new KBucketImpl(I2PAppContext.getGlobalContext(), local);
        bucket.setRange(low, high);
        //Hash lowerBoundKey = bucket.getRangeBeginKey();
        //Hash upperBoundKey = bucket.getRangeEndKey();
        for (int i = 0; i < 100000; i++) {
            Hash rnd = bucket.generateRandomKey();
            //buf.append(toString(rnd.getData())).append('\n');
            boolean ok = bucket.shouldContain(rnd);
            if (!ok) {
                byte diff[] = bucket.getLocal().cachedXor(rnd);
                //BigInteger dv = new BigInteger(1, diff);
                //log.error("WTF! bucket doesn't want: \n" + toString(rnd.getData()) 
                //          + "\nDelta: \n" + toString(diff) + "\nDelta val: \n" + dv.toString(2) 
                //          + "\nBucket: \n"+bucket, new Exception("WTF"));
                log.error("wtf, bucket doesnt want a key that it generated.  i == " + i);
                log.error("\nLow: " + DataHelper.toHexString(bucket.getRangeBeginKey().getData()) 
                           + "\nVal: " + DataHelper.toHexString(rnd.getData())
                           + "\nHigh:" + DataHelper.toHexString(bucket.getRangeEndKey().getData()));
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
                System.exit(0);
            } else {
                //_log.debug("Ok, bucket wants: \n" + toString(rnd.getData()));
            }
            //_log.info("Low/High:\n" + toString(lowBounds.toByteArray()) + "\n" + toString(highBounds.toByteArray()));
        }
        log.info("Passed 100,000 random key generations against the null hash");
    }
    
    private static void testRand2() {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(KBucketImpl.class);
        int low = 1;
        int high = 200;
        byte hash[] = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(hash);
        LocalHash local = new LocalHash(hash);
        local.prepareCache();
        KBucketImpl bucket = new KBucketImpl(I2PAppContext.getGlobalContext(), local);
        bucket.setRange(low, high);
        //Hash lowerBoundKey = bucket.getRangeBeginKey();
        //Hash upperBoundKey = bucket.getRangeEndKey();
        for (int i = 0; i < 100000; i++) {
            Hash rnd = bucket.generateRandomKey();
            //buf.append(toString(rnd.getData())).append('\n');
            boolean ok = bucket.shouldContain(rnd);
            if (!ok) {
                byte diff[] = bucket.getLocal().cachedXor(rnd);
                //BigInteger dv = new BigInteger(1, diff);
                //log.error("WTF! bucket doesn't want: \n" + toString(rnd.getData()) 
                //          + "\nDelta: \n" + toString(diff) + "\nDelta val: \n" + dv.toString(2) 
                //          + "\nBucket: \n"+bucket, new Exception("WTF"));
                log.error("wtf, bucket doesnt want a key that it generated.  i == " + i);
                log.error("\nLow: " + DataHelper.toHexString(bucket.getRangeBeginKey().getData()) 
                           + "\nVal: " + DataHelper.toHexString(rnd.getData())
                           + "\nHigh:" + DataHelper.toHexString(bucket.getRangeEndKey().getData()));
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
                System.exit(0);
            } else {
                //_log.debug("Ok, bucket wants: \n" + toString(rnd.getData()));
            }
        }
        log.info("Passed 100,000 random key generations against a random hash");
    }
    
    private final static String toString(byte b[]) {
        if (true) return DataHelper.toHexString(b);
        StringBuilder buf = new StringBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            buf.append(toString(b[i]));
            buf.append(" ");
        }
        return buf.toString();
    }
    
    private final static String toString(byte b) {
        StringBuilder buf = new StringBuilder(8);
        for (int i = 7; i >= 0; i--) {
            boolean bb = (0 != (b & (1<<i)));
            if (bb)
                buf.append("1");
            else
                buf.append("0");
        }
        return buf.toString();
    }
}
