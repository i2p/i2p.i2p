package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Pick peers randomly out of the not-failing pool, and put them into randomly
 * ordered tunnels.
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
        HashSet matches = new HashSet(length);
        boolean exploreHighCap = shouldPickHighCap(ctx);
        if (exploreHighCap) 
            ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches);
        else
            ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false);
        
        if (l.shouldLog(Log.DEBUG))
            l.debug("profileOrganizer.selectNotFailing(" + length + ") found " + matches);
        
        matches.remove(ctx.routerHash());
        ArrayList rv = new ArrayList(matches);
        Collections.shuffle(rv, ctx.random());
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        return rv;
    }
    
    private boolean shouldPickHighCap(RouterContext ctx) {
        if (Boolean.valueOf(ctx.getProperty("router.exploreHighCapacity", "false")).booleanValue())
            return true;
        // no need to explore too wildly at first
        if (ctx.router().getUptime() <= 10*1000)
            return true;
        // ok, if we aren't explicitly asking for it, we should try to pick peers
        // randomly from the 'not failing' pool.  However, if we are having a
        // hard time building exploratory tunnels, lets fall back again on the
        // high capacity peers, at least for a little bit.
        int failPct = getExploratoryFailPercentage(ctx);
        return (failPct >= ctx.random().nextInt(100));
    }
    
    private int getExploratoryFailPercentage(RouterContext ctx) {
        int timeout = getEvents(ctx, "tunnel.buildExploratoryExpire", 10*60*1000);
        int reject = getEvents(ctx, "tunnel.buildExploratoryReject", 10*60*1000);
        int accept = getEvents(ctx, "tunnel.buildExploratorySuccess", 10*60*1000);
        if (accept + reject + timeout <= 0)
            return 0;
        double pct = (double)(reject + timeout) / (accept + reject + timeout);
        return (int)(100 * pct);
    }
    
    private int getEvents(RouterContext ctx, String stat, long period) {
        RateStat rs = ctx.statManager().getRate(stat);
        if (rs == null) 
            return 0;
        Rate r = rs.getRate(period);
        if (r == null)
            return 0;
        return (int)r.getLastEventCount();
    }
}
