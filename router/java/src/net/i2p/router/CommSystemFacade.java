package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;

/**
 * Manages the communication subsystem between peers, including connections, 
 * listeners, transports, connection keys, etc.
 *
 */ 
public abstract class CommSystemFacade implements Service {
    public abstract void processMessage(OutNetMessage msg);
    
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { }
    public void renderStatusHTML(Writer out) throws IOException { renderStatusHTML(out, null, 0); }
    
    /** Create the list of RouterAddress structures based on the router's config */
    public List<RouterAddress> createAddresses() { return Collections.emptyList(); }
    
    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to or received a message from in the last five minutes.
     */
    public abstract int countActivePeers();

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to in the last minute.
     *  Unused for anything, to be removed.
     */
    public abstract int countActiveSendPeers();

    public boolean haveInboundCapacity(int pct) { return true; }
    public boolean haveOutboundCapacity(int pct) { return true; }
    public boolean haveHighOutboundCapacity() { return true; }
    public List<String> getMostRecentErrorMessages() { return Collections.emptyList(); }
    
    /**
     * Median clock skew of connected peers in seconds, or null if we cannot answer.
     * CommSystemFacadeImpl overrides this.
     */
    public Long getMedianPeerClockSkew() { return null; }
    
    /**
     * Return framed average clock skew of connected peers in seconds, or null if we cannot answer.
     * CommSystemFacadeImpl overrides this.
     */
    public long getFramedAveragePeerClockSkew(int percentToInclude) { return 0; }
    
    /**
     * Determine under what conditions we are remotely reachable.
     * For internal use only.
     * Not recommended for plugins or embedded applications, as
     * the integer codes may change. Use getStatus() instead.
     *
     * @deprecated use getStatus()
     */
    @Deprecated
    public short getReachabilityStatus() { return (short) getStatus().getCode(); }
    
    /**
     * Determine under what conditions we are remotely reachable.
     * @since 0.9.20
     */
    public Status getStatus() { return Status.OK; }

    /**
     * @deprecated unused
     */
    @Deprecated
    public void recheckReachability() {}

    public boolean isBacklogged(Hash peer) { return false; }
    public boolean wasUnreachable(Hash peer) { return false; }
    public abstract boolean isEstablished(Hash peer);
    public byte[] getIP(Hash dest) { return null; }
    public void queueLookup(byte[] ip) {}
    
    /**
     * Tell the comm system that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    public void mayDisconnect(Hash peer) {}

    /** @since 0.8.11 */
    public String getOurCountry() { return null; }

    /** @since 0.8.13 */
    public boolean isInBadCountry() { return false; }

    /** @since 0.9.16 */
    public boolean isInBadCountry(Hash peer) { return false; }

    /** @since 0.9.16 */
    public boolean isInBadCountry(RouterInfo ri) { return false; }

    public String getCountry(Hash peer) { return null; }
    public String getCountryName(String code) { return code; }
    public String renderPeerHTML(Hash peer) {
        return peer.toBase64().substring(0, 4);
    }
    
    /**
     *  @return SortedMap of style to Transport (a copy)
     *  @since 0.9.31
     */
    public SortedMap<String, Transport> getTransports() {
        return new TreeMap<String, Transport>();
    }
    
    /**
     *  Get all the peers we are connected to.
     *  This should be more efficient than repeated calls to isEstablished()
     *  if you have to check a lot.
     *
     *  @return the hashes of all the routers we are connected to, non-null
     *  @since 0.9.34
     */
    public abstract Set<Hash> getEstablished();
    
    /** @since 0.8.13 */
    public boolean isDummy() { return true; }

    /** 
     * Tell other transports our address changed
     */
    public void notifyReplaceAddress(RouterAddress address) {}

    /** 
     * Tell other transports our address changed
     * @since 0.9.20
     */
    public void notifyRemoveAddress(RouterAddress address) {}

    /** 
     * Tell other transports our address changed
     * @since 0.9.20
     */
    public void notifyRemoveAddress(boolean ipv6) {}

    /**
     *  Pluggable transport
     *  @since 0.9.16
     */
    public void registerTransport(Transport t) {}

    /**
     *  Pluggable transport
     *  @since 0.9.16
     */
    public void unregisterTransport(Transport t) {}

    /**
     *  Hook for pluggable transport creation.
     *  @since 0.9.16
     */
    public DHSessionKeyBuilder.Factory getDHFactory() { return null; }

