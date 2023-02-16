package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.ArraySet;

/**
 * Pick peers randomly out of the fast pool, and put them into tunnels
 * ordered by XOR distance from a random key.
 *
 */
class ClientPeerSelector extends TunnelPeerSelector {

    public ClientPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Returns ENDPOINT FIRST, GATEWAY LAST!!!!
     * In: us .. closest .. middle .. IBGW
     * Out: OBGW .. middle .. closest .. us
     *
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (ENDPOINT FIRST).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0)
            return null;
        if ( (length == 0) && (settings.getLength()+settings.getLengthVariance() > 0) )
            return null;

        List<Hash> rv;
        boolean isInbound = settings.isInbound();

        if (length > 0) {
            // special cases
            boolean v6Only = isIPv6Only();
            boolean ntcpDisabled = isNTCPDisabled();
            boolean ssuDisabled = isSSUDisabled();
            // for these cases, check the closest hop up front,
            // otherwise, will be done in checkTunnel() at the end
            boolean checkClosestHop = v6Only || ntcpDisabled || ssuDisabled;
            boolean hidden = ctx.router().isHidden() ||
                             ctx.router().getRouterInfo().getAddressCount() <= 0 ||
                             !ctx.commSystem().haveInboundCapacity(95);
            boolean hiddenInbound = hidden && isInbound;
            boolean hiddenOutbound = hidden && !isInbound;
            int ipRestriction =  (ctx.getBooleanProperty("i2np.allowLocal") || length <= 1) ? 0 : settings.getIPRestriction();
            MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(16) : null;

            if (shouldSelectExplicit(settings))
                return selectExplicit(settings, length);

            Set<Hash> exclude = getExclude(isInbound, false);
            ArraySet<Hash> matches = new ArraySet<Hash>(length);
            if (length == 1) {
                // closest-hop restrictions
                if (checkClosestHop)
                    exclude = getClosestHopExclude(isInbound, exclude);
                if (isInbound)
                    exclude = new IBGWExcluder(exclude);
                else
                    exclude = new OBEPExcluder(exclude);
                // 1-hop, IP restrictions not required here
                if (hiddenInbound) {
                    // TODO this doesn't pick from fast
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches);
                }
                if (matches.isEmpty()) {
                    if (hiddenInbound) {
                        // No connected peers found, give up now
                        if (log.shouldWarn())
                            log.warn("CPS SANFP hidden closest IB no active peers found, returning null");
                        return null;
                    }
                    // ANFP does not fall back to non-connected
                    ctx.profileOrganizer().selectFastPeers(length, exclude, matches);
                }
                matches.remove(ctx.routerHash());
                rv = new ArrayList<Hash>(matches);
            } else {
                // build a tunnel using 4 subtiers.
                // For a 2-hop tunnel, the first hop comes from subtiers 0-1 and the last from subtiers 2-3.
                // For a longer tunnels, the first hop comes from subtier 0, the middle from subtiers 2-3, and the last from subtier 1.
                rv = new ArrayList<Hash>(length + 1);
                SessionKey randomKey = settings.getRandomKey();
                // OBEP or IB last hop
                // group 0 or 1 if two hops, otherwise group 0
                Set<Hash> lastHopExclude;
                if (isInbound) {
                    if (checkClosestHop && !hidden) {
                        // exclude existing OBEPs to get some diversity ?
                        // closest-hop restrictions
                        lastHopExclude = getClosestHopExclude(true, exclude);
                    } else {
                        lastHopExclude = exclude;
                    }
                    if (log.shouldInfo())
                        log.info("CPS SFP closest IB " + lastHopExclude);
                } else {
                    lastHopExclude = new OBEPExcluder(exclude);
                    if (log.shouldInfo())
                        log.info("CPS SFP OBEP " + lastHopExclude);
                }
                if (hiddenInbound) {
                    // IB closest hop
                    if (log.shouldInfo())
                        log.info("CPS SANFP hidden closest IB " + lastHopExclude);
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                    if (matches.isEmpty()) {
                        // No connected peers found, give up now
                        if (log.shouldWarn())
                            log.warn("CPS SANFP hidden closest IB no active peers found, returning null");
                        return null;
                    }
                } else if (hiddenOutbound) {
                    // OBEP
                    // check for hidden and outbound, and the paired (inbound) tunnel is zero-hop
                    // if so, we need the OBEP to be connected to us, so we get the build reply back
                    // This should be rare except at startup
                    TunnelManagerFacade tmf = ctx.tunnelManager();
                    TunnelPool tp = tmf.getInboundPool(settings.getDestination());
                    boolean pickFurthest;
                    if (tp != null) {
                        pickFurthest = true;
                        TunnelPoolSettings tps = tp.getSettings();
                        int len = tps.getLength();
                        if (len <= 0 ||
                            tps.getLengthOverride() == 0 ||
                            len + tps.getLengthVariance() <= 0) {
                            // leave it true
                        } else {
                            List<TunnelInfo> tunnels = tp.listTunnels();
                            if (!tunnels.isEmpty()) {
                                for (TunnelInfo ti : tp.listTunnels()) {
                                    if (ti.getLength() > 1) {
                                        pickFurthest = false;
                                        break;
                                    }
                                }
                            } else {
                                // no tunnels in the paired tunnel pool
                                // BuildRequester will be using exploratory
                                tp = tmf.getInboundExploratoryPool();
                                tps = tp.getSettings();
                                len = tps.getLength();
                                if (len <= 0 ||
                                    tps.getLengthOverride() == 0 ||
                                    len + tps.getLengthVariance() <= 0) {
                                    // leave it true
                                } else {
                                    tunnels = tp.listTunnels();
                                    if (!tunnels.isEmpty()) {
                                        for (TunnelInfo ti : tp.listTunnels()) {
                                            if (ti.getLength() > 1) {
                                                pickFurthest = false;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // shouldn't happen
                        pickFurthest = false;
                    }
                    if (pickFurthest) {
                        if (log.shouldInfo())
                            log.info("CPS SANFP OBEP " + lastHopExclude);
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        if (matches.isEmpty()) {
                            // No connected peers found, give up now
                            if (log.shouldWarn())
                                log.warn("CPS SANFP hidden OBEP no active peers found, returning null");
                            return null;
                        }
                        ctx.commSystem().exemptIncoming(matches.get(0));
                    } else {
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                    }
                } else {
                    // TODO exclude IPv6-only at OBEP? Caught in checkTunnel() below
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                }

                matches.remove(ctx.routerHash());
                exclude.addAll(matches);
                rv.addAll(matches);
                matches.clear();
                if (length > 2) {
                    // middle hop(s)
                    // group 2 or 3
                    if (log.shouldInfo())
                        log.info("CPS SFP middle " + exclude);
                    ctx.profileOrganizer().selectFastPeers(length - 2, exclude, matches, randomKey, SLICE_2_3, ipRestriction, ipSet);
                    matches.remove(ctx.routerHash());
                    if (matches.size() > 1) {
                        // order the middle peers for tunnels >= 4 hops
                        List<Hash> ordered = new ArrayList<Hash>(matches);
                        orderPeers(ordered, randomKey);
                        rv.addAll(ordered);
                    } else {
                        rv.addAll(matches);
                    }
                    exclude.addAll(matches);
                    matches.clear();
                }

                // IBGW or OB first hop
                // group 2 or 3 if two hops, otherwise group 1
                if (isInbound) {
                    exclude = new IBGWExcluder(exclude);
                    if (log.shouldInfo())
                        log.info("CPS SFP IBGW " + exclude);
                } else {
                    // exclude existing IBGWs to get some diversity ?
                    // OB closest-hop restrictions
                    if (checkClosestHop)
                        exclude = getClosestHopExclude(false, exclude);
                    if (log.shouldInfo())
                        log.info("CPS SFP closest OB " + exclude);
                }
                // TODO exclude IPv6-only at IBGW? Caught in checkTunnel() below
                ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1, ipRestriction, ipSet);
                matches.remove(ctx.routerHash());
                rv.addAll(matches);
            }
            if (log.shouldInfo())
                log.info("CPS " + length + (isInbound ? " IB " : " OB ") + "final: " + exclude);
            if (rv.size() < length) {
                // not enough peers to build the requested size
                // client tunnels do not use overrides
                if (log.shouldWarn())
                    log.warn("CPS requested " + length + " got " + rv.size());
                int min = settings.getLength();
                int skew = settings.getLengthVariance();
                if (skew < 0)
                    min += skew;
                // not enough peers to build the minimum size
                if (rv.size() < min)
                    return null;
            }
        } else {
            rv = new ArrayList<Hash>(1);
        }

        if (isInbound)
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, false, rv))
                rv = null;
        }
        if (isInbound && rv != null && rv.size() > 1)
            ctx.commSystem().exemptIncoming(rv.get(1));
        return rv;
    }

    /**
     *  A Set of Hashes that automatically adds to the
     *  Set in the contains() check.
     *
     *  So we don't need to generate the exclude set up front.
     *
     *  @since 0.9.58
     */
    private class IBGWExcluder extends ExcluderBase {

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param set not copied, contents will be modified by all methods
         */
        public IBGWExcluder(Set<Hash> set) {
            super(set);
        }

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param o a Hash
         *  @return true if peer should be excluded
         */
        public boolean contains(Object o) {
            if (s.contains(o))
                return true;
            Hash h = (Hash) o;
            boolean rv = !allowAsIBGW(h);
            if (rv) {
                s.add(h);
                if (log.shouldDebug())
                    log.debug("CPS IBGW exclude " + h.toBase64());
            }
            return rv;
        }
    }

    /**
     *  A Set of Hashes that automatically adds to the
     *  Set in the contains() check.
     *
     *  So we don't need to generate the exclude set up front.
     *
     *  @since 0.9.58
     */
    private class OBEPExcluder extends ExcluderBase {

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param set not copied, contents will be modified by all methods
         */
        public OBEPExcluder(Set<Hash> set) {
            super(set);
        }

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param o a Hash
         *  @return true if peer should be excluded
         */
        public boolean contains(Object o) {
            if (s.contains(o))
                return true;
            Hash h = (Hash) o;
            boolean rv = !allowAsOBEP(h);
            if (rv) {
                s.add(h);
                if (log.shouldDebug())
                    log.debug("CPS OBEP exclude " + h.toBase64());
            }
            return rv;
        }
    }
}
