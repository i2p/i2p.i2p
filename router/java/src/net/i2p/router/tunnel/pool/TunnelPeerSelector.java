package net.i2p.router.tunnel.pool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.HashDistance;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * Coordinate the selection of peers to go into a tunnel for one particular 
 * pool.
 *
 * Todo: there's nothing non-static in here
 */
public abstract class TunnelPeerSelector {
    /**
     * Which peers should go into the next tunnel for the given settings?  
     * 
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (ENDPOINT FIRST).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public abstract List<Hash> selectPeers(RouterContext ctx, TunnelPoolSettings settings);
    
    /**
     *  @return randomized number of hops 0-7, not including ourselves
     */
    protected int getLength(RouterContext ctx, TunnelPoolSettings settings) {
        int length = settings.getLength();
        int override = settings.getLengthOverride();
        if (override != 0)
            length = override;
        else if (settings.getLengthVariance() != 0) {
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
        }
        if (length < 0)
            length = 0;
        else if (length > 7) // as documented in tunnel.html
            length = 7;
        /*
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
        */
        return length;
    }
    
    /**
     *  For debugging, also possibly for restricted routes?
     *  Needs analysis and testing
     *  @return should always be false
     */
    protected boolean shouldSelectExplicit(TunnelPoolSettings settings) {
        if (settings.isExploratory()) return false;
        Properties opts = settings.getUnknownOptions();
        if (opts != null) {
            String peers = opts.getProperty("explicitPeers");
            if (peers == null)
                peers = I2PAppContext.getGlobalContext().getProperty("explicitPeers");
            if (peers != null)
                return true;
        }
        return false;
    }
    
    /**
     *  For debugging, also possibly for restricted routes?
     *  Needs analysis and testing
     *  @return should always be false
     */
    protected List<Hash> selectExplicit(RouterContext ctx, TunnelPoolSettings settings, int length) {
        String peers = null;
        Properties opts = settings.getUnknownOptions();
        if (opts != null)
            peers = opts.getProperty("explicitPeers");
        
        if (peers == null)
            peers = I2PAppContext.getGlobalContext().getProperty("explicitPeers");
        
        Log log = ctx.logManager().getLog(ClientPeerSelector.class);
        List<Hash> rv = new ArrayList();
        StringTokenizer tok = new StringTokenizer(peers, ",");
        while (tok.hasMoreTokens()) {
            String peerStr = tok.nextToken();
            Hash peer = new Hash();
            try {
                peer.fromBase64(peerStr);
                
                if (ctx.profileOrganizer().isSelectable(peer)) {
                    rv.add(peer);
                } else {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug("Explicit peer is not selectable: " + peerStr);
                }
            } catch (DataFormatException dfe) {
                if (log.shouldLog(Log.ERROR))
                    log.error("Explicit peer is improperly formatted (" + peerStr + ")", dfe);
            }
        }
        
        int sz = rv.size();
        Collections.shuffle(rv, ctx.random());
        
        while (rv.size() > length)
            rv.remove(0);
        
        if (log.shouldLog(Log.INFO)) {
            StringBuilder buf = new StringBuilder();
            if (settings.getDestinationNickname() != null)
                buf.append("peers for ").append(settings.getDestinationNickname());
            else if (settings.getDestination() != null)
                buf.append("peers for ").append(settings.getDestination().toBase64());
            else
                buf.append("peers for exploratory ");
            if (settings.isInbound())
                buf.append(" inbound");
            else
                buf.append(" outbound");
            buf.append(" peers: ").append(rv);
            buf.append(", out of ").append(sz).append(" (not including self)");
            log.info(buf.toString());
        }
        
        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        
        return rv;
    }
    
