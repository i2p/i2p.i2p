package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;

/**
 * Manage peer references and keep them up to date so that when asked for peers,
 * it can provide appropriate peers according to the criteria provided.  This 
 * includes periodically queueing up outbound messages to the peers to test them.
 *
 */
public interface PeerManagerFacade extends Service {
    /**
     * Select peers from the manager's existing routing tables according to 
     * the specified criteria.  This call DOES block.
     *
     * @return List of Hash objects of the RouterIdentity for matching peers
     */
    public List selectPeers(PeerSelectionCriteria criteria);
}

class DummyPeerManagerFacade implements PeerManagerFacade {
    public void shutdown() {}    
    public void startup() {}
    public String renderStatusHTML() { return ""; }    
    public List selectPeers(PeerSelectionCriteria criteria) { return null; }
}
