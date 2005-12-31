package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
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
}
