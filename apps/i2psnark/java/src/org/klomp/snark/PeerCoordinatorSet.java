package org.klomp.snark;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Hmm, any guesses as to what this is?  Used by the multitorrent functionality
 * in the PeerAcceptor to pick the right PeerCoordinator to accept the con for.
 * Each PeerCoordinator is added to the set from within the Snark (and removed
 * from it there too)
 */
public class PeerCoordinatorSet {
    private static final PeerCoordinatorSet _instance = new PeerCoordinatorSet();
    public static final PeerCoordinatorSet instance() { return _instance; }
    private Set _coordinators;
    
    private PeerCoordinatorSet() {
        _coordinators = new HashSet();
    }
    
    public Iterator iterator() {
        synchronized (_coordinators) {
            return new ArrayList(_coordinators).iterator();
        }
    }
    
    public void add(PeerCoordinator coordinator) {
        synchronized (_coordinators) {
            _coordinators.add(coordinator);
        }
    }
    public void remove(PeerCoordinator coordinator) {
        synchronized (_coordinators) {
            _coordinators.remove(coordinator);
        }
    }
}
