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
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

class KBucketImpl implements KBucket {
    private final static Log _log = new Log(KBucketImpl.class);
    private Set _entries; // PeerInfo structures
    private Hash _local;
    private int _begin; // if any bits equal or higher to this bit (in big endian order),
    private int _end;   // and no values higher than this bit (inclusive), include
    private BigInteger _lowerBounds; // lowest distance allowed from local
    private BigInteger _upperBounds; // one higher than the highest distance allowed from local
    private int _size; // integer value of the number of bits that can fit between lower and upper bounds
    
    public KBucketImpl(Hash local) {
	_entries = new HashSet();
	_local = local;
    }
    
    public int getRangeBegin() { return _begin; }
    public int getRangeEnd() { return _end; }
    public void setRange(int lowOrderBitLimit, int highOrderBitLimit) { 
	_begin = lowOrderBitLimit; 
	_end = highOrderBitLimit;
	if (_begin == 0)
	    _lowerBounds = BigInteger.ZERO;
	else
	    _lowerBounds = BigInteger.ZERO.setBit(_begin);
	_upperBounds = BigInteger.ZERO.setBit(_end);
	BigInteger diff = _upperBounds.subtract(_lowerBounds);
	_size = diff.bitLength();
	StringBuffer buf = new StringBuffer(1024);
	buf.append("Set range: ").append(lowOrderBitLimit).append(" through ").append(highOrderBitLimit).append('\n');
	buf.append("Local key, lowest allowed key, and highest allowed key: \n");
	Hash low = getRangeBeginKey();
	Hash high = getRangeEndKey();
	if ( (_local == null) || (_local.getData() == null) ) 
	    buf.append(toString(Hash.FAKE_HASH.getData())).append('\n');
	else
	    buf.append(toString(_local.getData())).append('\n');
	buf.append(toString(low.getData())).append('\n');
	buf.append(toString(high.getData()));
	//_log.debug(buf.toString());
    }
    public int getKeyCount() { 
	synchronized (_entries) {
	    return _entries.size(); 
	}
    }

    public Hash getLocal() { return _local; }
    public void setLocal(Hash local) { _local = local; }
    
    private byte[] distanceFromLocal(Hash key) {
	return DataHelper.xor(key.getData(), _local.getData());
    }
    
    public boolean shouldContain(Hash key) {
	// woohah, incredibly excessive object creation! whee!
	BigInteger kv = new BigInteger(1, distanceFromLocal(key));
	int lowComp = kv.compareTo(_lowerBounds);
	int highComp = kv.compareTo(_upperBounds);
	
	//_log.debug("kv.compareTo(low) = " + lowComp + " kv.compareTo(high) " + highComp);
	
	if ( (lowComp >= 0) && (highComp < 0) ) return true;
	return false;
    }
    
    public Set getEntries() {
	Set entries = new HashSet(64);
	synchronized (_entries) {
	    entries.addAll(_entries);
	}
	return entries;
    }
    public Set getEntries(Set toIgnoreHashes) {
	Set entries = new HashSet(64);
	synchronized (_entries) {
	    entries.addAll(_entries);
	    entries.removeAll(toIgnoreHashes);
	}
	return entries;
    }
    
    public void setEntries(Set entries) {
	synchronized (_entries) { 
	    _entries.clear();
	    _entries.addAll(entries);
	}
    }
    
    public int add(Hash peer) {
	synchronized (_entries) {
	    _entries.add(peer);
	    return _entries.size();
	}
    }
    
    public boolean remove(Hash peer) {
	synchronized (_entries) {
	    return _entries.remove(peer);
	}
    }
    
