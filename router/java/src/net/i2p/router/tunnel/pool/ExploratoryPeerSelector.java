package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

/**
 * Pick peers randomly out of the not-failing pool, and put them into randomly
 * ordered tunnels.
 *
 */
class ExploratoryPeerSelector extends TunnelPeerSelector {
    public List selectPeers(RouterContext ctx, TunnelPoolSettings settings) {
        int length = getLength(ctx, settings);
        if (length < 0)
            return null;
        HashSet matches = new HashSet(length);
        ctx.profileOrganizer().selectNotFailingPeers(length, null, matches);
        
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
