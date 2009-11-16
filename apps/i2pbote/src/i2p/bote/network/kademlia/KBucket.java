package i2p.bote.network.kademlia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Peers are sorted by the time of the most recent communication: Index 0 = least recent,
 * index n-1 = most recent.
 * 
 * @author HungryHobo@mail.i2p
 */
class KBucket implements Iterable<KademliaPeer> {
    static final BigInteger MIN_HASH_VALUE = BigInteger.ONE.negate().shiftLeft(Hash.HASH_LENGTH*8);   // system-wide minimum hash value
    static final BigInteger MAX_HASH_VALUE = BigInteger.ONE.shiftLeft(Hash.HASH_LENGTH*8).subtract(BigInteger.ONE);   // system-wide maximum hash value
    private static final int CAPACITY = KademliaConstants.K;   // The maximum number of peers the bucket can hold
    
    private Log log = new Log(KBucket.class);
    private BigInteger startId;
    private BigInteger endId;
    private List<KademliaPeer> peers;   // the list is always kept sorted by "last seen" time
    private Set<KademliaPeer> replacementCache;
    private int depth;
    private boolean replacementCacheEnabled;

    KBucket(BigInteger startId, BigInteger endId, int depth, boolean replacementCacheEnabled) {
        this.startId = startId;
        this.endId = endId;
        Comparator<KademliaPeer> peerComparator = createLastReceptionComparator();
        peers = new ArrayList<KademliaPeer>();
        replacementCache = new ConcurrentSkipListSet<KademliaPeer>(peerComparator);
        this.depth = depth;
        this.replacementCacheEnabled = replacementCacheEnabled;
    }
    
    BigInteger getStartId() {
        return startId;
    }
    
    BigInteger getEndId() {
        return endId;
    }
    
    List<KademliaPeer> getNodes() {
        // TODO only return peers that are not locked
        return peers;
    }
    
    void add(KademliaPeer node) {
        if (isFull())
            log.error("Error: adding a node to a full k-bucket. Bucket needs to be split first. Size=" + size() + ", capacity=" + CAPACITY);
        
        peers.add(node);
    }
    
    /**
     * Add a node to the bucket, splitting the bucket if necessary. If the bucket is split,
     * the newly created bucket is returned. Otherwise, return <code>null</code>.
     * 
     * If the bucket is full but cannot be split, the new node is added to the replacement
     * cache and <code>null</code> is returned.
     * @param peer
     * @return
     */
    synchronized KBucket addOrSplit(KademliaPeer peer) {
        if (!rangeContains(peer))
            log.error("Attempt to add a node whose hash is outside the bucket's range! Bucket start=" + startId + " Bucket end=" + endId + " peer hash=" + new BigInteger(peer.getDestinationHash().getData()));
        
        if (isFull() && !contains(peer)) {
            if (canSplit(peer)) {
                KBucket newBucket = split(peer.getDestinationHash());
                if (rangeContains(peer))
                    add(peer);
                else if (newBucket.rangeContains(peer))
                    newBucket.add(peer);
                else
                    log.error("After splitting a bucket, node is outside of both buckets' ranges.");
                return newBucket;
            }
            else {
                replacementCache.add(peer);
                return null;
            }
        }
        else {
            addOrUpdate(peer);
            return null;
        }
    }
    
    /**
     * Return <code>true</code> if the bucket should be split in order to make room for a new peer.
     * @return
     */
    private boolean canSplit(KademliaPeer peer) {
        return depth%KademliaConstants.B!=0 || rangeContains(peer);
    }
    
    /**
     * Update a known peer, or add the peer if it isn't known.
     * TODO If the bucket is full, the peer is added to the bucket's replacement cache.
     * @param destination
     * @return <code>true</code> if the peer was added (or replacement-cached),
     * <code>false</code> if it was updated.
     */
    boolean addOrUpdate(KademliaPeer peer) {
        // TODO log an error if peer outside bucket's range
        // TODO handle stale peers
        // TODO manage replacement cache
    	KademliaPeer existingPeer = getPeer(peer.getDestination());
        if (existingPeer == null) {
            add(peer);
            return true;
        }
        else {
        	existingPeer.setLastReception(peer.getLastReception());
        	// TODO move to end of list if lastReception is highest value, which it should be most of the time
        	return false;
        }
    }
    
