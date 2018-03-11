package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;

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
            boolean checkClosestHop = v6Only || ntcpDisabled || ssuDisabled;
            boolean hidden = ctx.router().isHidden() ||
                             ctx.router().getRouterInfo().getAddressCount() <= 0;
            boolean hiddenInbound = hidden && isInbound;
            boolean hiddenOutbound = hidden && !isInbound;

            if (shouldSelectExplicit(settings))
                return selectExplicit(settings, length);
        
            Set<Hash> exclude = getExclude(isInbound, false);
            Set<Hash> matches = new HashSet<Hash>(length);
            if (length == 1) {
                // closest-hop restrictions
                if (checkClosestHop) {
                    Set<Hash> moreExclude = getClosestHopExclude(isInbound);
                    if (moreExclude != null)
                        exclude.addAll(moreExclude);
                }
                if (hiddenInbound) {
                    // SANFP adds all not-connected to exclude, so make a copy
                    Set<Hash> SANFPExclude = new HashSet<Hash>(exclude);
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, SANFPExclude, matches);
                }
                if (matches.isEmpty()) {
                    // ANFP does not fall back to non-connected
                    ctx.profileOrganizer().selectFastPeers(length, exclude, matches, 0);
                }
                matches.remove(ctx.routerHash());
                rv = new ArrayList<Hash>(matches);
            } else {
                // build a tunnel using 4 subtiers.
                // For a 2-hop tunnel, the first hop comes from subtiers 0-1 and the last from subtiers 2-3.
                // For a longer tunnels, the first hop comes from subtier 0, the middle from subtiers 2-3, and the last from subtier 1.
                rv = new ArrayList<Hash>(length + 1);
                Hash randomKey = settings.getRandomKey();
                // OBEP or IB last hop
                // group 0 or 1 if two hops, otherwise group 0
                Set<Hash> lastHopExclude;
                if (isInbound) {
                    // exclude existing OBEPs to get some diversity ?
                    // closest-hop restrictions
                    if (checkClosestHop) {
                        Set<Hash> moreExclude = getClosestHopExclude(false);
                        if (moreExclude != null) {
                            moreExclude.addAll(exclude);
                            lastHopExclude = moreExclude;
                        } else {
                            lastHopExclude = exclude;
                        }
                    } else {
                         lastHopExclude = exclude;
                    }
                } else {
                    lastHopExclude = exclude;
                }
                if (hiddenInbound) {
                    // IB closest hop 
                    if (log.shouldInfo())
                        log.info("CPS SANFP closest IB exclude " + lastHopExclude.size());
                    // SANFP adds all not-connected to exclude, so make a copy
                    Set<Hash> SANFPExclude = new HashSet<Hash>(lastHopExclude);
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, SANFPExclude, matches);
                    if (matches.isEmpty()) {
                        if (log.shouldInfo())
                            log.info("CPS SFP closest IB exclude " + lastHopExclude.size());
                        // ANFP does not fall back to non-connected
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0);
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
                            log.info("CPS SANFP OBEP exclude " + lastHopExclude.size());
                        // SANFP adds all not-connected to exclude, so make a copy
                        Set<Hash> SANFPExclude = new HashSet<Hash>(lastHopExclude);
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, SANFPExclude, matches);
                        if (matches.isEmpty()) {
                            // ANFP does not fall back to non-connected
                            if (log.shouldInfo())
                                log.info("CPS SFP OBEP exclude " + lastHopExclude.size());
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0);
                        }
                    } else {
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0);
                    }
                } else {
                    // TODO exclude IPv6-only at OBEP? Caught in checkTunnel() below
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0);
                }

                matches.remove(ctx.routerHash());
                exclude.addAll(matches);
                rv.addAll(matches);
                matches.clear();
                if (length > 2) {
                    // middle hop(s)
                    // group 2 or 3
                    ctx.profileOrganizer().selectFastPeers(length - 2, exclude, matches, randomKey, SLICE_2_3);
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
                if (!isInbound) {
                    // exclude existing IBGWs to get some diversity ?
                    // closest-hop restrictions
                    if (checkClosestHop) {
                        Set<Hash> moreExclude = getClosestHopExclude(true);
                        if (moreExclude != null)
                            exclude.addAll(moreExclude);
                    }
                }
                // TODO exclude IPv6-only at IBGW? Caught in checkTunnel() below
                ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1);
                matches.remove(ctx.routerHash());
                rv.addAll(matches);
            }
        } else {
            rv = new ArrayList<Hash>(1);
        }
        
        //if (length != rv.size() && log.shouldWarn())
        //    log.warn("CPS requested " + length + " got " + rv.size() + ": " + DataHelper.toString(rv));
        //else if (log.shouldDebug())
        //    log.debug("EPS result: " + DataHelper.toString(rv));
        if (isInbound)
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());
        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, rv))
                rv = null;
        }
        return rv;
    }
}
