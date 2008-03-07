package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

/**
 * Pick peers randomly out of the fast pool, and put them into tunnels in a 
 * random order
 *
 */
class ClientPeerSelector extends TunnelPeerSelector {
    public List selectPeers(RouterContext ctx, TunnelPoolSettings settings) {
        int length = getLength(ctx, settings);
        if (length < 0)
            return null;
        if ( (length == 0) && (settings.getLength()+settings.getLengthVariance() > 0) )
            return null;
        HashSet matches = new HashSet(length);
    
        if (length > 0) {
            if (shouldSelectExplicit(settings))
                return selectExplicit(ctx, settings, length);
        }
        
        Set exclude = getExclude(ctx, settings.isInbound(), settings.isExploratory());
        ctx.profileOrganizer().selectFastPeers(length, exclude, matches, settings.getIPRestriction());
        
        matches.remove(ctx.routerHash());
        ArrayList rv = new ArrayList(matches);
        // Todo - Rather than shuffle, sort using xor distance from settings.getRandomKey()
        Collections.shuffle(rv, ctx.random());
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        return rv;
    }
}
