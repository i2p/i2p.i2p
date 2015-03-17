package org.klomp.snark;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SHA1Hash;

/**
 * Hmm, any guesses as to what this is?  Used by the multitorrent functionality
 * in the PeerAcceptor to pick the right PeerCoordinator to accept the con for.
 * Each PeerCoordinator is added to the set from within the Snark (and removed
 * from it there too)
 */
public class PeerCoordinatorSet {
    private final Map<SHA1Hash, PeerCoordinator> _coordinators;
    
    public PeerCoordinatorSet() {
        _coordinators = new ConcurrentHashMap();
    }
     
    public Iterator<PeerCoordinator> iterator() {
        return _coordinators.values().iterator();
    }

    public void add(PeerCoordinator coordinator) {
        _coordinators.put(new SHA1Hash(coordinator.getInfoHash()), coordinator);
    }

    public void remove(PeerCoordinator coordinator) {
        _coordinators.remove(new SHA1Hash(coordinator.getInfoHash()));
    }

    /**
     *  @since 0.9.2
     */
    public PeerCoordinator get(byte[] infoHash) {
        return _coordinators.get(new SHA1Hash(infoHash));
    }
}
