package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Set;

/**
 * Manages the communication subsystem between peers, including connections, 
 * listeners, transports, connection keys, etc.
 *
 */ 
public abstract class CommSystemFacade implements Service {
    public abstract void processMessage(OutNetMessage msg);
    
    public String renderStatusHTML() { return ""; }
    
    /** Create the set of RouterAddress structures based on the router's config */
    public Set createAddresses() { return new HashSet(); }
}

class DummyCommSystemFacade extends CommSystemFacade {
    public void shutdown() {}
    public void startup() {}
    public void processMessage(OutNetMessage msg) { }
}
