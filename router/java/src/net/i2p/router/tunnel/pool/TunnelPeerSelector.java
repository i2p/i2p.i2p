package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Coordinate the selection of peers to go into a tunnel for one particular
 * pool.
 *
 */
public abstract class TunnelPeerSelector extends ConnectChecker {

    private static final String DEFAULT_EXCLUDE_CAPS = String.valueOf(Router.CAPABILITY_BW12);

    protected TunnelPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Which peers should go into the next tunnel for the given settings?
     *
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (ENDPOINT FIRST).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public abstract List<Hash> selectPeers(TunnelPoolSettings settings);

    /**
     *  @return randomized number of hops 0-7, not including ourselves
     */
    protected int getLength(TunnelPoolSettings settings) {
        int length = settings.getLength();
        int override = settings.getLengthOverride();
        if (override >= 0) {
            length = override;
        } else if (settings.getLengthVariance() != 0) {
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
     *  @return usually false
     */
    protected boolean shouldSelectExplicit(TunnelPoolSettings settings) {
        if (settings.isExploratory()) return false;
        // To test IB or OB only
        //if (settings.isInbound()) return false;
        //if (!settings.isInbound()) return false;
        Properties opts = settings.getUnknownOptions();
        String peers = opts.getProperty("explicitPeers");
        if (peers == null)
            peers = ctx.getProperty("explicitPeers");
        // only one out of 4 times so we don't break completely if peer doesn't build one
        if (peers != null && ctx.random().nextInt(4) == 0)
            return true;
        return false;
    }

    /**
     *  For debugging, also possibly for restricted routes?
     *  Needs analysis and testing
     *  @return the peers
     */
    protected List<Hash> selectExplicit(TunnelPoolSettings settings, int length) {
        String peers = null;
        Properties opts = settings.getUnknownOptions();
        peers = opts.getProperty("explicitPeers");

        if (peers == null)
            peers = ctx.getProperty("explicitPeers");

        List<Hash> rv = new ArrayList<Hash>();
        StringTokenizer tok = new StringTokenizer(peers, ",");
        while (tok.hasMoreTokens()) {
            String peerStr = tok.nextToken();
            Hash peer = new Hash();
            try {
                peer.fromBase64(peerStr);

                if (ctx.profileOrganizer().isSelectable(peer)) {
                    rv.add(peer);
                } else {
                    log.logAlways(Log.WARN, "Explicit peer is not selectable: " + peerStr);
                }
            } catch (DataFormatException dfe) {
                if (log.shouldLog(Log.ERROR))
                    log.error("Explicit peer is improperly formatted (" + peerStr + ")", dfe);
            }
        }

        int sz = rv.size();
        if (sz == 0) {
            log.logAlways(Log.WARN, "No valid explicit peers found, building zero hop");
        } else if (sz > 1) {
            Collections.shuffle(rv, ctx.random());
        }

        while (rv.size() > length) {
            rv.remove(0);
        }
        if (rv.size() < length) {
            int more = length - rv.size();
            Set<Hash> exclude = getExclude(settings.isInbound(), settings.isExploratory());
            exclude.addAll(rv);
            Set<Hash> matches = new HashSet<Hash>(more);
            // don't bother with IP restrictions here
            ctx.profileOrganizer().selectFastPeers(more, exclude, matches);
            rv.addAll(matches);
            Collections.shuffle(rv, ctx.random());
        }

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
    public Set<Hash> getExclude(boolean isInbound, boolean isExploratory) {
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

        Set<Hash> peers = new HashSet<Hash>(8);
        peers.addAll(ctx.profileOrganizer().selectPeersRecentlyRejecting());
        if (!ctx.getBooleanProperty("i2np.allowLocal"))
            peers.addAll(ctx.tunnelManager().selectPeersInTooManyTunnels());
        if (filterUnreachable(isInbound, isExploratory)) {
            // This is the only use for getPeersByCapability? And the whole set of datastructures in PeerManager?
            Collection<Hash> caps = ctx.peerManager().getPeersByCapability(Router.CAPABILITY_UNREACHABLE);
            if (caps != null)
                peers.addAll(caps);
        }
        Collection<Hash> local = ctx.profileOrganizer().selectPeersLocallyUnreachable();
        if (local != null)
            peers.addAll(local);
        if (filterSlow(isInbound, isExploratory)) {
            // NOTE: filterSlow always returns true
            String excl = getExcludeCaps(ctx);

                FloodfillNetworkDatabaseFacade fac = (FloodfillNetworkDatabaseFacade)ctx.netDb();
                List<RouterInfo> known = fac.getKnownRouterData();
                if (known != null) {
                    for (int i = 0; i < known.size(); i++) {
                        RouterInfo peer = known.get(i);
                        boolean shouldExclude = shouldExclude(peer, excl);
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
        return peers;
    }

    /**
     *  Are we IPv6 only?
     *  @since 0.9.34
     */
    protected boolean isIPv6Only() {
        // The setting is the same for both SSU and NTCP, so just take the SSU one
        return TransportUtil.getIPv6Config(ctx, "SSU") == TransportUtil.IPv6Config.IPV6_ONLY;
    }

    /**
     *  Should we allow as OBEP?
     *  This just checks for IPv4 support.
     *  Will return false for IPv6-only.
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *  Default true.
     *
     *  @since 0.9.34
     */
    private boolean allowAsOBEP(Hash h) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(h);
        if (ri == null)
            return true;
        return canConnect(ri, ANY_V4);
    }

    /**
     *  Should we allow as IBGW?
     *  This just checks for the "R" capability and IPv4 support.
     *  Will return false for hidden or IPv6-only.
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *  Default true.
     *
     *  @since 0.9.34
     */
    private boolean allowAsIBGW(Hash h) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(h);
        if (ri == null)
            return true;
        if (ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0)
            return false;
        return canConnect(ANY_V4, ri);
    }

    /**
     *  Pick peers that we want to avoid for the first OB hop or last IB hop.
     *  There's several cases of importance:
     *  <ol><li>Inbound and we are hidden -
     *      Exclude all unless connected.
     *      This is taken care of in ClientPeerSelector and TunnelPeerSelector selectPeers(), not here.
     *
     *  <li>We are IPv6-only.
     *      Exclude all v4-only peers, unless connected
     *      This is taken care of here.
     *
     *  <li>We have NTCP or SSU disabled.
     *      Exclude all incompatible peers, unless connected
     *      This is taken care of here.
     *
     *  <li>Minimum version check, if we are some brand-new sig type,
     *      or are using some new tunnel build method.
     *      Not currently used, but this is where to implement the checks if needed.
     *      Make sure that ClientPeerSelector and TunnelPeerSelector selectPeers() call this when needed.
     *  </ol>
     *
     *  Don't call this unless you need to.
     *  See ClientPeerSelector and TunnelPeerSelector selectPeers().
     *
     *  @param isInbound
     *  @return null if none
     *  @since 0.9.17
     */
    protected Set<Hash> getClosestHopExclude(boolean isInbound) {
        RouterInfo ri = ctx.router().getRouterInfo();
        if (ri == null)
            return null;

        // we can skip this check now, uncomment if we have some new sigtype
        //SigType type = ri.getIdentity().getSigType();
        //if (type == SigType.DSA_SHA1)
        //    return null;

        int ourMask = isInbound ? getInboundMask(ri) : getOutboundMask(ri);
        Set<Hash> connected = ctx.commSystem().getEstablished();
        Set<Hash> rv = new HashSet<Hash>(256);
        FloodfillNetworkDatabaseFacade fac = (FloodfillNetworkDatabaseFacade)ctx.netDb();
        List<RouterInfo> known = fac.getKnownRouterData();
        if (known != null) {
            for (int i = 0; i < known.size(); i++) {
                RouterInfo peer = known.get(i);
                Hash h = peer.getIdentity().calculateHash();

                // Uncomment if stricter than in shouldExclude() below
                //String v = peer.getVersion();
                //if (VersionComparator.comp(v, "0.9.16") < 0) {
                //    rv.add(h);
                //    continue;
                //}

                if (connected.contains(h))
                    continue;
                boolean canConnect = isInbound ? canConnect(peer, ourMask) : canConnect(ourMask, peer);
                if (!canConnect)
                    rv.add(h);
            }
        }
        return rv;
    }

    /** warning, this is also called by ProfileOrganizer.isSelectable() */
    public static boolean shouldExclude(RouterContext ctx, RouterInfo peer) {
        return shouldExclude(peer, getExcludeCaps(ctx));
    }

    /**
     *  @return non-null, possibly empty
     */
    private static String getExcludeCaps(RouterContext ctx) {
        return ctx.getProperty("router.excludePeerCaps", DEFAULT_EXCLUDE_CAPS);
    }

    /** NTCP2 */
    private static final String MIN_VERSION = "0.9.36";

    /** warning, this is also called by ProfileOrganizer.isSelectable() */
    private static boolean shouldExclude(RouterInfo peer, String excl) {
        String cap = peer.getCapabilities();
        for (int j = 0; j < excl.length(); j++) {
            if (cap.indexOf(excl.charAt(j)) >= 0) {
                return true;
            }
        }
        int maxLen = 0;
        if (cap.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0)
            maxLen++;
        if (cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0)
            maxLen++;
        if (cap.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)
            maxLen++;
        if (cap.length() <= maxLen)
            return true;
        RouterIdentity ident = peer.getIdentity();
        if (ident.getSigningPublicKey().getType() == SigType.DSA_SHA1)
            return true;
        EncType type = ident.getPublicKey().getType();
        if (!LeaseSetKeys.SET_BOTH.contains(type))
            return true;

        // otherwise, it contains flags we aren't trying to focus on,
        // so don't exclude it based on published capacity

        // minimum version check
        String v = peer.getVersion();
        if (VersionComparator.comp(v, MIN_VERSION) < 0)
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

    private static final boolean DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = false;
    // see comments at getExclude() above
    private static final boolean DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = false;

    /**
     * do we want to skip unreachable peers?
     * @return true if yes
     */
    private boolean filterUnreachable(boolean isInbound, boolean isExploratory) {
        if (SystemVersion.isSlow() || ctx.router().getUptime() < 65*60*1000)
            return true;
        if (isExploratory) {
            if (isInbound) {
                if (ctx.router().isHidden())
                    return true;
                return ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
            } else {
                return ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
            }
        } else {
            if (isInbound) {
                if (ctx.router().isHidden())
                    return true;
                return ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE);
            } else {
                return ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE);
            }
        }
    }


    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.outboundExploratoryExcludeSlow";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW = "router.outboundClientExcludeSlow";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.inboundExploratoryExcludeSlow";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_SLOW = "router.inboundClientExcludeSlow";

    /**
     * do we want to skip peers that are slow?
     * @return true unless configured otherwise
     */
    protected boolean filterSlow(boolean isInbound, boolean isExploratory) {
        if (isExploratory) {
            if (isInbound)
                return ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW, true);
            else
                return ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW, true);
        } else {
            if (isInbound)
                return ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_SLOW, true);
            else
                return ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW, true);
        }
    }