    /*
     *  Reachability status codes
     *
     *	IPv4	IPv6	Status
     *	----	----	------
     *	ok	ok	OK 0
     *	ok	x	OK 0
     *	ok	unk	OK/UNKNOWN 1
     *	ok	fw	OK/FIREWALLED 2
     *
     *	x	ok	DISABLED/OK 5
     *	x	x	HOSED 12
     *	x	unk	DISABLED/UNKNOWN 10
     *	x	fw	DISABLED/FIREWALLED 11
     *
     *	unk	ok	UNKNOWN/OK 3
     *	unk	x	UNKNOWN 14
     *	unk	unk	UNKNOWN 14
     *	unk	fw	UNKNOWN/FIREWALLED 9
     *
     *	fw	ok	FIREWALLED/OK 4
     *	fw	x	FIREWALLED 8
     *	fw	unk	FIREWALLED/UNKNOWN 7
     *	fw	fw	FIREWALLED 8
     *
     *	sym	any	DIFFERENT 6 (TODO add IPv6 states or not worth it?)
     *	disconnected	DISCONNECTED 12
     *	hosed		HOSED 13
     */

    /** 
     * These must be increasing in "badness" (see TransportManager.java),
     * but UNKNOWN must be last.
     *
     * We are able to receive unsolicited connections
     * on all enabled transports
     */
    public static final short STATUS_OK = 0;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We can receive unsolicited connections on IPv4.
     *  We might be able to receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_OK_IPV6_UNKNOWN = 2;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We can receive unsolicited connections on IPv4.
     *  We cannot receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_OK_IPV6_FIREWALLED = 1;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We may be able to receive unsolicited connections on IPv4.
     *  We can receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_UNKNOWN_IPV6_OK = 4;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We cannot receive unsolicited connections on IPv4.
     *  We can receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_FIREWALLED_IPV6_OK = 3;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  IPv4 is disabled.
     *  We can receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_DISABLED_IPV6_OK = 5;

    /**
     *  We are behind a symmetric NAT which will make our 'from' address look 
     *  differently when we talk to multiple people
     *  We can receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_SNAT_IPV6_OK = 6;

    /**
     * We are behind a symmetric NAT which will make our 'from' address look 
     * differently when we talk to multiple people
     *
     */
    public static final short STATUS_DIFFERENT = 7;

    /**
     *  We are behind a symmetric NAT which will make our 'from' address look 
     *  differently when we talk to multiple people
     *  We might be able to receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_SNAT_IPV6_UNKNOWN = 8;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We cannot receive unsolicited connections on IPv4.
     *  We might be able to receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_FIREWALLED_IPV6_UNKNOWN = 10;

    /**
     * We are able to talk to peers that we initiate communication with, but
     * cannot receive unsolicited connections, i.e. Firewalled,
     * on all enabled transports.
     */
    public static final short STATUS_REJECT_UNSOLICITED = 9;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We may be able to receive unsolicited connections on IPv4.
     *  We cannot receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_UNKNOWN_IPV6_FIREWALLED = 11;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  IPv4 is disabled.
     *  We might be able to receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_DISABLED_IPV6_UNKNOWN = 13;

    /** 
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  IPv4 is disabled.
     *  We can receive unsolicited connections on IPv6.
     *  @since 0.9.20
     */
    public static final short STATUS_IPV4_DISABLED_IPV6_FIREWALLED = 12;

    /**
     *  We have no network interface at all enabled transports
     *  @since 0.9.4
     */
    public static final short STATUS_DISCONNECTED = 14;

    /**
     * Our detection system is broken (SSU bind port failed)
     */
    public static final short STATUS_HOSED = 15;

    /**
     * Our reachability is unknown on all
     */
    public static final short STATUS_UNKNOWN = 16;

    /** 
     *  Since the codes may change.
     *  @since 0.9.20
     */
    public enum Status {
        /** IPv4 OK, IPv6 OK or disabled or no address */
        OK(STATUS_OK, _x("OK")),
        IPV4_OK_IPV6_UNKNOWN(STATUS_IPV4_OK_IPV6_UNKNOWN, _x("IPv4: OK; IPv6: Testing")),
        IPV4_OK_IPV6_FIREWALLED(STATUS_IPV4_OK_IPV6_FIREWALLED, _x("IPv4: OK; IPv6: Firewalled")),
        IPV4_UNKNOWN_IPV6_OK(STATUS_IPV4_UNKNOWN_IPV6_OK, _x("IPv4: Testing; IPv6: OK")),
        IPV4_FIREWALLED_IPV6_OK(STATUS_IPV4_FIREWALLED_IPV6_OK, _x("IPv4: Firewalled; IPv6: OK")),
        IPV4_DISABLED_IPV6_OK(STATUS_IPV4_DISABLED_IPV6_OK, _x("IPv4: Disabled; IPv6: OK")),
        IPV4_SNAT_IPV6_OK(STATUS_IPV4_SNAT_IPV6_OK, _x("IPv4: Symmetric NAT; IPv6: OK")),
        /** IPv4 symmetric NAT, IPv6 firewalled or disabled or no address */
        DIFFERENT(STATUS_DIFFERENT, _x("Symmetric NAT")),
        IPV4_SNAT_IPV6_UNKNOWN(STATUS_IPV4_SNAT_IPV6_UNKNOWN, _x("IPv4: Symmetric NAT; IPv6: Testing")),
        IPV4_FIREWALLED_IPV6_UNKNOWN(STATUS_IPV4_FIREWALLED_IPV6_UNKNOWN, _x("IPv4: Firewalled; IPv6: Testing")),
        /** IPv4 firewalled, IPv6 firewalled or disabled or no address */
        REJECT_UNSOLICITED(STATUS_REJECT_UNSOLICITED, _x("Firewalled")),
        IPV4_UNKNOWN_IPV6_FIREWALLED(STATUS_IPV4_UNKNOWN_IPV6_FIREWALLED, _x("IPv4: Testing; IPv6: Firewalled")),
        IPV4_DISABLED_IPV6_UNKNOWN(STATUS_IPV4_DISABLED_IPV6_UNKNOWN, _x("IPv4: Disabled; IPv6: Testing")),
        IPV4_DISABLED_IPV6_FIREWALLED(STATUS_IPV4_DISABLED_IPV6_FIREWALLED, _x("IPv4: Disabled; IPv6: Firewalled")),
        DISCONNECTED(STATUS_DISCONNECTED, _x("Disconnected")),
        HOSED(STATUS_HOSED, _x("Port Conflict")),
        UNKNOWN(STATUS_UNKNOWN, _x("Testing"));

