package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Pick peers randomly out of the not-failing pool, and put them into a tunnel
 * ordered by XOR distance from a random key.
 *
 */
class ExploratoryPeerSelector extends TunnelPeerSelector {
    public List selectPeers(RouterContext ctx, TunnelPoolSettings settings) {
        Log l = ctx.logManager().getLog(getClass());
        int length = getLength(ctx, settings);
        if (length < 0) { 
            if (l.shouldLog(Log.DEBUG))
                l.debug("Length requested is zero: " + settings);
            return null;
        }
        
        if (false && shouldSelectExplicit(settings)) {
            List rv = selectExplicit(ctx, settings, length);
            if (l.shouldLog(Log.DEBUG))
                l.debug("Explicit peers selected: " + rv);
            return rv;
        }
        
        Set exclude = getExclude(ctx, settings.isInbound(), settings.isExploratory());
        exclude.add(ctx.routerHash());
        // Don't use ff peers for exploratory tunnels to lessen exposure to netDb searches and stores
        // Hmm if they don't get explored they don't get a speed/capacity rating
        // so they don't get used for client tunnels either.
        // FloodfillNetworkDatabaseFacade fac = (FloodfillNetworkDatabaseFacade)ctx.netDb();
        // exclude.addAll(fac.getFloodfillPeers());
        HashSet matches = new HashSet(length);
        boolean exploreHighCap = shouldPickHighCap(ctx);
        //
        // We don't honor IP Restriction here, to be fixed
        //
        if (exploreHighCap) 
            ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches);
        else if (ctx.commSystem().haveHighOutboundCapacity())
            ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false);
        else // use only connected peers so we don't make more connections
            ctx.profileOrganizer().selectActiveNotFailingPeers(length, exclude, matches);
        
        if (l.shouldLog(Log.DEBUG))
            l.debug("profileOrganizer.selectNotFailing(" + length + ") found " + matches);
        
        matches.remove(ctx.routerHash());
        ArrayList rv = new ArrayList(matches);
        if (rv.size() > 1)
            orderPeers(rv, settings.getRandomKey());
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        return rv;
    }
    
    private static final int MIN_NONFAILING_PCT = 25;
    private boolean shouldPickHighCap(RouterContext ctx) {
        if (Boolean.valueOf(ctx.getProperty("router.exploreHighCapacity", "false")).booleanValue())
            return true;
        // no need to explore too wildly at first
        if (ctx.router().getUptime() <= 5*60*1000)
            return true;
        // ok, if we aren't explicitly asking for it, we should try to pick peers
        // randomly from the 'not failing' pool.  However, if we are having a
        // hard time building exploratory tunnels, lets fall back again on the
        // high capacity peers, at least for a little bit.
        int failPct;
        // getEvents() will be 0 for first 10 minutes
        if (ctx.router().getUptime() <= 11*60*1000) {
            failPct = 100 - MIN_NONFAILING_PCT;
        } else {
            failPct = getExploratoryFailPercentage(ctx);
            Log l = ctx.logManager().getLog(getClass());
            if (l.shouldLog(Log.DEBUG))
                l.debug("Normalized Fail pct: " + failPct);
            // always try a little, this helps keep the failPct stat accurate too
            if (failPct > 100 - MIN_NONFAILING_PCT)
                failPct = 100 - MIN_NONFAILING_PCT;
        }
        return (failPct >= ctx.random().nextInt(100));
    }
    
    // We should really use the difference between the exploratory fail rate
    // and the high capacity fail rate - but we don't have a stat for high cap,
    // so use the fast (== client) fail rate, it should be close
    // if the expl. and client tunnel lengths aren't too different.
    // So calculate the difference between the exploratory fail rate
    // and the client fail rate, normalized to 100:
    //    100 * ((Efail - Cfail) / (100 - Cfail))
    // Even this isn't the "true" rate for the NonFailingPeers pool, since we
    // are often building exploratory tunnels using the HighCapacity pool.
    private int getExploratoryFailPercentage(RouterContext ctx) {
        int c = getFailPercentage(ctx, "Client");
        int e = getFailPercentage(ctx, "Exploratory");
        Log l = ctx.logManager().getLog(getClass());
        if (l.shouldLog(Log.DEBUG))
            l.debug("Client, Expl. Fail pct: " + c + ", " + e);
        if (e <= c || e <= 25) // doing very well (unlikely)
            return 0;
        if (c >= 90) // doing very badly
            return 100 - MIN_NONFAILING_PCT;
        return (100 * (e-c)) / (100-c);
    }

    private int getFailPercentage(RouterContext ctx, String t) {
        String pfx = "tunnel.build" + t;
        int timeout = getEvents(ctx, pfx + "Expire", 10*60*1000);
        int reject = getEvents(ctx, pfx + "Reject", 10*60*1000);
        int accept = getEvents(ctx, pfx + "Success", 10*60*1000);
        if (accept + reject + timeout <= 0)
            return 0;
        double pct = (double)(reject + timeout) / (accept + reject + timeout);
        return (int)(100 * pct);
    }
    
    // Use current + last to get more recent and smoother data
    private int getEvents(RouterContext ctx, String stat, long period) {
        RateStat rs = ctx.statManager().getRate(stat);
        if (rs == null) 
            return 0;
        Rate r = rs.getRate(period);
        if (r == null)
            return 0;
        return (int) (r.getLastEventCount() + r.getCurrentEventCount());
    }
}
