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
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.PeerManagerFacade;
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

    private static final boolean ENABLE_PEER_TEST = false;
    
    public PeerManagerFacadeImpl(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerManagerFacadeImpl.class);
        _persistenceHelper = new ProfilePersistenceHelper(ctx);
        _testJob = ENABLE_PEER_TEST ? new PeerTestJob(_context) : null;
    }
    
    public synchronized void startup() {
        _log.info("Starting up the peer manager");
        _manager = new PeerManager(_context);
        _persistenceHelper.setUs(_context.routerHash());
        if (_testJob != null)
            _testJob.startTesting(_manager);
    }
    
    public synchronized void shutdown() {
        _log.info("Shutting down the peer manager");
        if (_testJob != null)
            _testJob.stopTesting();
        if (_manager != null) {
            _manager.storeProfiles();
            _manager.clearProfiles();
        }
    }
    
    public synchronized void restart() {
        _manager.storeProfiles();
        _persistenceHelper.setUs(_context.routerHash());
        _manager.loadProfiles();
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
    @Deprecated
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
        if (_manager == null) return Collections.emptySet();
        return _manager.getPeersByCapability(capability); 
    }

    /**
     *  @param capability case-insensitive
     *  @return how many
     *  @since 0.9.45
     */
    public int countPeersByCapability(char capability) { 
        if (_manager == null) return 0;
        return _manager.countPeersByCapability(capability); 
    }

    /** @deprecated moved to routerconsole */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException { 
    }
    
}
