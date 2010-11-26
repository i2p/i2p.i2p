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
    private boolean requested;
    /** @since 0.8.1 */
    private int priority;
    
    public Piece(int id) {
        this.id = id;
        this.peers = new ConcurrentHashSet();
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
    /** @deprecated unused */
    public Set<PeerID> getPeers() { return this.peers; }
    public boolean addPeer(Peer peer) { return this.peers.add(peer.getPeerID()); }
    public boolean removePeer(Peer peer) { return this.peers.remove(peer.getPeerID()); }
    public boolean isRequested() { return this.requested; }
    public void setRequested(boolean requested) { this.requested = requested; } 
    
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
