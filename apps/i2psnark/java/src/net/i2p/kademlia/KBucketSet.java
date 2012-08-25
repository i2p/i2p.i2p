package net.i2p.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.Log;

/**
 * In-memory storage of buckets sorted by the XOR metric from the base (us)
 * passed in via the constructor.
 * This starts with one bucket covering the whole key space, and
 * may eventually be split to a max of the number of bits in the data type
 * (160 for SHA1Hash or 256 for Hash),
 * times 2**(B-1) for Kademlia value B.
 *
 * Refactored from net.i2p.router.networkdb.kademlia
 * @since 0.9.2
 */
public class KBucketSet<T extends SimpleDataStructure> {
    private final Log _log;
    private final I2PAppContext _context;
    private final T _us;

    /**
     * The bucket list is locked by _bucketsLock, however the individual
     * buckets are not locked. Users may see buckets that have more than
     * the maximum k entries, or may have adds and removes silently fail
     * when they appear to succeed.
     *
     * Closest values are in bucket 0, furthest are in the last bucket.
     */
    private final List<KBucket> _buckets;
    private final Range<T> _rangeCalc;
    private final KBucketTrimmer _trimmer;
    
    /**
     *  Locked for reading only when traversing all the buckets.
     *  Locked for writing only when splitting a bucket.
     *  Adds/removes/gets from individual buckets are not locked.
     */
    private final ReentrantReadWriteLock _bucketsLock = new ReentrantReadWriteLock(false);

    private final int KEYSIZE_BITS;
    private final int NUM_BUCKETS;
    private final int BUCKET_SIZE;
    private final int B_VALUE;
    private final int B_FACTOR;
    
    /**
     * Use the default trim strategy, which removes a random entry.
     * @param us the local identity (typically a SHA1Hash or Hash)
     *           The class must have a zero-argument constructor.
     * @param max the Kademlia value "k", the max per bucket, k >= 4
     * @param b the Kademlia value "b", split buckets an extra 2**(b-1) times,
     *           b > 0, use 1 for bittorrent, Kademlia paper recommends 5
     */
    public KBucketSet(I2PAppContext context, T us, int max, int b) {
        this(context, us, max, b, new RandomTrimmer(context, max));
    }

    /**
     * Use the supplied trim strategy.
     */
    public KBucketSet(I2PAppContext context, T us, int max, int b, KBucketTrimmer trimmer) {
        _us = us;
        _context = context;
        _log = context.logManager().getLog(KBucketSet.class);
        _trimmer = trimmer;
        if (max <= 4 || b <= 0 || b > 8)
            throw new IllegalArgumentException();
        KEYSIZE_BITS = us.length() * 8;
        B_VALUE = b;
        B_FACTOR = 1 << (b - 1);
        NUM_BUCKETS = KEYSIZE_BITS * B_FACTOR;
        BUCKET_SIZE = max;
        _buckets = createBuckets();
        _rangeCalc = new Range(us, B_VALUE);
        // this verifies the zero-argument constructor
        makeKey(new byte[us.length()]);
    }
    
    private void getReadLock() {
        _bucketsLock.readLock().lock();
    }

    /**
     *  Get the lock if we can. Non-blocking.
     *  @return true if the lock was acquired
     */
    private boolean tryReadLock() {
        return _bucketsLock.readLock().tryLock();
    }

    private void releaseReadLock() {
        _bucketsLock.readLock().unlock();
    }