        private final int code;
        private final String status;

        Status(int code, String status) {
            this.code = code;
            this.status = status;
        }

        public int getCode() {
            return code;
        }

        /** 
         *  merge the new Status with the old Status
         */
        public static Status merge(Status oldStatus, Status newStatus) {
            // shortcut newStatus
            if (oldStatus == newStatus || newStatus == UNKNOWN)
                return oldStatus;
            // shortcut oldStatus
            if (oldStatus == UNKNOWN || oldStatus == DISCONNECTED || oldStatus == HOSED)
                return newStatus;
            switch (newStatus) {
                case IPV4_OK_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                            return oldStatus;

                        case DIFFERENT:
                        case REJECT_UNSOLICITED:
                            return newStatus;

                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return IPV4_OK_IPV6_FIREWALLED;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return IPV4_OK_IPV6_FIREWALLED;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                case IPV4_UNKNOWN_IPV6_OK:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_OK_IPV6_FIREWALLED:
                            return OK;

                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case DIFFERENT:
                        case IPV4_SNAT_IPV6_OK:
                            return oldStatus;

                        case REJECT_UNSOLICITED:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return IPV4_DISABLED_IPV6_OK;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return newStatus;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                            return OK;

                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_DISABLED_IPV6_UNKNOWN:
                            return IPV4_DISABLED_IPV6_OK;

                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return IPV4_SNAT_IPV6_OK;

                        default:
                            return newStatus;
                    }

                case IPV4_FIREWALLED_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return REJECT_UNSOLICITED;

                        case DIFFERENT:
                        case REJECT_UNSOLICITED:
                            return newStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return REJECT_UNSOLICITED;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                case IPV4_UNKNOWN_IPV6_FIREWALLED:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                            return IPV4_OK_IPV6_FIREWALLED;

                        case IPV4_OK_IPV6_FIREWALLED:
                            return oldStatus;

                        case REJECT_UNSOLICITED:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return REJECT_UNSOLICITED;

                        case IPV4_DISABLED_IPV6_OK:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        case DIFFERENT:
                        case IPV4_SNAT_IPV6_OK:
                            return oldStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return newStatus;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                            return IPV4_OK_IPV6_FIREWALLED;

                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                            return REJECT_UNSOLICITED;

                        case IPV4_DISABLED_IPV6_UNKNOWN:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return DIFFERENT;

                        default:
                            return newStatus;
                    }

                case IPV4_DISABLED_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return IPV4_DISABLED_IPV6_OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                        case REJECT_UNSOLICITED:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        case DIFFERENT:
                            return newStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return IPV4_DISABLED_IPV6_OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                case IPV4_SNAT_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return IPV4_SNAT_IPV6_OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                        case REJECT_UNSOLICITED:
                            return DIFFERENT;

                        case DIFFERENT:
                            return newStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return IPV4_SNAT_IPV6_OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return DIFFERENT;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                case UNKNOWN:
                    return oldStatus;

                default:
                    return newStatus;
            }
        }

        /** 
         *  Readable status, not translated
         */
        public String toStatusString() {
            return status;
        }

        @Override
        public String toString() {
            return super.toString() + " (" + code + "; " + status + ')';
        }
    
        /** 
         *  Tag for translation.
         */
        private static String _x(String s) { return s; }
    }
}

/** unused
class DummyCommSystemFacade extends CommSystemFacade {
    public void shutdown() {}
    public void startup() {}
    public void restart() {}
    public void processMessage(OutNetMessage msg) { }    
}
**/
