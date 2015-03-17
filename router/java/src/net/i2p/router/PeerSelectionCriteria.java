package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines the criteria for selecting a set of peers for use when searching the
 * PeerManager
 *
 * Only used by PeerTestJob, which may not have a point.
 */
public class PeerSelectionCriteria {
    /** The peers will be used in a tunnel */
    public final static int PURPOSE_TUNNEL = 1;
    /** The peers will be used for garlic routed messages */
    public final static int PURPOSE_GARLIC = 2;
    /** The peers will be used for a source routed reply block message */
    public final static int PURPOSE_SOURCE_ROUTE = 3;
    /** The peers will be used for a test message */
    public final static int PURPOSE_TEST = 4;
    
    private int _minReq;
    private int _maxReq;
    private int _purpose;
    
    /** Minimum number of peers required */
    public int getMinimumRequired() { return _minReq; }
    public void setMinimumRequired(int min) { _minReq = min; }
    /** Maximum number of peers required */
    public int getMaximumRequired() { return _maxReq; }
    public void setMaximumRequired(int max) { _maxReq = max; }
    /** Purpose for which the peers will be used */
    public int getPurpose() { return _purpose; }
    public void setPurpose(int purpose) { _purpose = purpose; }
}