    /** @return true if the lock was acquired */
    private boolean getWriteLock() {
        try {
            boolean rv = _bucketsLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldLog(Log.WARN))
                _log.warn("no lock, size is: " + _bucketsLock.getQueueLength(), new Exception("rats"));
            return rv;
        } catch (InterruptedException ie) {}
        return false;
    }

    private void releaseWriteLock() {
        _bucketsLock.writeLock().unlock();
    }

    /**
     * @return true if the peer is new to the bucket it goes in, or false if it was
     *  already in it. Always returns false on an attempt to add ourselves.
     *
     */
    public boolean add(T peer) {
        KBucket bucket;
        getReadLock();
        try {
            bucket = getBucket(peer);
        } finally { releaseReadLock(); }
        if (bucket != null) {
            if (bucket.add(peer)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Peer " + peer + " added to bucket " + bucket);
                if (shouldSplit(bucket)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Splitting bucket " + bucket);
                    split(bucket.getRangeBegin());
                    //testAudit(this, _log);
                }
                return true;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Peer " + peer + " NOT added to bucket " + bucket);
                return false;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to add, probably us: " + peer);
            return false;
        }
    }

    /**
     *  No lock required.
     *  FIXME will split the closest buckets too far if B > 1 and K < 2**B
     *  Won't ever really happen and if it does it still works.
     */
    private boolean shouldSplit(KBucket b) {
        return
               b.getRangeBegin() != b.getRangeEnd() &&
               b.getKeyCount() > BUCKET_SIZE;
    }

    /**
     *  Grabs the write lock.
     *  Caller must NOT have the read lock.
     *  The bucket should be splittable (range start != range end).
     *  @param r the range start of the bucket to be split
     */
    private void split(int r) {
        if (!getWriteLock())
            return;
        try {
            locked_split(r);
        } finally { releaseWriteLock(); }
    }

    /**
     *  Creates two or more new buckets. The old bucket is replaced and discarded.
     *
     *  Caller must hold write lock
     *  The bucket should be splittable (range start != range end).
     *  @param r the range start of the bucket to be split
     */
    private void locked_split(int r) {
        int b = pickBucket(r);
        while (shouldSplit(_buckets.get(b))) {
            KBucket<T> b0 = _buckets.get(b);
            // Each bucket gets half the keyspace.
            // When B_VALUE = 1, or the bucket is larger than B_FACTOR, then
            // e.g. 0-159 => 0-158, 159-159
            // When B_VALUE > 1, and the bucket is smaller than B_FACTOR, then
            // e.g. 1020-1023 => 1020-1021, 1022-1023
            int s1, e1, s2, e2;
            s1 = b0.getRangeBegin();
            e2 = b0.getRangeEnd();
            if (B_FACTOR > 1 &&
                (s1 & (B_FACTOR - 1)) == 0 &&
                ((e2 + 1) & (B_FACTOR - 1)) == 0 &&
                e2 > s1 + B_FACTOR) {
                // The bucket is a "whole" kbucket with a range > B_FACTOR,
                // so it should be split into two "whole" kbuckets each with
                // a range >= B_FACTOR.
                // Log split
                s2 = e2 + 1 - B_FACTOR;
            } else {
                // The bucket is the smallest "whole" kbucket with a range == B_FACTOR,
                // or B_VALUE > 1 and the bucket has already been split.
                // Start or continue splitting down to a depth B_VALUE.
                // Linear split
                s2 = s1 + ((1 + e2 - s1) / 2);
            }	
            e1 = s2 - 1;
            if (_log.shouldLog(Log.INFO))
                _log.info("Splitting (" + s1 + ',' + e2 + ") -> (" + s1 + ',' + e1 + ") (" + s2 + ',' + e2 + ')');
            KBucket<T> b1 = createBucket(s1, e1);
            KBucket<T> b2 = createBucket(s2, e2);
            for (T key : b0.getEntries()) {
                if (getRange(key) < s2)
                    b1.add(key);
                else
                    b2.add(key);
            }
            _buckets.set(b, b1);
            _buckets.add(b + 1, b2);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Split bucket at idx " + b +
                           ":\n" + b0 +
                           "\ninto: " + b1 +
                           "\nand: " + b2);
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("State is now: " + toString());

            if (b2.getKeyCount() > BUCKET_SIZE) {
                // should be rare... too hard to call _trimmer from here
                // (and definitely not from inside the write lock)
                if (_log.shouldLog(Log.INFO))
                    _log.info("All went into 2nd bucket after split");
            }
            // loop if all the entries went in the first bucket
        }
    }

    /**
     *  The current number of entries.
     */
    public int size() {
        int rv = 0;
        getReadLock();
        try {
            for (KBucket b : _buckets) {
                rv += b.getKeyCount();
            }
        } finally { releaseReadLock(); }
        return rv;
    }
    
    public boolean remove(T entry) {
        KBucket kbucket;
        getReadLock();
        try {
            kbucket = getBucket(entry);
        } finally { releaseReadLock(); }
        boolean removed = kbucket.remove(entry);
        return removed;
    }
    
    /** @since 0.8.8 */
    public void clear() {
        getReadLock();
        try {
            for (KBucket b : _buckets) {
                b.clear();
            }
        } finally { releaseReadLock(); }
        _rangeCalc.clear();
    }
    
    /**
     *  @return a copy in a new set
     */
    public Set<T> getAll() {
        Set<T> all = new HashSet(256);
        getReadLock();
        try {
            for (KBucket b : _buckets) {
                all.addAll(b.getEntries());
            }
        } finally { releaseReadLock(); }
        return all;
    }

    /**
     *  @return a copy in a new set
     */
    public Set<T> getAll(Set<T> toIgnore) {
        Set<T> all = getAll();
        all.removeAll(toIgnore);
        return all;
    }
    
    public void getAll(SelectionCollector<T> collector) {
        getReadLock();
        try {
            for (KBucket b : _buckets) {
                b.getEntries(collector);
            }
        } finally { releaseReadLock(); }
    }
    
    /**
     *  The keys closest to us.
     *  Returned list will never contain us.
     *  @return non-null, closest first
     */
    public List<T> getClosest(int max) {
        return getClosest(max, Collections.EMPTY_SET);
    }
    
    /**
     *  The keys closest to us.
     *  Returned list will never contain us.
     *  @return non-null, closest first
     */
    public List<T> getClosest(int max, Collection<T> toIgnore) {
        List<T> rv = new ArrayList(max);
        int count = 0;
        getReadLock();
        try {
            // start at first (closest) bucket
            for (int i = 0; i < _buckets.size() && count < max; i++) {
                Set<T> entries = _buckets.get(i).getEntries();
                // add the whole bucket except for ignores,
                // extras will be trimmed after sorting
                for (T e : entries) {
                    if (!toIgnore.contains(e)) {
                        rv.add(e);
                        count++;
                    }
                }
            }
        } finally { releaseReadLock(); }
        Comparator comp = new XORComparator(_us);
        Collections.sort(rv, comp);
        int sz = rv.size();
        for (int i = sz - 1; i >= max; i--) {
            rv.remove(i);
        }
        return rv;
    }
    
    /**
     *  The keys closest to the key.
     *  Returned list will never contain us.
     *  @return non-null, closest first
     */
    public List<T> getClosest(T key, int max) {
        return getClosest(key, max, Collections.EMPTY_SET);
    }
    
    /**
     *  The keys closest to the key.
     *  Returned list will never contain us.
     *  @return non-null, closest first
     */
    public List<T> getClosest(T key, int max, Collection<T> toIgnore) {
        if (key.equals(_us))
            return getClosest(max, toIgnore);
        List<T> rv = new ArrayList(max);
        int count = 0;
        getReadLock();
        try {
            int start = pickBucket(key);
            // start at closest bucket, then to the smaller (closer to us) buckets
            for (int i = start; i >= 0 && count < max; i--) {
                Set<T> entries = _buckets.get(i).getEntries();
                for (T e : entries) {
                    if (!toIgnore.contains(e)) {
                        rv.add(e);
                        count++;
                    }
                }
            }
            // then the farther from us buckets if necessary
            for (int i = start + 1; i < _buckets.size() && count < max; i++) {
                Set<T> entries = _buckets.get(i).getEntries();
                for (T e : entries) {
                    if (!toIgnore.contains(e)) {
                        rv.add(e);
                        count++;
                    }
                }
            }
        } finally { releaseReadLock(); }
        Comparator comp = new XORComparator(key);
        Collections.sort(rv, comp);
        int sz = rv.size();
        for (int i = sz - 1; i >= max; i--) {
            rv.remove(i);
        }
        return rv;
    }

    /**
     *  The bucket number (NOT the range number) that the xor of the key goes in
     *  Caller must hold read lock
     *  @return 0 to max-1 or -1 for us
     */
    private int pickBucket(T key) {
        int range = getRange(key);
        if (range < 0)
            return -1;
        int rv = pickBucket(range);
        if (rv >= 0) {
             return rv;
        }
        _log.error("Key does not fit in any bucket?! WTF!\nKey  : [" 
                   + DataHelper.toHexString(key.getData()) + "]" 
                   + "\nUs   : " + _us
                   + "\nDelta: ["
                   + DataHelper.toHexString(DataHelper.xor(_us.getData(), key.getData()))
                   + "]", new Exception("WTF"));
        _log.error(toString());
        throw new IllegalStateException("pickBucket returned " + rv);
        //return -1;
    }
    
    /**
     *  Returned list is a copy of the bucket list, closest first,
     *  with the actual buckets (not a copy).
     *
     *  Primarily for testing. You shouldn't ever need to get all the buckets.
     *  Use getClosest() or getAll() instead to get the keys.
     *
     *  @return non-null
     */
    List<KBucket<T>> getBuckets() {
        getReadLock();
        try {
            return new ArrayList(_buckets);
        } finally { releaseReadLock(); }
    }

    /**
     *  The bucket that the xor of the key goes in
     *  Caller must hold read lock
     *  @return null if key is us
     */
    private KBucket getBucket(T key) {
       int bucket = pickBucket(key);
       if (bucket < 0)
           return null;
       return _buckets.get(bucket);
    }
    
    /**
     *  The bucket number that contains this range number
     *  Caller must hold read lock or write lock
     *  @return 0 to max-1 or -1 for us
     */
    private int pickBucket(int range) {
        // If B is small, a linear search from back to front
        // is most efficient since most of the keys are at the end...
        // If B is larger, there's a lot of sub-buckets
        // of equal size to be checked so a binary search is better
        if (B_VALUE <= 3) {
            for (int i = _buckets.size() - 1; i >= 0; i--) {
                KBucket b = _buckets.get(i);
                if (range >= b.getRangeBegin() && range <= b.getRangeEnd())
                    return i;
            }
            return -1;
        } else {
            KBucket dummy = new DummyBucket(range);
            return Collections.binarySearch(_buckets, dummy, new BucketComparator());
        }
    }

    private List<KBucket> createBuckets() {
        // just an initial size
        List<KBucket> buckets = new ArrayList(4 * B_FACTOR);
        buckets.add(createBucket(0, NUM_BUCKETS -1));
        return buckets;
    }
    
    private KBucket createBucket(int start, int end) {
        if (end - start >= B_FACTOR &&
            (((end + 1) & B_FACTOR - 1) != 0 ||
             (start & B_FACTOR - 1) != 0))
            throw new IllegalArgumentException("Sub-bkt crosses K-bkt boundary: " + start + '-' + end);
        KBucket bucket = new KBucketImpl(_context, start, end, BUCKET_SIZE, _trimmer);
        return bucket;
    }
    
    /**
     *  The number of bits minus 1 (range number) for the xor of the key.
     *  Package private for testing only. Others shouldn't need this.
     *  @return 0 to max-1 or -1 for us
     */
    int getRange(T key) {
        return _rangeCalc.getRange(key);
    }
    
    /**
     *  For every bucket that hasn't been updated in this long,
     *  generate a random key that would be a member of that bucket.
     *  The returned keys may be searched for to "refresh" the buckets.
     *  @return non-null, closest first
     */
    public List<T> getExploreKeys(long age) {
        List<T> rv = new ArrayList(_buckets.size());
        long old = _context.clock().now() - age;
        getReadLock();
        try {
            for (KBucket b : _buckets) {
                if (b.getLastChanged() < old)
                    rv.add(generateRandomKey(b));
            }
        } finally { releaseReadLock(); }
        return rv;
    }
    
    /**
     *  Generate a random key to go within this bucket
     *  Package private for testing only. Others shouldn't need this.
     */
    T generateRandomKey(KBucket bucket) {
        int begin = bucket.getRangeBegin();
        int end = bucket.getRangeEnd();
        // number of fixed bits, out of B_VALUE - 1 bits
        int fixed = 0;
        int bsz = 1 + end - begin;
        // compute fixed = B_VALUE - log2(bsz)
        // e.g for B=4, B_FACTOR=8, sz 4-> fixed 1, sz 2->fixed 2, sz 1 -> fixed 3
        while (bsz < B_FACTOR) {
            fixed++;
            bsz <<= 1;
        }
        int fixedBits = 0;
        if (fixed > 0) {
            // 0x01, 03, 07, 0f, ...
            int mask = (1 << fixed) - 1;
            // fixed bits masked from begin
            fixedBits = (begin >> (B_VALUE - (fixed + 1))) & mask;
        }
        int obegin = begin;
        int oend = end;
        begin >>= (B_VALUE - 1);
        end >>= (B_VALUE - 1);
        // we need randomness for [0, begin) bits
        BigInteger variance;
        // 00000000rrrr
        if (begin > 0)
            variance = new BigInteger(begin - fixed, _context.random());
        else
            variance = BigInteger.ZERO;
        // we need nonzero randomness for [begin, end] bits
        int numNonZero = 1 + end - begin;
        if (numNonZero == 1) {
            // 00001000rrrr
            variance = variance.setBit(begin);
            // fixed bits as the 'main' bucket is split
            // 00001fffrrrr
            if (fixed > 0)
                variance = variance.or(BigInteger.valueOf(fixedBits).shiftLeft(begin - fixed));
        } else {
            // dont span main bucket boundaries with depth > 1
            if (fixed > 0)
                throw new IllegalStateException("WTF " + bucket);
            BigInteger nonz;
            if (numNonZero <= 62) {
                // add one to ensure nonzero
                long nz = 1 + _context.random().nextLong((1l << numNonZero) - 1);
                nonz = BigInteger.valueOf(nz);
            } else {
                // loop to ensure nonzero
                do {
                    nonz = new BigInteger(numNonZero, _context.random());
                } while (nonz.equals(BigInteger.ZERO));
            }
            // shift left and or-in the nonzero randomness
            if (begin > 0)
                nonz = nonz.shiftLeft(begin);
            // 0000nnnnrrrr
            variance = variance.or(nonz);
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("SB(" + obegin + ',' + oend + ") KB(" + begin + ',' + end + ") fixed=" + fixed + " fixedBits=" + fixedBits + " numNonZ=" + numNonZero);
        byte data[] = variance.toByteArray();
        T key = makeKey(data);
        byte[] hash = DataHelper.xor(key.getData(), _us.getData());
        T rv = makeKey(hash);

        // DEBUG
        //int range = getRange(rv);
        //if (range < obegin || range > oend) {
        //    throw new IllegalStateException("Generate random key failed range=" + range + " for " + rv + " meant for bucket " + bucket);
        //}

        return rv;
    }
    
    /**
     *  Make a new SimpleDataStrucure from the data
     *  @param data size <= SDS length, else throws IAE
     *              Can be 1 bigger if top byte is zero
     */
    private T makeKey(byte[] data) {
        int len = _us.length();
        int dlen = data.length;
        if (dlen > len + 1 ||
            (dlen == len + 1 && data[0] != 0))
            throw new IllegalArgumentException("bad length " + dlen + " > " + len);
        T rv;
        try {
            rv = (T) _us.getClass().newInstance();
        } catch (Exception e) {
            _log.error("fail", e);
            throw new RuntimeException(e);
        }
        if (dlen == len) {
            rv.setData(data);
        } else {
            byte[] ndata = new byte[len];
            if (dlen == len + 1) {
                // one bigger
                System.arraycopy(data, 1, ndata, 0, len);
            } else {
                // smaller
                System.arraycopy(data, 0, ndata, len - dlen, dlen);
            }
            rv.setData(ndata);
        }
        return rv;
    }

    private static class Range<T extends SimpleDataStructure> {
        private final int _bValue;
        private final BigInteger _bigUs;
        private final Map<T, Integer> _distanceCache;

        public Range(T us, int bValue) {
            _bValue = bValue;
            _bigUs = new BigInteger(1, us.getData());
            _distanceCache = new LHM(256);
        }

        /** @return 0 to max-1 or -1 for us */
        public int getRange(T key) {
            Integer rv;
            synchronized (_distanceCache) {
                rv = _distanceCache.get(key);
                if (rv == null) {
                    // easy way when _bValue == 1
                    //rv = Integer.valueOf(_bigUs.xor(new BigInteger(1, key.getData())).bitLength() - 1);
                    BigInteger xor = _bigUs.xor(new BigInteger(1, key.getData()));
                    int range = xor.bitLength() - 1;
                    if (_bValue > 1) {
                        int toShift = range + 1 - _bValue;
                        int highbit = range;
                        range <<= _bValue - 1;
                        if (toShift >= 0) {
                            int extra = xor.clearBit(highbit).shiftRight(toShift).intValue();
                            range += extra;
                            //Log log = I2PAppContext.getGlobalContext().logManager().getLog(KBucketSet.class);
                            //if (log.shouldLog(Log.DEBUG))
                            //    log.debug("highbit " + highbit + " toshift " + toShift + " extra " + extra + " new " + range);
                        }
                    }
                    rv = Integer.valueOf(range);
                    _distanceCache.put(key, rv);
                }
            }
            return rv.intValue();
        }

        public void clear() {
            synchronized (_distanceCache) {
                _distanceCache.clear();
            }
        }
    }

    private static class LHM<K, V> extends LinkedHashMap<K, V> {
        private final int _max;

        public LHM(int max) {
            super(max, 0.75f, true);
            _max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > _max;
        }
    }

    /**
     *  For Collections.binarySearch.
     *  getRangeBegin == getRangeEnd.
     */
    private static class DummyBucket<T extends SimpleDataStructure> implements KBucket<T> {
        private final int r;

        public DummyBucket(int range) {
            r = range;
        }

        public int getRangeBegin() { return r; }
        public int getRangeEnd() { return r; }

        public int getKeyCount() {
            return 0;
        }

        public Set<T> getEntries() {
            throw new UnsupportedOperationException();
        }

        public void getEntries(SelectionCollector<T> collector) {
            throw new UnsupportedOperationException();
        }
    
        public void clear() {}

        public boolean add(T peer) {
            throw new UnsupportedOperationException();
        }
    
        public boolean remove(T peer) {
            return false;
        }

        public void setLastChanged() {}

        public long getLastChanged() {
            return 0;
        }
    }

    /**
     *  For Collections.binarySearch.
     *  Returns equal for any overlap.
     */
    private static class BucketComparator implements Comparator<KBucket> {
        public int compare(KBucket l, KBucket r) {
            if (l.getRangeEnd() < r.getRangeBegin())
                return -1;
            if (l.getRangeBegin() > r.getRangeEnd())
                return 1;
            return 0;
        }
    }

    /**
     *  Called when a kbucket can no longer be split and is too big
     */
    public interface KBucketTrimmer<K extends SimpleDataStructure> {
        /**
         *  Called from add() just before adding the entry.
         *  You may call getEntries() and/or remove() from here.
         *  Do NOT call add().
         *  To always discard a newer entry, always return false.
         *
         *  @param kbucket the kbucket that is now too big
         *  @return true to actually add the entry.
         */
        public boolean trim(KBucket<K> kbucket, K toAdd);
    }

    /**
     *  Removes a random element. Not resistant to flooding.
     */
    public static class RandomTrimmer<T extends SimpleDataStructure> implements KBucketTrimmer<T> {
        protected final I2PAppContext _ctx;
        private final int _max;

        public RandomTrimmer(I2PAppContext ctx, int max) {
            _ctx = ctx;
            _max = max;
        }

        public boolean trim(KBucket<T> kbucket, T toAdd) {
            List<T> e = new ArrayList(kbucket.getEntries());
            int sz = e.size();
            // concurrency
            if (sz < _max)
                return true;
            T toRemove = e.get(_ctx.random().nextInt(sz));
            return kbucket.remove(toRemove);
        }
    }

    /**
     *  Removes a random element, but only if the bucket hasn't changed in 5 minutes.
     */
    public static class RandomIfOldTrimmer<T extends SimpleDataStructure> extends RandomTrimmer<T> {

        public RandomIfOldTrimmer(I2PAppContext ctx, int max) {
            super(ctx, max);
        }

        public boolean trim(KBucket<T> kbucket, T toAdd) {
            if (kbucket.getLastChanged() > _ctx.clock().now() - 5*60*1000)
                return false;
            return super.trim(kbucket, toAdd);
        }
    }

    /**
     *  Removes nothing and always rejects the add. Flood resistant..
     */
    public static class RejectTrimmer<T extends SimpleDataStructure> implements KBucketTrimmer<T> {
        public boolean trim(KBucket<T> kbucket, T toAdd) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("Bucket set rooted on: ").append(_us.toString())
           .append(" K= ").append(BUCKET_SIZE)
           .append(" B= ").append(B_VALUE)
           .append(" with ").append(size())
           .append(" keys in ").append(_buckets.size()).append(" buckets:\n");
        getReadLock();
        try {
            int len = _buckets.size();
            for (int i = 0; i < len; i++) {
                KBucket b = _buckets.get(i);
                buf.append("* Bucket ").append(i).append("/").append(len).append(": ");
                buf.append(b.toString()).append("\n");
            }
        } finally { releaseReadLock(); }
        return buf.toString();
    }
}
