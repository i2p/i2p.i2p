package org.klomp.snark;

import java.util.Set;
import java.util.HashSet;

public class Piece implements Comparable {

    private int id;
    private Set peers;
    private boolean requested;
    
    public Piece(int id) {
        this.id = id;
        this.peers = new HashSet();
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
    public boolean isRequested() { return this.requested; }
    public void setRequested(boolean requested) { this.requested = requested; } 
}