    /**
     * Return <code>true</code> if a peer exists in the bucket.
     * @param peer
     * @return
     */
    boolean contains(KademliaPeer peer) {
        return getPeer(peer.getDestination()) != null;
    }

    /**
     * Return <code>true</code> if the bucket's Id range contains the hash of a given
     * peer, regardless if the bucket contains the peer; <code>false</code> if the hash
     * is outside the range.
     * @param peer
     * @return
     */
    private boolean rangeContains(KademliaPeer peer) {
        BigInteger peerHash = new BigInteger(peer.getDestinationHash().getData());
        return (startId.compareTo(peerHash)<=0 || endId.compareTo(peerHash)>0);
    }
    
    /**
     * Return a peer with a given I2P destination from the bucket, or <code>null</code> if the
     * peer isn't in the bucket.
     * @param destination
     * @return
     */
    KademliaPeer getPeer(Destination destination) {
        for (KademliaPeer peer: peers)
            if (peer.getDestination().equals(destination))
                return peer;
        return null;
    }
    
    KademliaPeer getClosestPeer(Hash key) {
        KademliaPeer closestPeer = null;
        BigInteger minDistance = MAX_HASH_VALUE;
        for (KademliaPeer peer: peers) {
            BigInteger distance = KademliaUtil.getDistance(key, peer.getDestination().calculateHash());
            if (distance.compareTo(minDistance) < 0) {
                closestPeer = peer;
                minDistance = distance;
            }
        }
        return closestPeer;
    }

/*    KademliaPeer getMostDistantPeer(KademliaPeer node) {
        return getMostDistantPeer(node.getDestinationHash());
    }*/
    
    KademliaPeer getMostDistantPeer(Hash key) {
        KademliaPeer mostDistantPeer = null;
        BigInteger maxDistance = BigInteger.ZERO;
        for (KademliaPeer peer: peers) {
            BigInteger distance = KademliaUtil.getDistance(key, peer.getDestination().calculateHash());
            if (distance.compareTo(maxDistance) > 0) {
                mostDistantPeer = peer;
                maxDistance = distance;
            }
        }
        return mostDistantPeer;
    }
    
    @Override
    public Iterator<KademliaPeer> iterator() {
        return peers.iterator();
    }
    
    void remove(Destination destination) {
        peers.remove(getPeer(destination));
    }

    /**
     * Remove a peer from the bucket. If the peer doesn't exist in the bucket, nothing happens.
     * @param node
     */
    void remove(KademliaPeer node) {
        peers.remove(node);
    }

    int size() {
        return peers.size();
    }
    
    boolean isFull() {
        return size() >= CAPACITY;
    }

    /**
     * Move half the nodes into a new bucket.
     * @return The new bucket
     */
    KBucket split() {
        depth++;
        KBucket newBucket = new KBucket(startId, endId, depth, replacementCacheEnabled);
        for (int i=0; i<peers.size()/2; i++) {
            KademliaPeer peer = peers.get(i);
            newBucket.add(peer);
            remove(peer);
        }
        return newBucket;
    }

    KBucket split(Hash hash) {
        return split(new BigInteger(hash.toBase64()));
    }
    
    KBucket split(BigInteger pivot) {
        depth++;
        KBucket newBucket = new KBucket(startId, pivot.subtract(BigInteger.ONE), depth, replacementCacheEnabled);
        startId = pivot;
        for (KademliaPeer peer: peers) {
            BigInteger nodeId = new BigInteger(peer.getDestination().calculateHash().getData());
            if (nodeId.compareTo(pivot) >= 0) {
                newBucket.add(peer);
                remove(peer);
            }
        }
        return newBucket;
    }
    
    private Comparator<KademliaPeer> createLastReceptionComparator() {
        return new Comparator<KademliaPeer>() {
            @Override
            public int compare(KademliaPeer peer1, KademliaPeer peer2) {
                return Long.valueOf(peer1.getLastReception()).compareTo(peer2.getLastReception());
            }
        };
    }
}