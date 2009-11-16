package i2p.bote.network;

import net.i2p.data.Destination;

public class PeerManager {

    public PeerManager() {
    }
    
    /**
     * Return a random active peer.
     * 
     * The peers are managed independently of the Kademlia peers because they need to
     * be uniformly distributed across the key space to prevent leaking information about
     * the local destination key to nodes that could link it to the local email address.
     * @return
     */
    public Destination getRandomPeer() {
        return null;
    }

    public int getNumPeers() {
        // TODO
        return 0;
    }
}