/****
    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UPTIME = "router.outboundExploratoryExcludeUptime";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_UPTIME = "router.outboundClientExcludeUptime";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_UPTIME = "router.inboundExploratoryExcludeUptime";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_UPTIME = "router.inboundClientExcludeUptime";
****/

    /**
     * do we want to skip peers who haven't been up for long?
     * @return true unless configured otherwise
     */
/****
    protected boolean filterUptime(boolean isInbound, boolean isExploratory) {
        if (isExploratory) {
            if (isInbound)
                return ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UPTIME, true);
            else
                return ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UPTIME, true);
        } else {
            if (isInbound)
                return ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UPTIME, true);
            else
                return ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UPTIME, true);
        }
    }
****/

    /** see HashComparator */
    protected void orderPeers(List<Hash> rv, SessionKey key) {
        if (rv.size() > 1)
            Collections.sort(rv, new HashComparator(key));
    }

    /**
     *  Implement a deterministic comparison that cannot be predicted by
     *  others. A naive implementation (using the distance from a random key)
     *  allows an attacker who runs two routers with hashes far apart
     *  to maximize his chances of those two routers being at opposite
     *  ends of a tunnel.
     *
     *  Previous Previous:
     *     d(l, h) - d(r, h)
     *
     *  Previous:
     *     d((H(l+h), h) - d(H(r+h), h)
     *
     *  Now:
     *     SipHash using h to generate the SipHash keys
     *     then siphash(l) - siphash(r)
     */
    private static class HashComparator implements Comparator<Hash>, Serializable {
        private final long k0, k1;

        /**
         * not thread safe
         *
         * @param k container for sort keys, not used as a Hash
         */
        private HashComparator(SessionKey k) {
            byte[] b = k.getData();
            // we use the first half of the random key in ProfileOrganizer.getSubTier(),
            // so use the last half here
            k0 = DataHelper.fromLong8(b, 16);
            k1 = DataHelper.fromLong8(b, 24);
        }

        public int compare(Hash l, Hash r) {
            long lh = SipHashInline.hash24(k0, k1, l.getData());
            long rh = SipHashInline.hash24(k0, k1, r.getData());
            if (lh > rh)
                return 1;
            if (lh < rh)
                return -1;
            return 0;
        }
    }

    /**
     *  Connectivity check.
     *  Check that each hop can connect to the next, including us.
     *  Check that the OBEP is not IPv6-only, and the IBGW is
     *  reachable and not hidden or IPv6-only.
     *  Tells the profile manager to blame the hop, and returns false on failure.
     *
     *  @param tunnel ENDPOINT FIRST, GATEWAY LAST!!!!, length 2 or greater
     *  @return ok
     *  @since 0.9.34
     */
    protected boolean checkTunnel(boolean isInbound, List<Hash> tunnel) {
        if (!checkTunnel(tunnel))
            return false;
        if (isInbound) {
            Hash h = tunnel.get(tunnel.size() - 1);
            if (!allowAsIBGW(h)) {
                if (log.shouldWarn())
                    log.warn("Picked IPv6-only or unreachable peer for IBGW: " + h);
                // treat as a timeout in the profile
                // tunnelRejected() would set the last heard from time
                ctx.profileManager().tunnelTimedOut(h);
                return false;
            }
        } else {
            Hash h = tunnel.get(0);
            if (!allowAsOBEP(h)) {
                if (log.shouldWarn())
                    log.warn("Picked IPv6-only peer for OBEP: " + h);
                // treat as a timeout in the profile
                // tunnelRejected() would set the last heard from time
                ctx.profileManager().tunnelTimedOut(h);
                return false;
            }
        }
        return true;
    }

    /**
     *  Connectivity check.
     *  Check that each hop can connect to the next, including us.
     *
     *  @param tunnel ENDPOINT FIRST, GATEWAY LAST!!!!
     *  @return ok
     *  @since 0.9.34
     */
    private boolean checkTunnel(List<Hash> tunnel) {
        boolean rv = true;
        for (int i = 0; i < tunnel.size() - 1; i++) {
            // order is backwards!
            Hash hf = tunnel.get(i+1);
            Hash ht = tunnel.get(i);
            if (!canConnect(hf, ht)) {
                if (log.shouldWarn())
                    log.warn("Connect check fail hop " + (i+1) + " to " + i +
                             " in tunnel (EP<-GW): " + DataHelper.toString(tunnel));
                // Blame them both
                // treat as a timeout in the profile
                // tunnelRejected() would set the last heard from time
                Hash us = ctx.routerHash();
                if (!hf.equals(us))
                    ctx.profileManager().tunnelTimedOut(hf);
                if (!ht.equals(us))
                    ctx.profileManager().tunnelTimedOut(ht);
                rv = false;
                break;
            }
        }
        return rv;
    }
}
