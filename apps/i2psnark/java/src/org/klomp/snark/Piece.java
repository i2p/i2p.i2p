package org.klomp.snark;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Piece implements Comparable {

    private int id;
    private Set peers;
    private boolean requested;
    
    public Piece(int id) {
        this.id = id;
        this.peers = Collections.synchronizedSet(new HashSet());
        this.requested = false;
    }
    
    public int compareTo(Object o) throws ClassCastException {
        return this.peers.size() - ((Piece)o).peers.size();
    }
    
    public boolean equals(Object o) {
        if (o == null) return false;
        try {
            return this.id == ((Piece)o).id;
        } catch (ClassCastException cce) {
            return false;
        }
    }
    
    public int getId() { return this.id; }
    public Set getPeers() { return this.peers; }
    public boolean addPeer(Peer peer) { return this.peers.add(peer.getPeerID()); }
    public boolean removePeer(Peer peer) { return this.peers.remove(peer.getPeerID()); }
    public boolean isRequested() { return this.requested; }
    public void setRequested(boolean requested) { this.requested = requested; } 
    
    public String toString() {
        return String.valueOf(id);
    }
}