    /** 
     * Pick peers that we want to avoid
     */
    public Set<Hash> getExclude(RouterContext ctx, boolean isInbound, boolean isExploratory) {
        // we may want to update this to skip 'hidden' or 'unreachable' peers, but that
        // isn't safe, since they may publish one set of routerInfo to us and another to
        // other peers.  the defaults for filterUnreachable has always been to return false,
        // but might as well make it explicit with a "false &&"
        //
        // Unreachable peers at the inbound gateway is a major cause of problems.
        // Due to a bug in SSU peer testing in 0.6.1.32 and earlier, peers don't know
        // if they are unreachable, so the netdb indication won't help much.
        // As of 0.6.1.33 we should have lots of unreachables, so enable this for now.
        // Also (and more effectively) exclude peers we detect are unreachable,
        // this should be much more effective, especially on a router that has been
        // up a few hours.
        //
        // We could just try and exclude them as the inbound gateway but that's harder
        // (and even worse for anonymity?).
        //
        // Defaults changed to true for inbound only in filterUnreachable below.

        Set<Hash> peers = new HashSet(1);
        peers.addAll(ctx.profileOrganizer().selectPeersRecentlyRejecting());
        peers.addAll(ctx.tunnelManager().selectPeersInTooManyTunnels());
        // if (false && filterUnreachable(ctx, isInbound, isExploratory)) {
        if (filterUnreachable(ctx, isInbound, isExploratory)) {
            // NOTE: filterUnreachable returns true for inbound, false for outbound
            // This is the only use for getPeersByCapability? And the whole set of datastructures in PeerManager?
            List<Hash> caps = ctx.peerManager().getPeersByCapability(Router.CAPABILITY_UNREACHABLE);
            if (caps != null)
                peers.addAll(caps);
            caps = ctx.profileOrganizer().selectPeersLocallyUnreachable();
            if (caps != null)
                peers.addAll(caps);
        }
        if (filterSlow(ctx, isInbound, isExploratory)) {
            // NOTE: filterSlow always returns true
            Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
            char excl[] = getExcludeCaps(ctx);
            if (excl != null) {
                FloodfillNetworkDatabaseFacade fac = (FloodfillNetworkDatabaseFacade)ctx.netDb();
                List known = fac.getKnownRouterData();
                if (known != null) {
                    for (int i = 0; i < known.size(); i++) {
                        RouterInfo peer = (RouterInfo)known.get(i);
                        boolean shouldExclude = shouldExclude(ctx, log, peer, excl);
                        if (shouldExclude) {
                            peers.add(peer.getIdentity().calculateHash());
                            continue;
                        }
                        /*
                        String cap = peer.getCapabilities();
                        if (cap == null) {
                            peers.add(peer.getIdentity().calculateHash());
                            continue;
                        }
                        for (int j = 0; j < excl.length; j++) {
                            if (cap.indexOf(excl[j]) >= 0) {
                                peers.add(peer.getIdentity().calculateHash());
                                continue;
                            }
                        }
                        int maxLen = 0;
                        if (cap.indexOf(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL) >= 0)
                            maxLen++;
                        if (cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0)
                            maxLen++;
                        if (cap.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)
                            maxLen++;
                        if (cap.length() <= maxLen)
                            peers.add(peer.getIdentity().calculateHash());
                        // otherwise, it contains flags we aren't trying to focus on,
                        // so don't exclude it based on published capacity
                        
                        if (filterUptime(ctx, isInbound, isExploratory)) {
                            Properties opts = peer.getOptions();
                            if (opts != null) {
                                String val = opts.getProperty("stat_uptime");
                                long uptimeMs = 0;
                                if (val != null) {
                                    long factor = 1;
                                    if (val.endsWith("ms")) {
                                        factor = 1;
                                        val = val.substring(0, val.length()-2);
                                    } else if (val.endsWith("s")) {
                                        factor = 1000l;
                                        val = val.substring(0, val.length()-1);
                                    } else if (val.endsWith("m")) {
                                        factor = 60*1000l;
                                        val = val.substring(0, val.length()-1);
                                    } else if (val.endsWith("h")) {
                                        factor = 60*60*1000l;
                                        val = val.substring(0, val.length()-1);
                                    } else if (val.endsWith("d")) {
                                        factor = 24*60*60*1000l;
                                        val = val.substring(0, val.length()-1);
                                    }
                                    try { uptimeMs = Long.parseLong(val); } catch (NumberFormatException nfe) {}
                                    uptimeMs *= factor;
                                } else {
                                    // not publishing an uptime, so exclude it
                                    peers.add(peer.getIdentity().calculateHash());
                                    continue;
                                }
                                
                                long infoAge = ctx.clock().now() - peer.getPublished();
                                if (infoAge < 0) {
                                    infoAge = 0;
                                } else if (infoAge > 24*60*60*1000) {
				    // Only exclude long-unseen peers if we haven't just started up
				    long DONT_EXCLUDE_PERIOD = 15*60*1000;
				    if (ctx.router().getUptime() < DONT_EXCLUDE_PERIOD) {
				        if (log.shouldLog(Log.DEBUG))
				            log.debug("Not excluding a long-unseen peer, since we just started up.");
				    } else {
				        if (log.shouldLog(Log.DEBUG))
				            log.debug("Excluding a long-unseen peer.");
				        peers.add(peer.getIdentity().calculateHash());
				    }
                                    //peers.add(peer.getIdentity().calculateHash());
                                    continue;
                                } else {
                                    if (infoAge + uptimeMs < 2*60*60*1000) {
                                        // up for less than 2 hours, so exclude it
                                        peers.add(peer.getIdentity().calculateHash());
                                    }
                                }
                            } else {
                                // not publishing stats, so exclude it
                                peers.add(peer.getIdentity().calculateHash());
                                continue;
                            }
                        }
                         */
                    }
                }
                /*
                for (int i = 0; i < excludeCaps.length(); i++) {
                    List matches = ctx.peerManager().getPeersByCapability(excludeCaps.charAt(i));
                    if (log.shouldLog(Log.INFO))
                        log.info("Filtering out " + matches.size() + " peers with capability " + excludeCaps.charAt(i));
                    peers.addAll(matches);
                }
                 */
            }
        }
        return peers;
    }
    
