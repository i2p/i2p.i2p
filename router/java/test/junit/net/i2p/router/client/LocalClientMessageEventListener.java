package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.router.RouterContext;

/**
 *  For testing
 *
 *  @since 0.9.8
 */
class LocalClientMessageEventListener extends ClientMessageEventListener {

    public LocalClientMessageEventListener(RouterContext context, ClientConnectionRunner runner, boolean enforceAuth) {
        super(context, runner, enforceAuth);
    }

    /**
     *  Immediately send a fake leaseset
     */
    @Override
    protected void startCreateSessionJob() {
        long exp = _context.clock().now() + 10*60*1000;
        LeaseSet ls = new LeaseSet();
        Lease lease = new Lease();
        lease.setGateway(Hash.FAKE_HASH);
        TunnelId id = new TunnelId(1);
        lease.setTunnelId(id);
        Date date = new Date(exp);
        lease.setEndDate(date);
        ls.addLease(lease);
        _runner.requestLeaseSet(ls, exp, null, null);
    }

    /**
     *  Don't tell the netdb or key manager
     */
    @Override
    protected void handleCreateLeaseSet(CreateLeaseSetMessage message) {	
        _runner.leaseSetCreated(message.getLeaseSet());
    }

    /**
     *  Look only in current local dests
     */
    @Override
    protected void handleDestLookup(DestLookupMessage message) {
        Hash h = message.getHash();
        DestReplyMessage msg;
        Destination d = ((LocalClientConnectionRunner)_runner).localLookup(h);
        if (d != null)
            msg = new DestReplyMessage(d);
        else
            msg = new DestReplyMessage(h);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            ime.printStackTrace();
        }
    }

    /**
     *  Send dummy limits
     */
    @Override
    protected void handleGetBWLimits(GetBandwidthLimitsMessage message) {
        int limit = 1024*1024;
        BandwidthLimitsMessage msg = new BandwidthLimitsMessage(limit, limit);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            ime.printStackTrace();
        }
    }
}
