package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
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
    
    protected boolean shouldSelectExplicit(TunnelPoolSettings settings) {
        Properties opts = settings.getUnknownOptions();
        if (opts != null) {
            String peers = opts.getProperty("explicitPeers");
            if (peers != null)
                return true;
        }
        return false;
    }
    
    protected List selectExplicit(RouterContext ctx, TunnelPoolSettings settings, int length) {
        String peers = null;
        Properties opts = settings.getUnknownOptions();
        if (opts != null)
            peers = opts.getProperty("explicitPeers");
        
        Log log = ctx.logManager().getLog(ClientPeerSelector.class);
        List rv = new ArrayList();
        StringTokenizer tok = new StringTokenizer(peers, ",");
        Hash h = new Hash();
        while (tok.hasMoreTokens()) {
            String peerStr = tok.nextToken();
            Hash peer = new Hash();
            try {
                peer.fromBase64(peerStr);
                
                if (ctx.profileOrganizer().isSelectable(peer)) {
                    rv.add(peer);
                } else {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Explicit peer is not selectable: " + peerStr);
                }
            } catch (DataFormatException dfe) {
                if (log.shouldLog(Log.ERROR))
                    log.error("Explicit peer is improperly formatted (" + peerStr + ")", dfe);
            }
        }
        
        Collections.shuffle(rv, ctx.random());
        
        
        while (rv.size() > length)
            rv.remove(0);
        
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        
        if (log.shouldLog(Log.INFO))
            log.info(toString() + ": Selecting peers explicitly: " + rv);
        return rv;
    }
}
