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
 * Set of criteria for finding a tunnel from the Tunnel Manager
 *
 */
public class TunnelSelectionCriteria {
    public final static int MAX_PRIORITY = 100;
    public final static int MIN_PRIORITY = 0;
    private int _latencyPriority;
    private int _anonymityPriority;
    private int _reliabilityPriority;
    private int _maxNeeded;
    private int _minNeeded;

    public TunnelSelectionCriteria() {
	setLatencyPriority(0);
	setAnonymityPriority(0);
	setReliabilityPriority(0);
	setMinimumTunnelsRequired(0);
	setMaximumTunnelsRequired(0);
    }
    
    /** priority of the latency for the tunnel */
    public int getLatencyPriority() { return _latencyPriority; }
    public void setLatencyPriority(int latencyPriority) { _latencyPriority = latencyPriority; }
    /** priority of the anonymity for the tunnel */
    public int getAnonymityPriority() { return _anonymityPriority; }
    public void setAnonymityPriority(int anonPriority) { _anonymityPriority = anonPriority; }
    /** priority of the reliability for the tunnel */
    public int getReliabilityPriority() { return _reliabilityPriority; }
    public void setReliabilityPriority(int reliabilityPriority) { _reliabilityPriority = reliabilityPriority; }
    /** max # of tunnels to return */
    public int getMaximumTunnelsRequired() { return _maxNeeded; }
    public void setMaximumTunnelsRequired(int maxNeeded) { _maxNeeded = maxNeeded; }
    /** minimum # of tunnels to return */
    public int getMinimumTunnelsRequired() { return _minNeeded; }
    public void setMinimumTunnelsRequired(int minNeeded) { _minNeeded = minNeeded; }
}
