package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.PeerManagerFacade;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Base implementation that has simple algorithms and periodically saves state
 *
 */
public class PeerManagerFacadeImpl implements PeerManagerFacade {
    private final Log _log;
    private PeerManager _manager;
    private final RouterContext _context;
    private final ProfilePersistenceHelper _persistenceHelper;
    private final PeerTestJob _testJob;
    
    public PeerManagerFacadeImpl(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerManagerFacadeImpl.class);
        _persistenceHelper = new ProfilePersistenceHelper(ctx);
        _testJob = new PeerTestJob(_context);
    }
    
    public void startup() {
        _log.info("Starting up the peer manager");
        _manager = new PeerManager(_context);
        _persistenceHelper.setUs(_context.routerHash());
        _testJob.startTesting(_manager);
    }
    
    public void shutdown() {
        _log.info("Shutting down the peer manager");
        _testJob.stopTesting();
        if (_manager != null) {
            _manager.storeProfiles();
            _manager.clearProfiles();
        }
    }
    
    public void restart() {
        _manager.storeProfiles();
        _persistenceHelper.setUs(_context.routerHash());
        _manager.loadProfiles();
    }
    
    public List<Hash> selectPeers(PeerSelectionCriteria criteria) {
        return _manager.selectPeers(criteria);
    }

    /**
     *  @param caps non-null
     */
    public void setCapabilities(Hash peer, String caps) { 
        if (_manager == null) return;
        _manager.setCapabilities(peer, caps); 
    }

    public void removeCapabilities(Hash peer) { 
        if (_manager == null) return;
        _manager.removeCapabilities(peer); 
    }

    /** @deprecated unused */
    public Hash selectRandomByCapability(char capability) { 
        //if (_manager == null) return null;
        //return _manager.selectRandomByCapability(capability); 
        return null;
    }

    /**
     *  @param capability case-insensitive
     *  @return non-null unmodifiable set
     */
    public Set<Hash> getPeersByCapability(char capability) { 
        if (_manager == null) return Collections.EMPTY_SET;
        return _manager.getPeersByCapability(capability); 
    }

    /** @deprecated moved to routerconsole */
    public void renderStatusHTML(Writer out) throws IOException { 
    }
    
}
