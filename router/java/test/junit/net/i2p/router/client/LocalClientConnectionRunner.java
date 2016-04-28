package net.i2p.router.client;

import java.net.Socket;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

/**
 *  For testing
 *
 *  @since 0.9.8
 */
class LocalClientConnectionRunner extends ClientConnectionRunner {
    
    /**
     * Create a new runner with the given queues
     *
     */
    public LocalClientConnectionRunner(RouterContext context, ClientManager manager, Socket socket) {
        super(context, manager, socket);
    }
    
    /**
     *  Custom listener
     */
    @Override
    protected I2CPMessageReader.I2CPMessageEventListener createListener() {
        return new LocalClientMessageEventListener(_context, this, true);
    }
    
    /**
     *  Just send the message directly,
     *  don't instantiate a RequestLeaseSetJob
     */
    @Override
    void requestLeaseSet(Hash h, LeaseSet set, long expirationTime, Job onCreateJob, Job onFailedJob) {
        RequestVariableLeaseSetMessage msg = new RequestVariableLeaseSetMessage();
        msg.setSessionId(getSessionId(h));
        for (int i = 0; i < set.getLeaseCount(); i++) {
            Lease lease = set.getLease(i);
            msg.addEndpoint(lease);
        }
        try {
            doSend(msg);
        } catch (I2CPMessageException ime) {
            ime.printStackTrace();
        }
    }

    /**
     *  No job queue, so super NPEs
     */
    @Override
    void updateMessageDeliveryStatus(Destination dest, MessageId id, long messageNonce, int status) {
        if (messageNonce <= 0)
            return;
        MessageStatusMessage msg = new MessageStatusMessage();
        msg.setMessageId(id.getMessageId());
        msg.setSessionId(getSessionId(dest.calculateHash()).getSessionId());
        // has to be >= 0, it is initialized to -1
        msg.setNonce(messageNonce);
        msg.setSize(0);
        msg.setStatus(status);
        try {
            doSend(msg);
        } catch (I2CPMessageException ime) {
            _log.warn("Error updating the status for " + id, ime);
        }
    }

    /**
     *  So LocalClientMessageEventListener can lookup other local dests
     */
    public Destination localLookup(Hash h) {
        for (Destination d : _manager.getRunnerDestinations()) {
            if (d.calculateHash().equals(h))
                return d;
        }
        return null;
    }
}
