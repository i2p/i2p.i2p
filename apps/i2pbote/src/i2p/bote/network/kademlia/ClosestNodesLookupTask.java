package i2p.bote.network.kademlia;

import i2p.bote.network.I2PPacketDispatcher;
import i2p.bote.network.I2PSendQueue;
import i2p.bote.network.PacketListener;
import i2p.bote.packet.CommunicationPacket;
import i2p.bote.packet.DataPacket;
import i2p.bote.packet.PeerList;
import i2p.bote.packet.ResponsePacket;
import i2p.bote.packet.UniqueId;
import i2p.bote.packet.dht.FindClosePeersPacket;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

public class ClosestNodesLookupTask implements Runnable {
    private static final int REQUEST_TIMEOUT = 30 * 1000;
    private static final int CLOSEST_NODES_LOOKUP_TIMEOUT = 2 * 60 * 1000;   // the maximum amount of time a FIND_CLOSEST_NODES can take
    
    private Log log = new Log(ClosestNodesLookupTask.class);
    private Hash key;
    private I2PPacketDispatcher i2pReceiver;
    private BucketManager bucketManager;
    private Random randomNumberGenerator;
    private I2PSendQueue sendQueue;
    private Set<Destination> responseReceived;
    private Set<KademliaPeer> notQueriedYet;
    private Set<FindClosePeersPacket> pendingRequests;
    
    public ClosestNodesLookupTask(Hash key, I2PSendQueue sendQueue, I2PPacketDispatcher i2pReceiver, BucketManager bucketManager) {
        this.key = key;
        this.sendQueue = sendQueue;
        this.i2pReceiver = i2pReceiver;
        this.bucketManager = bucketManager;
        randomNumberGenerator = new Random(getTime());
        responseReceived = new TreeSet<Destination>(new HashDistanceComparator(key));   // nodes that have responded to a query; sorted by distance to the key
        notQueriedYet = new ConcurrentHashSet<KademliaPeer>();   // peers we haven't contacted yet
        pendingRequests = new ConcurrentHashSet<FindClosePeersPacket>();   // outstanding queries
    }
    
    @Override
    public void run() {
        log.debug("Looking up nodes closest to " + key);
        
        PacketListener packetListener = new IncomingPacketHandler();
        i2pReceiver.addPacketListener(packetListener);
        
        // prepare a list of close nodes (might need more than alpha if they don't all respond)
        notQueriedYet.addAll(bucketManager.getClosestPeers(key, KademliaConstants.S));
        
        long startTime = getTime();
        while (true) {
            // send new requests if less than alpha are pending
            while (pendingRequests.size()<KademliaConstants.ALPHA && !notQueriedYet.isEmpty()) {
                KademliaPeer peer = selectRandom(notQueriedYet);
                notQueriedYet.remove(peer);
                FindClosePeersPacket packet = new FindClosePeersPacket(key);
                pendingRequests.add(packet);
                try {
                    CommunicationPacket response = sendQueue.sendAndWait(packet, peer.getDestination(), REQUEST_TIMEOUT);
                    if (response == null)   // timeout occurred
                        peer.incrementStaleCounter();
                    else
                        peer.resetStaleCounter();
                }
                catch (InterruptedException e) {
                    log.warn("Interrupted while waiting on a lookup request.", e);
                }
            }
            
            if (responseReceived.size() >= KademliaConstants.S)
                break;
            if (hasTimedOut(startTime, CLOSEST_NODES_LOOKUP_TIMEOUT)) {
                log.error("Lookup for closest nodes timed out.");
                break;
            }
        }
        log.debug(responseReceived.size() + " nodes found.");
        
        i2pReceiver.removePacketListener(packetListener);
    }

    private KademliaPeer selectRandom(Collection<KademliaPeer> collection) {
        KademliaPeer[] array = new KademliaPeer[collection.size()];
        int index = randomNumberGenerator.nextInt(array.length);
        return collection.toArray(array)[index];
    }
    
    private long getTime() {
        return System.currentTimeMillis();
    }
    
    private boolean hasTimedOut(long startTime, long timeout) {
        return getTime() > startTime + timeout;
    }
    
    /**
     * Return up to <code>s</code> peers.
     * @return
     */
    public List<Destination> getResults() {
        List<Destination> resultsList = new ArrayList<Destination>();
        for (Destination destination: responseReceived)
            resultsList.add(destination);
        Collections.sort(resultsList, new HashDistanceComparator(key));
        
        // trim the list to the k closest nodes
        if (resultsList.size() > KademliaConstants.S)
            resultsList = resultsList.subList(0, KademliaConstants.S);
        return resultsList;
    }

    /**
     * Return <code>true</code> if a set of peers contains a given peer.
     * @param peerSet
     * @param peerToFind
     * @return
     */
    private boolean contains(Set<KademliaPeer> peerSet, KademliaPeer peerToFind) {
        Hash peerHash = peerToFind.getDestinationHash();
        for (KademliaPeer peer: peerSet)
            if (peer.getDestinationHash().equals(peerHash))
                return true;
        return false;
    }
    
    // compares two Destinations in terms of closeness to <code>reference</code>
    private class HashDistanceComparator implements Comparator<Destination> {
        private Hash reference;
        
        public HashDistanceComparator(Hash reference) {
            this.reference = reference;
        }
        
        public int compare(Destination dest1, Destination dest2) {
            BigInteger dest1Distance = KademliaUtil.getDistance(dest1.calculateHash(), reference);
            BigInteger dest2Distance = KademliaUtil.getDistance(dest2.calculateHash(), reference);
            return dest1Distance.compareTo(dest2Distance);
        }
    };
    
    private class IncomingPacketHandler implements PacketListener {
        @Override
        public void packetReceived(CommunicationPacket packet, Destination sender, long receiveTime) {
            if (packet instanceof ResponsePacket) {
                ResponsePacket responsePacket = (ResponsePacket)packet;
                DataPacket payload = responsePacket.getPayload();
                if (payload instanceof PeerList)
                    addPeers(responsePacket, (PeerList)payload, sender, receiveTime);
            }
        }
    
        private void addPeers(ResponsePacket responsePacket, PeerList peerListPacket, Destination sender, long receiveTime) {
            log.debug("Peer List Packet received: #peers=" + peerListPacket.getPeers().size() + ", sender="+ sender);
            
            // if the packet is in response to a pending request, update the three Sets
            FindClosePeersPacket request = getPacketById(pendingRequests, responsePacket.getPacketId());   // find the request the node list is in response to
            if (request != null) {
                // TODO make responseReceived and pendingRequests a parameter in the constructor?
                responseReceived.add(sender);
                Collection<KademliaPeer> peersReceived = peerListPacket.getPeers();
                for (KademliaPeer peer: peersReceived)
                    if (contains(notQueriedYet, peer))
                        notQueriedYet.add(peer);
                pendingRequests.remove(request);
                // TODO synchronize access to shortList and pendingRequests
            }
            else
                log.debug("No Find Close Nodes packet found for Peer List: " + peerListPacket);
        }

        /**
         * Returns a packet that matches a given {@link UniqueId} from a {@link Collection} of packets, or
         * <code>null</code> if no match.
         * @param packets
         * @param packetId
         * @return
         */
        private FindClosePeersPacket getPacketById(Collection<FindClosePeersPacket> packets, UniqueId packetId) {
            for (FindClosePeersPacket packet: packets)
                if (packetId.equals(packet.getPacketId()))
                    return packet;
            return null;
        }
    };
}