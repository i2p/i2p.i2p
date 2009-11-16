package i2p.bote.network.kademlia;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

public class KademliaPeer {
    private static final int STALE_THRESHOLD = 5;
    
    private Log log = new Log(KademliaPeer.class);
    private Destination destination;
    private Hash destinationHash;
    private long lastPingSent;
    private long lastReception;
    private long activeSince;
    private AtomicInteger consecutiveTimeouts;
    
    public KademliaPeer(Destination destination, long lastReception) {
        this.destination = destination;
        destinationHash = destination.calculateHash();
        if (destinationHash == null)
            log.error("calculateHash() returned null!");
        
        this.lastReception = lastReception;
        lastPingSent = 0;
        activeSince = lastReception;
        consecutiveTimeouts = new AtomicInteger(0);
    }
    
    public Destination getDestination() {
    	return destination;
    }
    
    public Hash getDestinationHash() {
    	return destinationHash;
    }
    
    public boolean isStale() {
        return consecutiveTimeouts.get() >= STALE_THRESHOLD;
    }
    
    public void incrementStaleCounter() {
        consecutiveTimeouts.incrementAndGet();
    }
    
    public void resetStaleCounter() {
        consecutiveTimeouts.set(0);
    }
    
    public long getLastPingSent() {
    	return lastPingSent;
    }
    
    public void setLastReception(long time) {
    	lastReception = time;
    }
    
    public long getLastReception() {
    	return lastReception;
    }
    
    public long getActiveSince() {
    	return activeSince;
    }
    
/*    public BigInteger getDistance(KademliaPeer anotherPeer) {
        return KademliaUtil.getDistance(getDestinationHash(), anotherPeer.getDestinationHash());
    }*/
 
    @Override
    public String toString() {
        return destination.toString();
    }
}