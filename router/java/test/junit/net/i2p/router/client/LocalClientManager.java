package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import gnu.getopt.Getopt;

import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.SimpleTimer2;

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
    private static int dropX1000 = 0, jitter = 0, latency = 0;

    /**
     *  @param context stub, may be constructed with new RouterContext(null),
     *                 no initAll() necessary
     */
    public LocalClientManager(RouterContext context, int port) {
        super(context, port);
    }

    @Override
    protected void startListeners() {
        ClientListenerRunner listener = new LocalClientListenerRunner(_ctx, this, _port);
        Thread t = new I2PThread(listener, "ClientListener:" + _port, true);
        t.start();
        _listeners.add(listener);
        _isStarted = true;
    }

    /**
     * Local only
     * TODO: we could have per-destination delay/drop parameters in the client options
     *
     * @param flags ignored for local
     */
    @Override
    void distributeMessage(ClientConnectionRunner sender, Destination fromDest, Destination toDest, Payload payload,
                           MessageId msgId, long messageNonce, long expiration, int flags) { 
        // check if there is a runner for it
        ClientConnectionRunner runner = getRunner(toDest);
        if (runner != null) {
            if (dropX1000 > 0) {
                if (100 * 1000 * _ctx.random().nextFloat() < dropX1000) {
                    System.out.println("Message " + msgId + " DROPPED randomly");
                    // pretend success
                    sender.updateMessageDeliveryStatus(fromDest, msgId, messageNonce, MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS);
                }
            }
            if (latency > 0 || jitter > 0) {
                int delay = latency;
                if (jitter > 0)
                    delay += (int) (jitter * _ctx.random().nextGaussian());
                if (delay > 0) {
                    //System.out.println("Message " + msgId + " DELAYED " + delay + " ms");
                    DelayedSend ds = new DelayedSend(_ctx, sender, runner, fromDest, toDest, payload, msgId, messageNonce);
                    ds.schedule(delay);
                    return;
                }
            }
            boolean ok = runner.receiveMessage(toDest, fromDest, payload);
            int rc = ok ? MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL : MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL;
            sender.updateMessageDeliveryStatus(fromDest, msgId, messageNonce, rc);
        } else {
            // remote.  ignore.
            System.out.println("Message " + msgId + " is targeting a REMOTE destination - DROPPED");
            sender.updateMessageDeliveryStatus(fromDest, msgId, messageNonce, MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE);
        }
    }

    private static class DelayedSend extends SimpleTimer2.TimedEvent {
        private final ClientConnectionRunner s, r;
        private final Destination fd, td;
        private final Payload pl;
        private final MessageId id;
        private final long nonce;

        public DelayedSend(RouterContext ctx, ClientConnectionRunner sender, ClientConnectionRunner runner,
                           Destination fromDest, Destination toDest, Payload payload,
                           MessageId msgId, long messageNonce) {
            super(ctx.simpleTimer2());
            s = sender; r = runner; fd = fromDest;
            td = toDest; pl = payload;
            id = msgId; nonce = messageNonce;
        }

        public void timeReached() {
            boolean ok = r.receiveMessage(td, fd, pl);
            int rc = ok ? MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL : MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL;
            s.updateMessageDeliveryStatus(fd, id, nonce, rc);
        }
    }

    public static void main(String args[]) {
        int dropX1000 = 0, jitter = 0, latency = 0;
        int port = ClientManagerFacadeImpl.DEFAULT_PORT;
        boolean error = false;
        Getopt g = new Getopt("LocalClientManager", args, "d:j:l:p:");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
                switch (c) {

                    case 'd':
                        dropX1000 = (int) (1000 * Double.parseDouble(g.getOptarg()));
                        if (dropX1000 < 0 || dropX1000 >= 100 * 1000)
                            error = true;
                        break;

                    case 'j':
                        jitter = Integer.parseInt(g.getOptarg());
                        if (jitter < 0)
                            error = true;
                        break;

                    case 'l':
                        latency = Integer.parseInt(g.getOptarg());
                        if (latency < 0)
                            error = true;
                        break;

                    case 'p':
                        port = Integer.parseInt(g.getOptarg());
                        if (port < 1024 || port > 65535)
                            error = true;
                        break;

                    default:
                        error = true;
                }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }
        if (error || args.length - g.getOptind() > 0) {
            usage();
            System.exit(1);
        }

        Properties props = new Properties();
        // prevent NTP queries
        props.setProperty("time.disabled", "true");
        RouterContext ctx = new RouterContext(null, props);
        LocalClientManager mgr = new LocalClientManager(ctx, port);
        mgr.dropX1000 = dropX1000;
        mgr.jitter = jitter;
        mgr.latency = latency;
        mgr.start();
        System.out.println("Listening on port " + port);
        try { Thread.sleep(60*60*1000); } catch (InterruptedException ie) {}
        System.out.println("Done listening on port " + port);
    }

    private static void usage() {
        System.err.println("usage: LocalClientManager\n" +
                           "         [-d droppercent] // 0.0 - 99.99999 (default 0)\n" +
                           "         [-j jitter]      // (integer ms for 1 std. deviation, default 0)\n" +
                           "         [-l latency]     // (integer ms, default 0)\n" +
                           "         [-p port]        // (I2CP port, default 7654)");
    }
}
