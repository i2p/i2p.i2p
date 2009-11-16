package i2p.bote.network.kademlia;

import i2p.bote.network.I2PSendQueue;
import i2p.bote.network.PacketListener;
import i2p.bote.packet.CommunicationPacket;
import i2p.bote.service.I2PBoteThread;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

// TODO if a sibling times out, refill the sibling table
public class BucketManager extends I2PBoteThread implements PacketListener {
    private static final int INTERVAL = 5 * 60 * 1000;
    private static final int PING_TIMEOUT = 20 * 1000;

    private Log log = new Log(BucketManager.class);
    private I2PSendQueue sendQueue;
    private List<KBucket> kBuckets;
    private KBucket siblingBucket;   // TODO [ordered furthest away to closest in terms of hash distance to local node]
    private Hash localDestinationHash;

    public BucketManager(I2PSendQueue sendQueue, Collection<KademliaPeer> initialPeers, Hash localDestinationHash) {
        super("BucketMgr");
        this.sendQueue = sendQueue;
        this.localDestinationHash = localDestinationHash;
        kBuckets = Collections.synchronizedList(new ArrayList<KBucket>());
        kBuckets.add(new KBucket(KBucket.MIN_HASH_VALUE, KBucket.MAX_HASH_VALUE, 0, true));   // this is the root bucket, so depth=0
        siblingBucket = new KBucket(KBucket.MIN_HASH_VALUE, KBucket.MAX_HASH_VALUE, 0, false);
        addAll(initialPeers);
    }
    
    public void addAll(Collection<KademliaPeer> nodes) {
        for (KademliaPeer node: nodes)
            addOrUpdate(node);
    }
    
    /**
     * Add a <code>{@link KademliaPeer}</code> to the sibling list or a bucket.
     * @param peer
     */
    public void addOrUpdate(KademliaPeer peer) {
        Hash peerHash = peer.getDestinationHash();
        log.debug("Adding/updating peer: Hash = " + peerHash);

        peer.resetStaleCounter();
        
        synchronized(this) {
            if (!siblingBucket.isFull() || siblingBucket.contains(peer)) {
                siblingBucket.addOrUpdate(peer);
                getBucket(peerHash).remove(peer);
            }
            else if (isCloserSibling(peer)) {
                KademliaPeer ejectedPeer = siblingBucket.getMostDistantPeer(localDestinationHash);
                
                addToBucket(ejectedPeer);
                siblingBucket.remove(ejectedPeer);
                
                siblingBucket.addOrUpdate(peer);
                getBucket(peerHash).remove(peer);
            }
            else
                addToBucket(peer);
        }
        logBucketStats();
            
/*        KademliaPeer ejectedPeer = addSibling(peer);
        // if the peer was added as a sibling, it may need to be removed from a bucket
        if (ejectedPeer != peer)
            getBucket(peerHash).remove(peer);
        // if the peer didn't get added as a sibling, try a bucket
        else
            addToBucket(peer);
        // if adding the peer to the list of siblings replaced another sibling, add the old sibling to a bucket
        if (ejectedPeer != null)
            addToBucket(ejectedPeer);*/
        
/*TODO        synchronized(siblings) {
            if (siblings.isFull()) {
                KBucket bucket = getBucket(nodeHash);
                KademliaPeer mostDistantSibling = getMostDistantSibling();
                if (getDistance(node.getDestinationHash()) < getDistance(mostDistantSibling)) {
                    bucket.addOrUpdate(mostDistantSibling);
                    siblings.remove(mostDistantSibling);
                    siblings.add(node);
                }
                else
                    bucket.addOrUpdate(node);
            }
            else {
                siblings.add(node);
        }*/
        
    }

    private void logBucketStats() {
        int numBuckets = kBuckets.size();
        int numPeers = getAllPeers().size();
        
        log.debug("#buckets=" + numBuckets + "+sibBkt, #peers=" + numPeers);
    }
    
    /**
     * Add a <code>{@link KademliaPeer}</code> to the appropriate bucket.
     * @param peer
     */
    private void addToBucket(KademliaPeer peer) {
        Hash nodeHash = peer.getDestinationHash();
        KBucket bucket = getBucket(nodeHash);
        KBucket newBucket = bucket.addOrSplit(peer);
        if (newBucket != null)
            kBuckets.add(newBucket);
    }

    /**
     * Return <code>true</code> if a given peer is closer to the local node than at
     * least one sibling. In other words, test if <code>peer</code> should replace
     * an existing sibling.
     * @param peer
     * @return
     */
    private boolean isCloserSibling(KademliaPeer peer) {
        BigInteger peerDistance = KademliaUtil.getDistance(peer, localDestinationHash);
        for (KademliaPeer sibling: siblingBucket) {
            BigInteger siblingDistance = KademliaUtil.getDistance(sibling.getDestinationHash(), localDestinationHash);
            if (peerDistance.compareTo(siblingDistance) < 0)
                return true;
        }
        return false;
    }
    
    /**
     * Add a peer to the sibling list if the list is not full, or if there is a node that can be
     * replaced.
     * 
     * If <code>peer</code> replaced an existing sibling, that sibling is returned.
     * If <code>peer</code> could not be added to the list, <code>peer</code> is returned.
     * If the list was not full, <code>null</code> is returned.
     * @param peer
     * @return
     */
/*    private KademliaPeer addSibling(KademliaPeer peer) {
        // no need to handle a replacement cache because the sibling bucket has none.
        KademliaPeer mostDistantSibling = siblingBucket.getMostDistantPeer(localDestinationHash);
        if (!siblingBucket.isFull()) {
            siblingBucket.add(peer);
            return null;
        }
        else if (new PeerDistanceComparator(localDestinationHash).compare(peer, mostDistantSibling) < 0) {
            siblingBucket.remove(mostDistantSibling);
            siblingBucket.add(peer);
            return mostDistantSibling;
        }
        else
            return peer;
    }*/
    
