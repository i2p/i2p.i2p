package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.PeerManagerFacade;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.Router;
import net.i2p.util.Log;

/**
 * Base implementation that has simple algorithms and periodically saves state
 *
 */
public class PeerManagerFacadeImpl extends PeerManagerFacade {
    private final static Log _log = new Log(PeerManagerFacadeImpl.class);
    private PeerManager _manager;
    
    public void startup() {
	_log.info("Starting up the peer manager");
	_manager = new PeerManager();
	ProfilePersistenceHelper.getInstance().setUs(Router.getInstance().getRouterInfo().getIdentity().getHash());
    }
    
    public void shutdown() { 
	_log.info("Shutting down the peer manager");
	_manager.storeProfiles();
    }
    
    public List selectPeers(PeerSelectionCriteria criteria) {
	return new ArrayList(_manager.selectPeers(criteria));
    }
    
    public String renderStatusHTML() { return _manager.renderStatusHTML(); }
}
