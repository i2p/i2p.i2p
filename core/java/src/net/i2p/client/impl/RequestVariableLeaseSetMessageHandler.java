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
import net.i2p.data.LeaseSet;
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
        LeaseSet leaseSet = new LeaseSet();
        for (int i = 0; i < msg.getEndpoints(); i++) {
            leaseSet.addLease(msg.getEndpoint(i));
        }
        signLeaseSet(leaseSet, session);
    }
}
