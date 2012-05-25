package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;

/**
 * Manage all interactions with clients 
 *
 * @author jrandom
 */
public class DummyClientManagerFacade extends ClientManagerFacade {
    private RouterContext _context;
    public DummyClientManagerFacade(RouterContext ctx) {
        _context = ctx;
    }
    public boolean isLocal(Hash destHash) { return true; }
    public boolean isLocal(Destination dest) { return true; }
    public void reportAbuse(Destination dest, String reason, int severity) { }
    public void messageReceived(ClientMessage msg) {}
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, 
                                Job onCreateJob, Job onFailedJob) { 
        _context.jobQueue().addJob(onFailedJob);
    }
    public void startup() {}    
    public void stopAcceptingClients() { }
    public void shutdown() {}
    public void restart() {}
    
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, boolean delivered) {}
    
    public SessionConfig getClientSessionConfig(Destination _dest) { return null; }
    public SessionKeyManager getClientSessionKeyManager(Destination _dest) { return null; }
    
    public void requestLeaseSet(Hash dest, LeaseSet set) {}
    
}

