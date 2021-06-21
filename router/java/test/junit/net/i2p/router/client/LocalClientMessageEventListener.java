package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Locale;

import net.i2p.data.Base32;
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
import net.i2p.data.i2cp.HostLookupMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
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
    protected void startCreateSessionJob(SessionConfig config) {
        long exp = _context.clock().now() + 10*60*1000;
        LeaseSet ls = new LeaseSet();
        Lease lease = new Lease();
        lease.setGateway(Hash.FAKE_HASH);
        TunnelId id = new TunnelId(1);
        lease.setTunnelId(id);
        lease.setEndDate(exp);
        ls.addLease(lease);
        _runner.requestLeaseSet(config.getDestination().calculateHash(), ls, exp, null, null);
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
     *  Look only in current local dests
     */
    @Override
    protected void handleHostLookup(HostLookupMessage message) {
        Hash h = message.getHash();
        String name = message.getHostname();
        long reqID = message.getReqID();
        SessionId sessID = message.getSessionId();
        if (h == null && name != null && name.length() == 60) {
            // convert a b32 lookup to a hash lookup
            String nlc = name.toLowerCase(Locale.US);
            if (nlc.endsWith(".b32.i2p")) {
                byte[] b = Base32.decode(nlc.substring(0, 52));
                if (b != null && b.length == Hash.HASH_LENGTH) {
                    h = Hash.create(b);
                }
            }
        }
        Destination d = null;
        if (h != null)
            d = ((LocalClientConnectionRunner)_runner).localLookup(h);
        HostReplyMessage msg;
        if (d != null)
            msg = new HostReplyMessage(sessID, d, reqID);
        else
            msg = new HostReplyMessage(sessID, HostReplyMessage.RESULT_FAILURE, reqID);
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
