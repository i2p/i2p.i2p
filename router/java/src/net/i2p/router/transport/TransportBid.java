package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Provide a bid for how much it would "cost" to transfer a message of a 
 * particular peer
 *
 */
public class TransportBid {
    private int _latencyMs;
    //private int _bandwidthBytes;
    //private int _msgSize;
    //private RouterInfo _router;
    //private long _bidExpiration;
    private Transport _transport;
    
    public static final int TRANSIENT_FAIL = 999999;

    public TransportBid() {
        _latencyMs = -1;
        //_bandwidthBytes = -1;
        //_msgSize = -1;
    }
    
    /**
     * How long this transport thinks it would take to send the message
     * This is the actual bid value, lower is better, and it doesn't really have
     * anything to do with latency.
     */
    public int getLatencyMs() { return _latencyMs; }
    public void setLatencyMs(int milliseconds) { _latencyMs = milliseconds; }
    
    /**
     * How many bytes the transport thinks it would need to send to transfer the
     * message successfully
     *
     */
    //public int getBandwidthBytes() { return _bandwidthBytes; }
    //public void setBandwidthBytes(int numBytes) { _bandwidthBytes = numBytes; }
    
    /** 
     * How large the message in question is, in bytes
     *
     */
    //public int getMessageSize() { return _msgSize; }
    //public void setMessageSize(int numBytes) { _msgSize = numBytes; }
    
    /**
     * Router to which the message is to be sent
     *
     */
    //public RouterInfo getRouter() { return _router; }
    //public void setRouter(RouterInfo router) { _router = router; }
    
    /**
     * Specifies how long this bid is "good for"
     */
    //public long getExpiration() { return _bidExpiration; }
    //public void setExpiration(long expirationDate) { _bidExpiration = expirationDate; }
    //public void setExpiration(long expirationDate) { setExpiration(new Date(expirationDate)); }
    
    /**
     * Specifies the transport that offered this bid
     */
    public Transport getTransport() { return _transport; }
    public void setTransport(Transport transport) { _transport = transport; }
}
