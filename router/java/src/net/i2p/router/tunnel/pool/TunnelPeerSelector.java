package net.i2p.router.tunnel.pool;

import java.util.List;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 * Coordinate the selection of peers to go into a tunnel for one particular 
 * pool.
 */
abstract class TunnelPeerSelector {
    /**
     * Which peers should go into the next tunnel for the given settings?  
     * 
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (endpoint first).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public abstract List selectPeers(RouterContext ctx, TunnelPoolSettings settings);
    
    protected int getLength(RouterContext ctx, TunnelPoolSettings settings) {
        int length = settings.getLength();
        if (settings.getLengthVariance() != 0) {
            int skew = settings.getLengthVariance();
            if (skew > 0)
                length += ctx.random().nextInt(skew+1);
            else {
                skew = 1 - skew;
                int off = ctx.random().nextInt(skew);
                if (ctx.random().nextBoolean())
                    length += off;
                else
                    length -= off;
            }
            if (length < 0)
                length = 0;
        }
        if ( (ctx.tunnelManager().getOutboundTunnelCount() <= 0) || 
             (ctx.tunnelManager().getFreeTunnelCount() <= 0) ) {
            Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
            // no tunnels to build tunnels with
            if (settings.getAllowZeroHop()) {
                if (log.shouldLog(Log.INFO))
                    log.info("no outbound tunnels or free inbound tunnels, but we do allow zeroHop: " + settings);
                return 0;
            } else {
                if (log.shouldLog(Log.WARN))
                    log.warn("no outbound tunnels or free inbound tunnels, and we dont allow zeroHop: " + settings);
                return -1;
            }
        }
        return length;
    }
}
