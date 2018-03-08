package net.i2p.router.tunnel.pool;

import java.util.Collection;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportManager;
import net.i2p.util.Log;

/**
 * Tools to check transport compatibility.
 *
 * @since 0.9.34
 */
public class ConnectChecker {
    protected final RouterContext ctx;
    protected final Log log;

    private static final int NTCP_V4 = 0x01;
    private static final int SSU_V4 = 0x02;
    public static final int ANY_V4 = NTCP_V4 | SSU_V4;
    private static final int NTCP_V6 = 0x04;
    private static final int SSU_V6 = 0x08;
    private static final int ANY_V6 = NTCP_V6 | SSU_V6;


    public ConnectChecker(RouterContext context) {
        ctx = context;
        log = ctx.logManager().getLog(getClass());
    }

    /**
     *  Is NTCP disabled?
     *  @since 0.9.34
     */
    protected boolean isNTCPDisabled() {
        return !TransportManager.isNTCPEnabled(ctx);
    }

    /**
     *  Is SSU disabled?
     *  @since 0.9.34
     */
    protected boolean isSSUDisabled() {
        return !ctx.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);
    }

    /**
     *  Can "from" connect to "to" based on published addresses?
     *
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *  Either from or to may be us.
     *
     *  This is best effort, as we can't know for sure.
     *  Published addresses or introducers may have changed.
     *  Even if a can't connect to b, they may already be connected
     *  as b connected to a.
     *
     *  @return true if we don't have either RI
     *  @since 0.9.34
     */
    public boolean canConnect(Hash from, Hash to) {
        Hash us = ctx.routerHash();
        if (us == null)
            return true;
        boolean usf = from.equals(us);
        if (usf && ctx.commSystem().isEstablished(to))
            return true;
        boolean ust = to.equals(us);
        if (ust && ctx.commSystem().isEstablished(from))
            return true;
        RouterInfo rt = ctx.netDb().lookupRouterInfoLocally(to);
        if (rt == null)
            return true;
        RouterInfo rf = ctx.netDb().lookupRouterInfoLocally(from);
        if (rf == null)
            return true;
        int ct;
        if (ust) {
            // to us
            ct = getInboundMask(rt);
        } else {
            Collection<RouterAddress> at = rt.getAddresses();
            // assume nothing if hidden
            if (at.isEmpty())
                return false;
            ct = getConnectMask(at);
        }

        int cf;
        if (usf) {
            // from us
            cf = getOutboundMask(rf);
        } else {
            Collection<RouterAddress> a = rf.getAddresses();
            if (a.isEmpty()) {
                // assume IPv4 if hidden
                cf = NTCP_V4 | SSU_V4;
            } else {
                cf = getConnectMask(a);
            }
        }

        boolean rv = (ct & cf) != 0;
        if (!rv && log.shouldWarn()) {
            log.warn("Cannot connect: " +
                     (usf ? "us" : from.toString()) + " with mask " + cf + "\nto " +
                     (ust ? "us" : to.toString()) + " with mask " + ct);
        }
        return rv;
    }

    /**
     *  Can we connect to "to" based on published addresses?
     *
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *
     *  This is best effort, as we can't know for sure.
     *  Does not check isEstablished(); do that first.
     *
     *  @since 0.9.34
     */
    public boolean canConnect(int ourMask, RouterInfo to) {
        Collection<RouterAddress> ra = to.getAddresses();
        // assume nothing if hidden
        if (ra.isEmpty())
            return false;
        int ct = getConnectMask(ra);
        boolean rv = (ourMask & ct) != 0;
        //if (!rv && log.shouldWarn())
        //    log.warn("Cannot connect: us with mask " + ourMask + " to " + to + " with mask " + ct);
        return rv;
    }

    /**
     *  Can "from" connect to us based on published addresses?
     *
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *
     *  This is best effort, as we can't know for sure.
     *  Does not check isEstablished(); do that first.
     *
     *  @since 0.9.34
     */
    public boolean canConnect(RouterInfo from, int ourMask) {
        if (ourMask == 0)
            return false;
        Collection<RouterAddress> ra = from.getAddresses();
        int cf;
        // assume v4 if hidden
        if (ra.isEmpty())
            cf = NTCP_V4 | SSU_V4;
        else
            cf = getConnectMask(ra);
        boolean rv = (cf & ourMask) != 0;
        //if (!rv && log.shouldWarn())
        //    log.warn("Cannot connect: " + from + " with mask " + cf + " to us with mask " + ourMask);
        return rv;
    }

    /**
     *  Our inbound mask.
     *  For most cases, we use what we published, i.e. getConnectMask()
     *
     *  @return bitmask for accepting connections
     *  @since 0.9.34
     */
    public int getInboundMask(RouterInfo us) {
        // to us
        int ct = 0;
        Status status = ctx.commSystem().getStatus();
        switch (status) {
            case OK:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
            case IPV4_SNAT_IPV6_UNKNOWN:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
            case IPV4_OK_IPV6_FIREWALLED:
            case DIFFERENT:
            case REJECT_UNSOLICITED:
                // use what we published
                Collection<RouterAddress> at = us.getAddresses();
                if (at.isEmpty())
                    return 0;
                ct = getConnectMask(at);
                break;

            case IPV4_DISABLED_IPV6_OK:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            // maybe should return zero for this one?
            case IPV4_DISABLED_IPV6_FIREWALLED:
                // TODO look at force-firewalled settings per-transport
                if (!isNTCPDisabled())
                    ct |= NTCP_V6;
                if (!isSSUDisabled())
                    ct |= SSU_V6;
                break;

            case IPV4_OK_IPV6_UNKNOWN:
            case DISCONNECTED:
            case HOSED:
            case UNKNOWN:
            default:
                if (!isNTCPDisabled())
                    ct |= NTCP_V4;
                if (!isSSUDisabled())
                    ct |= SSU_V4;
                break;
        }
        return ct;
    }

    /**
     *  Our outbound mask.
     *  For most cases, we use our comm system status.
     *
     *  @return bitmask for initiating connections
     *  @since 0.9.34
     */
    public int getOutboundMask(RouterInfo us) {
        // from us
        int cf = 0;
        Status status = ctx.commSystem().getStatus();
        switch (status) {
            case OK:
                // use what we published, as the OK state doesn't tell us about IPv6
                // Addresses.isConnectedIPv6() is too slow
                Collection<RouterAddress> a = us.getAddresses();
                if (a.isEmpty()) {
                    // we are hidden
                    // TODO ipv6
                    if (!isNTCPDisabled())
                        cf |= NTCP_V4;
                    if (!isSSUDisabled())
                        cf |= SSU_V4;
                } else {
                    cf = getConnectMask(a);
                }
                break;

            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
                if (!isNTCPDisabled())
                    cf |= NTCP_V4 | NTCP_V6;
                if (!isSSUDisabled())
                    cf |= SSU_V4 | SSU_V6;
                break;

            case IPV4_DISABLED_IPV6_OK:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            case IPV4_DISABLED_IPV6_FIREWALLED:
                if (!isNTCPDisabled())
                    cf |= NTCP_V6;
                if (!isSSUDisabled())
                    cf |= SSU_V6;
                break;

            case DIFFERENT:
            case IPV4_SNAT_IPV6_UNKNOWN:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
            case REJECT_UNSOLICITED:
            case IPV4_OK_IPV6_UNKNOWN:
            case DISCONNECTED:
            case HOSED:
            case UNKNOWN:
            default:
                if (!isNTCPDisabled())
                    cf |= NTCP_V4;
                if (!isSSUDisabled())
                    cf |= SSU_V4;
                break;
        }
        return cf;
    }

    /** prevent object churn */
    private static final String IHOST[] = { "ihost0", "ihost1", "ihost2" };

    /**
     *  @param addrs non-empty, set your own default if empty
     *  @return bitmask of v4/v6 NTCP/SSU
     *  @since 0.9.34
     */
    private static int getConnectMask(Collection<RouterAddress> addrs) {
        int rv = 0;
        for (RouterAddress ra : addrs) {
            String style = ra.getTransportStyle();
            String host = ra.getHost();
            if ("NTCP".equals(style)) {
                if (host != null) {
                    if (host.contains(":"))
                        rv |= NTCP_V6;
                    else
                        rv |= NTCP_V4;
                }
            } else if ("SSU".equals(style)) {
                if (host == null) {
                    for (int i = 0; i < 2; i++) {
                        String ihost = ra.getOption(IHOST[i]);
                        if (ihost == null)
                            break;
                        if (ihost.contains(":"))
                            rv |= SSU_V6;
                        else
                            rv |= SSU_V4;
                    }
                } else if (host.contains(":")) {
                    rv |= SSU_V6;
                } else {
                    rv |= SSU_V4;
                }
            }
        }
        return rv;
    }
}
