package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.DataFormatException;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
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
    private final Log _log;

    /**
     *  Don't store if somebody else stored it recently.
     *  Must be less than PUBLISH_DELAY * 3 / 16 (see getDelay())
     */
    private static final long MIN_PUBLISH_DELAY = 9*60*1000;

    /**
     *  Too short and the network puts a big connection load on the
     *  floodfills since we store directly.
     *  Too long and the floodfill will drop us - timeout is 60 minutes.
     */
    private static final long PUBLISH_DELAY = 52*60*1000;

    /** this needs to be long enough to give us time to start up,
        but less than 20m (when we start accepting tunnels and could be a IBGW)
        Actually no, we need this soon if we are a new router or
        other routers have forgotten about us, else
        we can't build IB exploratory tunnels.
     */
    private static final long FIRST_TIME_DELAY = 90*1000;
    private volatile boolean _notFirstTime;
    private final AtomicInteger _runCount = new AtomicInteger();
    
    public PublishLocalRouterInfoJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(PublishLocalRouterInfoJob.class);
    }
    
    public String getName() { return "Publish Local Router Info"; }

    public void runJob() {
        long last = getContext().netDb().getLastRouterInfoPublishTime();
        long now = getContext().clock().now();
        if (last + MIN_PUBLISH_DELAY > now) {
            long delay = getDelay();
            requeue(last + delay - now);
            return;
        }
        RouterInfo oldRI = getContext().router().getRouterInfo();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Old routerInfo contains " + oldRI.getAddresses().size() 
                       + " addresses and " + oldRI.getOptionsMap().size() + " options");
        try {
            List<RouterAddress> oldAddrs = new ArrayList<RouterAddress>(oldRI.getAddresses());
            List<RouterAddress> newAddrs = getContext().commSystem().createAddresses();
            int count = _runCount.incrementAndGet();
            RouterInfo ri = new RouterInfo(oldRI);
            if (_notFirstTime && (count % 4) != 0 && oldAddrs.size() == newAddrs.size()) {
                // 3 times out of 4, we don't republish if everything is the same...
                // If something changed, including the cost, then publish,
                // otherwise don't.
                String newcaps = getContext().router().getCapabilities();
                boolean different = !oldRI.getCapabilities().equals(newcaps);
                if (!different) {
                    Comparator<RouterAddress> comp = new AddrComparator();
                    Collections.sort(oldAddrs, comp);
                    Collections.sort(newAddrs, comp);
                    for (int i = 0; i < oldAddrs.size(); i++) {
                        // deepEquals() includes cost
                        if (!oldAddrs.get(i).deepEquals(newAddrs.get(i))) {
                            different = true;
                            break;
                        }
                    }
                    if (!different) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Not republishing early because costs and caps and addresses are the same");
                        requeue(getDelay());
                        return;
                    }
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Republishing early because addresses or costs or caps have changed -" +
                              " oldCaps: " + oldRI.getCapabilities() + " newCaps: " + newcaps +
                              " old:\n" +
                              oldAddrs + "\nnew:\n" + newAddrs);
            }
            ri.setPublished(getContext().clock().now());
            Properties stats = getContext().statPublisher().publishStatistics();
            ri.setOptions(stats);
            ri.setAddresses(newAddrs);

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
            requeue(getDelay());
        } else {
            requeue(FIRST_TIME_DELAY);
            _notFirstTime = true;
        }
    }

    private long getDelay() {
        long rv = (PUBLISH_DELAY * 3 / 4) + getContext().random().nextLong(PUBLISH_DELAY / 4);
        // run 4x as often as usual publish time (see above)
        rv /= 4;
        return rv;
    }

    /**
     *  Arbitrary sort so we can attempt to compare costs between two RIs to see if they have changed
     *
     *  @since 0.9.18
     */
    private static class AddrComparator implements Comparator<RouterAddress>, Serializable {
        public int compare(RouterAddress l, RouterAddress r) {
            int c = l.getTransportStyle().compareTo(r.getTransportStyle());
            if (c != 0)
                return c;
            String lh = l.getHost();
            String rh = r.getHost();
            if (lh == null)
                return rh == null ? 0 : -1;
            if (rh == null)
                return 1;
            return lh.compareTo(rh);
        }
    }
}
