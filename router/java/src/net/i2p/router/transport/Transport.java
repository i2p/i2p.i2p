package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;
import java.util.Set;

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
    public void rotateAddresses();
    public Set getCurrentAddresses();
    public void addAddressInfo(Properties infoForNewAddress);
    public void setListener(TransportEventListener listener);
    public String getStyle();
    
    public int countActivePeers();
    
    public String renderStatusHTML();
}
