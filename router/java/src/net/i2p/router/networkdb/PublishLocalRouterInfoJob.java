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
import net.i2p.data.SigningPrivateKey;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.Router;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.Log;

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
        RouterInfo ri = new RouterInfo(getContext().router().getRouterInfo());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Old routerInfo contains " + ri.getAddresses().size() 
                       + " addresses and " + ri.getOptions().size() + " options");
        Properties stats = getContext().statPublisher().publishStatistics();
        stats.setProperty(RouterInfo.PROP_NETWORK_ID, ""+Router.NETWORK_ID);
        try {
            ri.setPublished(getContext().clock().now());
            ri.setOptions(stats);
            ri.setAddresses(getContext().commSystem().createAddresses());
            if (FloodfillNetworkDatabaseFacade.floodfillEnabled(getContext()))
                ri.addCapability(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL);

            if ("true".equalsIgnoreCase(getContext().getProperty(Router.PROP_HIDDEN, "false")))
                ri.addCapability(RouterInfo.CAPABILITY_HIDDEN);

            getContext().router().addReachabilityCapability(ri);
            SigningPrivateKey key = getContext().keyManager().getSigningPrivateKey();
            if (key == null) {
                _log.log(Log.CRIT, "Internal error - signing private key not known?  rescheduling publish for 30s");
                requeue(30*1000);
                return;
            }
            ri.sign(key);
            getContext().router().setRouterInfo(ri);
            if (_log.shouldLog(Log.INFO))
                _log.info("Newly updated routerInfo is published with " + stats.size() 
                          + "/" + ri.getOptions().size() + " options on " 
                          + new Date(ri.getPublished()));
            try {
                getContext().netDb().publish(ri);
            } catch (IllegalArgumentException iae) {
                _log.log(Log.CRIT, "Error publishing our identity - corrupt?", iae);
                getContext().router().rebuildNewIdentity();
            }
        } catch (DataFormatException dfe) {
            _log.error("Error signing the updated local router info!", dfe);
        }
        requeue(PUBLISH_DELAY + getContext().random().nextInt((int)PUBLISH_DELAY));
    }
}