    /**
     * Generate a random key to go within this bucket
     *
     */
    public Hash generateRandomKey() {
	BigInteger variance = new BigInteger(_size-1, RandomSource.getInstance());
	variance = variance.add(_lowerBounds);
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
	BigInteger lowerBounds = _lowerBounds;
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
	BigInteger upperBounds = _upperBounds;
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
    
    public String toString() {
	StringBuffer buf = new StringBuffer(1024);
	buf.append("KBucketImpl: ");
	synchronized (_entries) {
	    buf.append(_entries.toString()).append("\n");
	}
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
	buf.append(_lowerBounds.toString(2)).append('\n');
	buf.append(_upperBounds.toString(2)).append('\n');
	return buf.toString();
    }
    
    /**
     * Test harness to make sure its assigning keys to the right buckets
     *
     */    
    public static void main(String args[]) {
	testRand2();
	testRand();
	
	try { Thread.sleep(10000); } catch (InterruptedException ie) {}
    }
    
    private static void testRand() {
	StringBuffer buf = new StringBuffer(2048);
	int low = 1;
	int high = 3;
	KBucketImpl bucket = new KBucketImpl(Hash.FAKE_HASH);
	bucket.setRange(low, high);
	Hash lowerBoundKey = bucket.getRangeBeginKey();
	Hash upperBoundKey = bucket.getRangeEndKey();
	for (int i = 0; i < 100; i++) {
	    Hash rnd = bucket.generateRandomKey();
	    //buf.append(toString(rnd.getData())).append('\n');
	    boolean ok = bucket.shouldContain(rnd);
	    if (!ok) {
		byte diff[] = DataHelper.xor(rnd.getData(), bucket.getLocal().getData());
		BigInteger dv = new BigInteger(1, diff);
		_log.error("WTF! bucket doesn't want: \n" + toString(rnd.getData()) + "\nDelta: \n" + toString(diff) + "\nDelta val: \n" + dv.toString(2) + "\nBucket: \n"+bucket, new Exception("WTF"));
		try { Thread.sleep(1000); } catch (Exception e) {}
		System.exit(0);
	    } else {
		//_log.debug("Ok, bucket wants: \n" + toString(rnd.getData()));
	    }
	    //_log.info("Low/High:\n" + toString(lowBounds.toByteArray()) + "\n" + toString(highBounds.toByteArray()));
	}
	_log.info("Passed 100 random key generations against the null hash");
    }
    
    private static void testRand2() {
	StringBuffer buf = new StringBuffer(1024*1024*16);
	int low = 1;
	int high = 200;
	byte hash[] = new byte[Hash.HASH_LENGTH];
	RandomSource.getInstance().nextBytes(hash);
	KBucketImpl bucket = new KBucketImpl(new Hash(hash));
	bucket.setRange(low, high);
	Hash lowerBoundKey = bucket.getRangeBeginKey();
	Hash upperBoundKey = bucket.getRangeEndKey();
	for (int i = 0; i < 1000; i++) {
	    Hash rnd = bucket.generateRandomKey();
	    buf.append(toString(rnd.getData())).append('\n');
	    boolean ok = bucket.shouldContain(rnd);
	    if (!ok) {
		byte diff[] = DataHelper.xor(rnd.getData(), bucket.getLocal().getData());
		BigInteger dv = new BigInteger(1, diff);
		_log.error("WTF! bucket doesn't want: \n" + toString(rnd.getData()) + "\nDelta: \n" + toString(diff) + "\nDelta val: \n" + dv.toString(2) + "\nBucket: \n"+bucket, new Exception("WTF"));
		try { Thread.sleep(1000); } catch (Exception e) {}
		System.exit(0);
	    } else {
		//_log.debug("Ok, bucket wants: \n" + toString(rnd.getData()));
	    }
	}
	_log.info("Passed 1000 random key generations against a random hash\n" + buf.toString());	
    }
    
    private final static String toString(byte b[]) {
	StringBuffer buf = new StringBuffer(b.length);
	for (int i = 0; i < b.length; i++) {
	    buf.append(toString(b[i]));
	    buf.append(" ");
	}
	return buf.toString();
    }
    
    private final static String toString(byte b) {
	StringBuffer buf = new StringBuffer(8);
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
