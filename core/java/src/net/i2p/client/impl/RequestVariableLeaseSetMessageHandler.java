package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Lease;
import net.i2p.data.Lease2;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.MetaLease;
import net.i2p.data.MetaLeaseSet;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP RequestVariableLeaseSetMessage from the router by granting all leases,
 * retaining the individual expiration time for each lease.
 *
 * @since 0.9.7
 */
class RequestVariableLeaseSetMessageHandler extends RequestLeaseSetMessageHandler {

    public RequestVariableLeaseSetMessageHandler(I2PAppContext context) {
        super(context, RequestVariableLeaseSetMessage.MESSAGE_TYPE);
    }
    
    @Override
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        RequestVariableLeaseSetMessage msg = (RequestVariableLeaseSetMessage) message;
        boolean isLS2 = requiresLS2(session);
        LeaseSet leaseSet;
        if (isLS2) {
            LeaseSet2 ls2;
            if (_ls2Type == DatabaseEntry.KEY_TYPE_LS2) {
                ls2 = new LeaseSet2();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                ls2 = new EncryptedLeaseSet();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                ls2 = new MetaLeaseSet();
            } else {
              session.propogateError("Unsupported LS2 type", new Exception());
              session.destroySession();
              return;
            }
            if (Boolean.parseBoolean(session.getOptions().getProperty("i2cp.dontPublishLeaseSet")))
                ls2.setUnpublished();
            // ensure 1-second resolution timestamp is higher than last one
            long now = Math.max(_context.clock().now(), session.getLastLS2SignTime() + 1000);
            ls2.setPublished(now);
            session.setLastLS2SignTime(now);
            leaseSet = ls2;
        } else {
            leaseSet = new LeaseSet();
        }
        // Full Meta support TODO
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease;
            if (isLS2) {
                // convert Lease to Lease2
                Lease old = msg.getEndpoint(i);
                if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                    lease = new MetaLease();
                } else {
                    lease = new Lease2();
                    lease.setTunnelId(old.getTunnelId());
                }
                lease.setGateway(old.getGateway());
                lease.setEndDate(old.getEndTime());
            } else {
                lease = msg.getEndpoint(i);
            }
            leaseSet.addLease(lease);
        }
        signLeaseSet(leaseSet, isLS2, session);
    }
}
