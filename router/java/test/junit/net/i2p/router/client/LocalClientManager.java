package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;

/**
 * For testing clients without a full router.
 * A complete router-side I2CP implementation, without requiring a router
 * or any RouterContext subsystems or threads.
 * Clients may connect only to other local clients.
 * Lookups and bw limit messages also supported.
 *
 * @since 0.9.8
 */
class LocalClientManager extends ClientManager {

    /**
     *  @param context stub, may be constructed with new RouterContext(null),
     *                 no initAll() necessary
     */
    public LocalClientManager(RouterContext context, int port) {
        super(context, port);
    }

    @Override
    protected void startListeners() {
        _listener = new LocalClientListenerRunner(_ctx, this, _port);
        Thread t = new I2PThread(_listener, "ClientListener:" + _port, true);
        t.start();
        _isStarted = true;
    }

    /**
     * Local only
     * TODO: add simulated delay and random drops to test streaming.
     *
     * @param flags ignored for local
     */
    @Override
    void distributeMessage(Destination fromDest, Destination toDest, Payload payload,
                           MessageId msgId, long messageNonce, long expiration, int flags) { 
        // check if there is a runner for it
        ClientConnectionRunner sender = getRunner(fromDest);
        ClientConnectionRunner runner = getRunner(toDest);
        if (runner != null) {
            runner.receiveMessage(toDest, fromDest, payload);
            if (sender != null)
                sender.updateMessageDeliveryStatus(msgId, messageNonce, MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL);
        } else {
            // remote.  ignore.
            System.out.println("Message " + msgId + " is targeting a REMOTE destination - DROPPED");
            if (sender != null)
                sender.updateMessageDeliveryStatus(msgId, messageNonce, MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE);
        }
    }

    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(null);
        int port = ClientManagerFacadeImpl.DEFAULT_PORT;
        ClientManager mgr = new LocalClientManager(ctx, port);
        mgr.start();
        System.out.println("Listening on port " + port);
        try { Thread.sleep(5*60*1000); } catch (InterruptedException ie) {}
        System.out.println("Done listening on port " + port);
    }
}
