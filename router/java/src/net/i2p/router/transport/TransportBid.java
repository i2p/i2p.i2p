package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.RouterInfo;

/**
 * Provide a bid for how much it would "cost" to transfer a message of a 
 * particular peer
 *
 */
public class TransportBid {
    private int _latencyMs;
    private int _bandwidthBytes;
    private int _msgSize;
    private RouterInfo _router;
    private Date _bidExpiration;
    private Transport _transport;
    
    public TransportBid() {
	setLatencyMs(-1);
	setBandwidthBytes(-1);
	setMessageSize(-1);
	setRouter(null);
	setExpiration(null);
	setTransport(null);
    }
    
    /**
     * How long this transport thinks it would take to send the message
     */
    public int getLatencyMs() { return _latencyMs; }
    public void setLatencyMs(int milliseconds) { _latencyMs = milliseconds; }
    
    /**
     * How many bytes the transport thinks it would need to send to transfer the
     * message successfully
     *
     */
    public int getBandwidthBytes() { return _bandwidthBytes; }
    public void setBandwidthBytes(int numBytes) { _bandwidthBytes = numBytes; }
    
    /** 
     * How large the message in question is, in bytes
     *
     */
    public int getMessageSize() { return _msgSize; }
    public void setMessageSize(int numBytes) { _msgSize = numBytes; }
    
    /**
     * Router to which the message is to be sent
     *
     */
    public RouterInfo getRouter() { return _router; }
    public void setRouter(RouterInfo router) { _router = router; }
    
    /**
     * Specifies how long this bid is "good for"
     */
    public Date getExpiration() { return _bidExpiration; }
    public void setExpiration(Date expirationDate) { _bidExpiration = expirationDate; }
    
    /**
     * Specifies the transport that offered this bid
     */
    public Transport getTransport() { return _transport; }
    public void setTransport(Transport transport) { _transport = transport; }
}
