package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.KeyManager;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.StatisticsManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.router.RouterContext;

/**
 * Publish the local router's RouterInfo every 5 to 10 minutes
 *
 */
public class PublishLocalRouterInfoJob extends JobImpl {
    private Log _log;
    final static long PUBLISH_DELAY = 5*60*1000; // every 5 to 10 minutes (since we randomize)
    
    public PublishLocalRouterInfoJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(PublishLocalRouterInfoJob.class);
    }
    
    public String getName() { return "Publish Local Router Info"; }
    public void runJob() {
        RouterInfo ri = new RouterInfo(_context.router().getRouterInfo());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Old routerInfo contains " + ri.getAddresses().size() 
                       + " addresses and " + ri.getOptions().size() + " options");
        Properties stats = _context.statPublisher().publishStatistics();
        try {
            ri.setPublished(_context.clock().now());
            ri.setOptions(stats);
            ri.setAddresses(_context.commSystem().createAddresses());
            ri.sign(_context.keyManager().getSigningPrivateKey());
            _context.router().setRouterInfo(ri);
            if (_log.shouldLog(Log.INFO))
                _log.info("Newly updated routerInfo is published with " + stats.size() 
                          + "/" + ri.getOptions().size() + " options on " 
                          + new Date(ri.getPublished()));
            _context.netDb().publish(ri);
        } catch (DataFormatException dfe) {
            _log.error("Error signing the updated local router info!", dfe);
        }
        requeue(PUBLISH_DELAY + _context.random().nextInt((int)PUBLISH_DELAY));
    }
}
