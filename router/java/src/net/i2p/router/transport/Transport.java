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
import java.util.Vector;
import net.i2p.data.Hash;

import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.OutNetMessage;

/**
 * Defines a way to send a message to another peer and start listening for messages
 *
 */
public interface Transport {
    public TransportBid bid(RouterInfo toAddress, long dataSize);
    /**
     * Asynchronously send the message as requested in the message and, if the
     * send is successful, queue up any msg.getOnSendJob job, and register it
     * with the OutboundMessageRegistry (if it has a reply selector).  If the
     * send fails, queue up any msg.getOnFailedSendJob
     *
     */
    public void send(OutNetMessage msg);
    public RouterAddress startListening();
    public void stopListening();
    public RouterAddress getCurrentAddress();
    public void setListener(TransportEventListener listener);
    public String getStyle();
    
    public int countActivePeers();    
    public int countActiveSendPeers();
    public Vector getClockSkews();
    public List getMostRecentErrorMessages();
    
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException;
    public short getReachabilityStatus();
    public void recheckReachability();
    public boolean isBacklogged(Hash dest);
    public boolean wasUnreachable(Hash dest);
    
    public boolean isUnreachable(Hash peer);
}
