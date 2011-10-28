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

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;

/**
 * Manages the communication subsystem between peers, including connections, 
 * listeners, transports, connection keys, etc.
 *
 */ 
public abstract class CommSystemFacade implements Service {
    public abstract void processMessage(OutNetMessage msg);
    
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { }
    public void renderStatusHTML(Writer out) throws IOException { renderStatusHTML(out, null, 0); }
    
    /** Create the set of RouterAddress structures based on the router's config */
    public Set<RouterAddress> createAddresses() { return Collections.EMPTY_SET; }
    
    public int countActivePeers() { return 0; }
    public int countActiveSendPeers() { return 0; }
    public boolean haveInboundCapacity(int pct) { return true; }
    public boolean haveOutboundCapacity(int pct) { return true; }
    public boolean haveHighOutboundCapacity() { return true; }
    public List getMostRecentErrorMessages() { return Collections.EMPTY_LIST; }
    
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
     *
     */
    public short getReachabilityStatus() { return STATUS_OK; }
    public void recheckReachability() {}
    public boolean isBacklogged(Hash dest) { return false; }
    public boolean wasUnreachable(Hash dest) { return false; }
    public boolean isEstablished(Hash dest) { return false; }
    public byte[] getIP(Hash dest) { return null; }
    public void queueLookup(byte[] ip) {}
    /** @since 0.8.11 */
    public String getOurCountry() { return null; }
    public String getCountry(Hash peer) { return null; }
    public String getCountryName(String code) { return code; }
    public String renderPeerHTML(Hash peer) {
        return peer.toBase64().substring(0, 4);
    }
    
    /** 
     * Tell other transports our address changed
     */
    public void notifyReplaceAddress(RouterAddress UDPAddr) {}
    /** 
     * These must be increasing in "badness" (see TransportManager.java),
     * but UNKNOWN must be last.
     *
     * We are able to receive unsolicited connections
     */
    public static final short STATUS_OK = 0;
    /**
     * We are behind a symmetric NAT which will make our 'from' address look 
     * differently when we talk to multiple people
     *
     */
    public static final short STATUS_DIFFERENT = 1;
    /**
     * We are able to talk to peers that we initiate communication with, but
     * cannot receive unsolicited connections
     */
    public static final short STATUS_REJECT_UNSOLICITED = 2;
    /**
     * Our detection system is broken (SSU bind port failed)
     */
    public static final short STATUS_HOSED = 3;
    /**
     * Our reachability is unknown
     */
    public static final short STATUS_UNKNOWN = 4;
    
}

/** unused
class DummyCommSystemFacade extends CommSystemFacade {
    public void shutdown() {}
    public void startup() {}
    public void restart() {}
    public void processMessage(OutNetMessage msg) { }    
}
**/