    public void remove(KademliaPeer peer) {
        Hash nodeHash = peer.getDestinationHash();
        getBucket(nodeHash).remove(peer);
    }
    
    /**
     * Do a binary search for the index of the bucket whose key range contains a given {@link Hash}.
     * @param key
     * @return
     */
    private int getBucketIndex(Hash key) {
        // initially, the search interval is 0..n-1
        int lowEnd = 0;
        int highEnd = kBuckets.size();
        
        BigInteger keyValue = new BigInteger(key.getData());
        while (lowEnd < highEnd) {
            int centerIndex = (highEnd + lowEnd) / 2;
            if (keyValue.compareTo(kBuckets.get(centerIndex).getStartId()) < 0)
                highEnd = centerIndex;
            else if (keyValue.compareTo(kBuckets.get(centerIndex).getEndId()) > 0)
                lowEnd = centerIndex;
            else
                return centerIndex;
        }
     
        log.error("This should never happen! No k-bucket found for hash: " + key);
        return -1;
    }
    
    /**
     * Do a binary search for the bucket whose key range contains a given {@link Hash}.
     * @param key
     * @return
     */
    private KBucket getBucket(Hash key) {
        return kBuckets.get(getBucketIndex(key));
    }
    
    /**
     * Return the <code>count</code> peers that are closest to a given key.
     * Less than <code>count</code> peers may be returned if there aren't
     * enough peers in the k-buckets.
     * @param key
     * @param count
     * @return
     */
    public Collection<KademliaPeer> getClosestPeers(Hash key, int count) {
        Collection<KademliaPeer> closestPeers = new ConcurrentHashSet<KademliaPeer>();
        
        // TODO don't put all peers in one huge list, only use two buckets at a time
        KademliaPeer[] allPeers = getAllPeersSortedByDistance(key);
        
        for (int i=0; i<count && i<allPeers.length; i++)
            closestPeers.add(allPeers[i]);
        
        return closestPeers;
    }

    private KademliaPeer[] getAllPeersSortedByDistance(Hash key) {
        List<KademliaPeer> allPeers = getAllPeers();
        KademliaPeer[] peerArray = getAllPeers().toArray(new KademliaPeer[allPeers.size()]);
        Arrays.sort(peerArray, new PeerDistanceComparator(key));
        return peerArray;
    }
    
    private List<KademliaPeer> getAllPeers() {
        List<KademliaPeer> allPeers = new ArrayList<KademliaPeer>();
        for (KBucket bucket: kBuckets)
            allPeers.addAll(bucket.getNodes());
        allPeers.addAll(siblingBucket.getNodes());
        return allPeers;
    }
    
    /**
     * Return all siblings of the local node (siblings are an S/Kademlia feature).
     * @return
     */
    public List<KademliaPeer> getSiblings() {
        List<KademliaPeer> siblingDestinations = new ArrayList<KademliaPeer>();
        for (KademliaPeer sibling: siblingBucket)
            siblingDestinations.add(sibling);
        return siblingDestinations;
    }

    /**
     * Return the total number of known Kademlia peers.
     * @return
     */
    public int getPeerCount() {
        int count = 0;
        for (KBucket bucket: kBuckets)
            count += bucket.size();
        count += siblingBucket.size();
        return count;
    }
    
    /**
     * "refresh all k-buckets further away than the closest neighbor. This refresh is just a lookup of a random key that is within that k-bucket range."
     */
    public void refreshAll() {
        for (KBucket bucket: kBuckets)
            refresh(bucket);
    }
    
    private void refresh(KBucket bucket) {
        byte[] randomHash = new byte[Hash.HASH_LENGTH];
        for (int i=0; i<Hash.HASH_LENGTH; i++)
            randomHash[i] = (byte)RandomSource.getInstance().nextInt(256);
        Hash key = new Hash(randomHash);
        getClosestPeers(key, KademliaConstants.K);
    }
    
/*    private void updatePeerList() {
        for (Destination peer: peers) {
            if (ping(peer))
                requestPeerList(peer);
        }
    }*/
    
    @Override
    public void run() {
        while (!shutdownRequested()) {
            try {
                // TODO replicate();
                // TODO updatePeerList(); refresh every bucket to which we haven't performed a node lookup in the past hour. Refreshing means picking a random ID in the bucket's range and performing a node search for that ID.
                sleep(INTERVAL);
            }
            catch (InterruptedException e) {
                log.debug("Thread '" + getName() + "' + interrupted", e);
            }
        }
    }

    // PacketListener implementation
    @Override
    public void packetReceived(CommunicationPacket packet, Destination sender, long receiveTime) {
        // any type of incoming packet updates the peer's record in the bucket/sibling list, or adds the peer to the bucket/sibling list
        addOrUpdate(new KademliaPeer(sender, receiveTime));
  }

    private class PeerDistanceComparator implements Comparator<KademliaPeer> {
        private Hash reference;
        
        PeerDistanceComparator(Hash reference) {
            this.reference = reference;
        }
        
        @Override
        public int compare(KademliaPeer peer1, KademliaPeer peer2) {
            BigInteger distance1 = KademliaUtil.getDistance(peer1.getDestinationHash(), reference);
            BigInteger distance2 = KademliaUtil.getDistance(peer2.getDestinationHash(), reference);
            return distance1.compareTo(distance2);
        }
    }
}