    /** warning, this is also called by ProfileOrganizer.isSelectable() */
    public static boolean shouldExclude(RouterContext ctx, RouterInfo peer) {
        Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
        return shouldExclude(ctx, log, peer, getExcludeCaps(ctx));
    }
    
    private static char[] getExcludeCaps(RouterContext ctx) {
        String excludeCaps = ctx.getProperty("router.excludePeerCaps", 
                                             String.valueOf(Router.CAPABILITY_BW12));
        if (excludeCaps != null) {
            char excl[] = excludeCaps.toCharArray();
            return excl;
        } else {
            return null;
        }
    }
    
    private static final long DONT_EXCLUDE_PERIOD = 15*60*1000;
    /** 0.7.8 and earlier had major message corruption bugs */
    private static final String MIN_VERSION = "0.7.9";
    private static final VersionComparator _versionComparator = new VersionComparator();

    private static boolean shouldExclude(RouterContext ctx, Log log, RouterInfo peer, char excl[]) {
        String cap = peer.getCapabilities();
        if (cap == null) {
            return true;
        }
        for (int j = 0; j < excl.length; j++) {
            if (cap.indexOf(excl[j]) >= 0) {
                return true;
            }
        }
        int maxLen = 0;
        if (cap.indexOf(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL) >= 0)
            maxLen++;
        if (cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0)
            maxLen++;
        if (cap.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)
            maxLen++;
        if (cap.length() <= maxLen)
            return true;
        // otherwise, it contains flags we aren't trying to focus on,
        // so don't exclude it based on published capacity

        // minimum version check
        String v = peer.getOption("router.version");
        if (v == null || _versionComparator.compare(v, MIN_VERSION) < 0)
            return true;

        // uptime is always spoofed to 90m, so just remove all this
      /******
        String val = peer.getOption("stat_uptime");
        if (val != null) {
            long uptimeMs = 0;
                long factor = 1;
                if (val.endsWith("ms")) {
                    factor = 1;
                    val = val.substring(0, val.length()-2);
                } else if (val.endsWith("s")) {
                    factor = 1000l;
                    val = val.substring(0, val.length()-1);
                } else if (val.endsWith("m")) {
                    factor = 60*1000l;
                    val = val.substring(0, val.length()-1);
                } else if (val.endsWith("h")) {
                    factor = 60*60*1000l;
                    val = val.substring(0, val.length()-1);
                } else if (val.endsWith("d")) {
                    factor = 24*60*60*1000l;
                    val = val.substring(0, val.length()-1);
                }
                try { uptimeMs = Long.parseLong(val); } catch (NumberFormatException nfe) {}
                uptimeMs *= factor;

            long infoAge = ctx.clock().now() - peer.getPublished();
            if (infoAge < 0) {
                return false;
            } else if (infoAge > 5*24*60*60*1000) {
                // Only exclude long-unseen peers if we haven't just started up
                if (ctx.router().getUptime() < DONT_EXCLUDE_PERIOD) {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug("Not excluding a long-unseen peer, since we just started up.");
                    return false;
                } else {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug("Excluding a long-unseen peer.");
                    return true;
                }
            } else {
                if ( (infoAge + uptimeMs < 90*60*1000) && (ctx.router().getUptime() > DONT_EXCLUDE_PERIOD) ) {
                    // up for less than 90 min (which is really 1h since an uptime of 1h-2h is published as 90m),
                    // so exclude it
                    return true;
                } else {
                    return false;
                }
            }
        } else {
            // not publishing an uptime, so exclude it
            return true;
        }
      ******/
        return false;
    }
    
    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.outboundExploratoryExcludeUnreachable";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.outboundClientExcludeUnreachable";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.inboundExploratoryExcludeUnreachable";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.inboundClientExcludeUnreachable";
    
