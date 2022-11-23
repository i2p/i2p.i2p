package net.i2p.router.transport;
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
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.OutNetMessage;

/**
 * Defines a way to send a message to another peer and start listening for messages.
 *
 * To implement a new or pluggable I2P transport, implement this interface,
 * and add it to TransportManager.startListening().
 *
 * API is subject to change.
 * Please contact us if you're writing a new transport or transport plugin.
 */
public interface Transport {

    /**
     *
     * @param dataSize assumes full 16-byte header, transports should
     *        adjust as necessary
     * @return a bid or null if unwilling to send
     *
     */
    public TransportBid bid(RouterInfo toAddress, int dataSize);

    /**
     * Asynchronously send the message as requested in the message and, if the
     * send is successful, queue up any msg.getOnSendJob job, and register it
     * with the OutboundMessageRegistry (if it has a reply selector).  If the
     * send fails, queue up any msg.getOnFailedSendJob
     *
     */
    public void send(OutNetMessage msg);
    public void startListening();
    public void stopListening();

    /**
     *  What addresses are we currently listening to?
     *  Replaces getCurrentAddress()
     *  @return all addresses, non-null
     *  @since IPv6
     */
    public List<RouterAddress> getCurrentAddresses();

    /**
     *  What address are we currently listening to?
     *  Replaces getCurrentAddress()
     *
     *  Note: An address without a host is considered IPv4.
     *
     *  @param ipv6 true for IPv6 only; false for IPv4 only
     *  @return first matching address or null
     *  @since 0.9.50 lifted from TransportImpl
     */
    public RouterAddress getCurrentAddress(boolean ipv6);

    /**
     *  Do we have any current address?
     *  @since IPv6
     */
    public boolean hasCurrentAddress();

    /**
     *  Ask the transport to update its addresses based on current information and return them
     *  @return all addresses, non-null
     */
    public List<RouterAddress> updateAddress();

    /**
     *  @since IPv6
     */
    public enum AddressSource {
        SOURCE_UPNP("upnp"),
        SOURCE_INTERFACE("local"),
        /** unused */
        SOURCE_CONFIG("config"),
        SOURCE_SSU("ssu");

        private final String cfgstr;

        AddressSource(String cfgstr) {
            this.cfgstr = cfgstr;
        }

        public String toConfigString() {
            return cfgstr;
        }
    }

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called before startListening() to set an initial address,
     *  or after the transport is running.
     *
     *  @param source defined in Transport.java
     *  @param ip typ. IPv4 or IPv6 non-local; may be null to indicate IPv4 failure or port info only
     *  @param port 0 for unknown or unchanged
     */
    public void externalAddressReceived(AddressSource source, byte[] ip, int port);

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called after the transport is running.
     *
     *  TODO externalAddressRemoved(source, ip, port)
     *
     *  @param source defined in Transport.java
     *  @since 0.9.20
     */
    public void externalAddressRemoved(AddressSource source, boolean ipv6);

    /**
     *  Notify a transport of the results of trying to forward a port.
     *
     *  @param ip may be null
     *  @param port the internal port
     *  @param externalPort the external port, which for now should always be the same as
     *                      the internal port if the forwarding was successful.
     */
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason);

    /**
     * What INTERNAL port would the transport like to have forwarded by UPnP.
     * This can't be passed via getCurrentAddress(), as we have to open the port
     * before we can publish the address, and that's the external port anyway.
     *
     * @return port or -1 for none or 0 for any
     */
    public int getRequestedPort();

    /** Who to notify on message availability */
    public void setListener(TransportEventListener listener);

    /** The unique identity of this Transport */
    public String getStyle();
    
    /**
     * @return may or may not be modifiable; check implementation
     * @since 0.9.34
     */
    public Set<Hash> getEstablished();    

    public int countPeers();    
    public int countActivePeers();    
    public int countActiveSendPeers();

    /**
     * @return 8 bytes:
     *         version 1 ipv4 in/out, ipv6 in/out
     *         version 2 ipv4 in/out, ipv6 in/out
     * @since 0.9.57
     */
    public int[] getPeerCounts();

    public boolean haveCapacity();
    public boolean haveCapacity(int pct);

    /**
     *  Previously returned Vector, now List as of 0.9.46.
     */
    public List<Long> getClockSkews();

    public List<String> getMostRecentErrorMessages();
    
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException;

    /**
     *  Previously returned short, now enum as of 0.9.20
     */
    public Status getReachabilityStatus();

    /**
     * @deprecated unused
     */
    @Deprecated
    public void recheckReachability();

    /**
     *  @since 0.9.50 added to interface
     */
    public TransportUtil.IPv6Config getIPv6Config();

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @since 0.9.50 added to interface
     */
    public boolean isIPv4Firewalled();

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @since 0.9.50 added to interface
     */
    public boolean isIPv6Firewalled();

    public boolean isBacklogged(Hash peer);

    /**
     * Was the peer UNreachable (outbound only) the last time we tried it?
     * This is NOT reset if the peer contacts us and it is never expired.
     */
    public boolean wasUnreachable(Hash peer);
    
    public boolean isUnreachable(Hash peer);
    public boolean isEstablished(Hash peer);

    /**
     * Tell the transport that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    public void mayDisconnect(Hash peer);

    /**
     * Tell the transport to disconnect from this peer.
     *
     * @since 0.9.38
     */
    public void forceDisconnect(Hash peer);
}
