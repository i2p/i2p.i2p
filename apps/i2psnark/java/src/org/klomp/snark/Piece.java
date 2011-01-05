package org.klomp.snark;

import java.util.Collections;
import java.util.Set;

import net.i2p.util.ConcurrentHashSet;

/**
 * This class is used solely by PeerCoordinator.
 */
class Piece implements Comparable {

    private int id;
    private Set<PeerID> peers;
    /** @since 0.8.3 */
    private Set<PeerID> requests;
    /** @since 0.8.1 */
    private int priority;
    
    public Piece(int id) {
        this.id = id;
        this.peers = new ConcurrentHashSet(I2PSnarkUtil.MAX_CONNECTIONS);
        this.requests = new ConcurrentHashSet(2);
    }
    
    /**
     *  Highest priority first,
     *  then rarest first
     */
    public int compareTo(Object o) throws ClassCastException {
        int pdiff = ((Piece)o).priority - this.priority;   // reverse
        if (pdiff != 0)
            return pdiff;
        return this.peers.size() - ((Piece)o).peers.size();
    }
    
    @Override
    public boolean equals(Object o) {
        if (o instanceof Piece) {
            if (o == null) return false;
            return this.id == ((Piece)o).id;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 31 * hash + this.id;
        return hash;
    }
    
    public int getId() { return this.id; }
    public boolean addPeer(Peer peer) { return this.peers.add(peer.getPeerID()); }
    public boolean removePeer(Peer peer) { return this.peers.remove(peer.getPeerID()); }
    public boolean isRequested() { return !this.requests.isEmpty(); }

    /**
     * Since 0.8.3, keep track of who is requesting here,
     * to avoid deadlocks from querying each peer.
     */
    public void setRequested(Peer peer, boolean requested) {
        if (requested)
            this.requests.add(peer.getPeerID());
        else
            this.requests.remove(peer.getPeerID());
    } 
    
    /**
     * Is peer requesting this piece?
     * @since 0.8.3
     */
    public boolean isRequestedBy(Peer peer) {
        return this.requests.contains(peer.getPeerID());
    } 
    
    /**
     * How many peers are requesting this piece?
     * @since 0.8.3
     */
    public int getRequestCount() {
        return this.requests.size();
    } 
    
    /** @return default 0 @since 0.8.1 */
    public int getPriority() { return this.priority; }

    /** @since 0.8.1 */
    public void setPriority(int p) { this.priority = p; }

    /** @since 0.8.1 */
    public boolean isDisabled() { return this.priority < 0; }

    /** @since 0.8.1 */
    public void setDisabled() { this.priority = -1; }

    @Override
    public String toString() {
        return String.valueOf(id);
    }
}