    private static final String DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "false";
    private static final String DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = "false";
    // see comments at getExclude() above
    private static final String DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "true";
    private static final String DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = "true";
    
    /**
     * do we want to skip peers who haven't been up for long?
     * @return true for inbound, false for outbound, unless configured otherwise
     */
    protected boolean filterUnreachable(RouterContext ctx, boolean isInbound, boolean isExploratory) {
        boolean def = false;
        String val = null;
        
        if (isExploratory)
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
            else
                val = ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
        else
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE);
            else 
                val = ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE);
        
        boolean rv = (val != null ? Boolean.valueOf(val).booleanValue() : def);
        //System.err.println("Filter unreachable? " + rv + " (inbound? " + isInbound + ", exploratory? " + isExploratory);
        return rv;
    }

    
    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.outboundExploratoryExcludeSlow";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW = "router.outboundClientExcludeSlow";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.inboundExploratoryExcludeSlow";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_SLOW = "router.inboundClientExcludeSlow";
    
    /**
     * do we want to skip peers that are slow?
     * @return true unless configured otherwise
     */
    protected boolean filterSlow(RouterContext ctx, boolean isInbound, boolean isExploratory) {
        boolean def = true;
        String val = null;
        
        if (isExploratory)
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW);
            else
                val = ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW);
        else
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_SLOW);
            else 
                val = ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW);
        
        boolean rv = (val != null ? Boolean.valueOf(val).booleanValue() : def);
        //System.err.println("Filter unreachable? " + rv + " (inbound? " + isInbound + ", exploratory? " + isExploratory);
        return rv;
    }
    
    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UPTIME = "router.outboundExploratoryExcludeUptime";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_UPTIME = "router.outboundClientExcludeUptime";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_UPTIME = "router.inboundExploratoryExcludeUptime";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_UPTIME = "router.inboundClientExcludeUptime";
    
    /**
     * do we want to skip peers who haven't been up for long?
     * @return true unless configured otherwise
     */
    protected boolean filterUptime(RouterContext ctx, boolean isInbound, boolean isExploratory) {
        boolean def = true;
        String val = null;
        
        if (isExploratory)
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UPTIME);
            else
                val = ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UPTIME);
        else
            if (isInbound)
                val = ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UPTIME);
            else 
                val = ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UPTIME);
        
        boolean rv = (val != null ? Boolean.valueOf(val).booleanValue() : def);
        //System.err.println("Filter unreachable? " + rv + " (inbound? " + isInbound + ", exploratory? " + isExploratory);
        return rv;
    }

    protected void orderPeers(List rv, Hash hash) {
            Collections.sort(rv, new HashComparator(hash));
    }

    /**
     *  Implement a deterministic comparison that cannot be predicted by
     *  others. A naive implementation (using the distance from a random key)
     *  allows an attacker who runs two routers with hashes far apart
     *  to maximize his chances of those two routers being at opposite
     *  ends of a tunnel.
     *
     *  Previous:
     *     d(l, h) - d(r, h)
     *
     *  Now:
     *     d((H(l+h), h) - d(H(r+h), h)
     */
    private static class HashComparator implements Comparator {
        private Hash _hash;

        private HashComparator(Hash h) {
            _hash = h;
        }
        public int compare(Object l, Object r) {
            byte[] data = new byte[2*Hash.HASH_LENGTH];
            System.arraycopy(_hash.getData(), 0, data, Hash.HASH_LENGTH, Hash.HASH_LENGTH);
            System.arraycopy(((Hash) l).getData(), 0, data, 0, Hash.HASH_LENGTH);
            Hash lh = SHA256Generator.getInstance().calculateHash(data);
            System.arraycopy(((Hash) r).getData(), 0, data, 0, Hash.HASH_LENGTH);
            Hash rh = SHA256Generator.getInstance().calculateHash(data);
            BigInteger ll = HashDistance.getDistance(_hash, lh);
            BigInteger rr = HashDistance.getDistance(_hash, rh);
            return ll.compareTo(rr);
        }
    }
}
