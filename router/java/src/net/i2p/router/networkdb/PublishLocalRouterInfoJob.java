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
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Publish the local router's RouterInfo periodically.
 * NOTE - this also creates and signs the RI.
 * This is run immediately at startup... but doesn't really
 * send to the floodfills until the second time it runs.
 */
public class PublishLocalRouterInfoJob extends JobImpl {
    private Log _log;
    final static long PUBLISH_DELAY = 20*60*1000;
    /** this needs to be long enough to give us time to start up,
        but less than 20m (when we start accepting tunnels and could be a IBGW)
        Actually no, we need this soon if we are a new router or
        other routers have forgotten about us, else
        we can't build IB exploratory tunnels.
     */
    final static long FIRST_TIME_DELAY = 90*1000;
    boolean _notFirstTime;
    
    public PublishLocalRouterInfoJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(PublishLocalRouterInfoJob.class);
    }
    
    public String getName() { return "Publish Local Router Info"; }
    public void runJob() {
        RouterInfo ri = new RouterInfo(getContext().router().getRouterInfo());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Old routerInfo contains " + ri.getAddresses().size() 
                       + " addresses and " + ri.getOptionsMap().size() + " options");
        Properties stats = getContext().statPublisher().publishStatistics();
        stats.setProperty(RouterInfo.PROP_NETWORK_ID, ""+Router.NETWORK_ID);
        try {
            ri.setPublished(getContext().clock().now());
            ri.setOptions(stats);
            ri.setAddresses(getContext().commSystem().createAddresses());

            getContext().router().addCapabilities(ri);
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
                          + "/" + ri.getOptionsMap().size() + " options on " 
                          + new Date(ri.getPublished()));
            try {
                // This won't really publish until the netdb is initialized.
                getContext().netDb().publish(ri);
            } catch (IllegalArgumentException iae) {
                _log.log(Log.CRIT, "Error publishing our identity - corrupt? Restart required", iae);
                getContext().router().rebuildNewIdentity();
            }
        } catch (DataFormatException dfe) {
            _log.error("Error signing the updated local router info!", dfe);
        }
        if (_notFirstTime) {
            requeue((PUBLISH_DELAY * 3 / 4) + getContext().random().nextInt((int)PUBLISH_DELAY / 2));
        } else {
            requeue(FIRST_TIME_DELAY);
            _notFirstTime = true;
        }
    }
}
