package net.i2p.router.crypto.ratchet;

import net.i2p.data.Destination;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.ClientMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Send an empty message if the timer expires.
 *
 *  This will be created for incoming NS, NSR,
 *  ACK request blocks, and forward next key blocks.
 *  The vast majority of these will be cancelled before firing,
 *  when streaming sends a response.
 *  This should only fire if streaming drops completely,
 *  and for certain datagram traffic patterns.
 *
 *  @since 0.9.47
 */
class ACKTimer extends SimpleTimer2.TimedEvent {
    private final RouterContext _context;
    private final Log _log;
    private final Destination _from, _to;

    private static final long EXPIRATION = 60*1000;
    // ClientMessageOptions.LS_MASK, don't send LS
    // Never bundle our LS with a ratchet-layer ACK, because we don't need to,
    // and because it will be the wrong LS for subsessions
    private static final int LS_MASK = 0x0100;

    /**
     *  Caller must schedule
     *
     *  @param from local destination ACK will come from, non-null
     *  @param to remote destination ACK will go to, non-null
     *
     */
    public ACKTimer(RouterContext context, Destination from, Destination to) {
        super(context.simpleTimer2());
        _context = context;
        _log = context.logManager().getLog(ACKTimer.class);
        _from = from;
        _to = to;
    }

    public void timeReached() {
        SessionConfig config = _context.clientManager().getClientSessionConfig(_from);
        if (config == null) {
            // Client gone
            return;
        }
        long now = _context.clock().now();
        long exp = now + EXPIRATION;
        MessageId msgID = new MessageId();
        // null payload, no nonce
        ClientMessage cmsg = new ClientMessage(_to, null, config, _from, msgID, 0, exp, LS_MASK);
        _context.clientMessagePool().add(cmsg, true);
        if (_log.shouldInfo())
            _log.info("Sent ratchet ack from " + _from.toBase32() + " to " + _to.toBase32());
    }
}
