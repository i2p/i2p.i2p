package net.i2p.client.streaming;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.util.Log;

/**
 * Receive raw information from the I2PSession and turn it into
 * Packets, if we can.
 *<p>
 * I2PSession -> MessageHandler -> PacketHandler -> ConnectionPacketHandler -> MessageInputStream
 */
class MessageHandler implements I2PSessionMuxedListener {
    private final ConnectionManager _manager;
    private final I2PAppContext _context;
    private final Log _log;
    private final Set<I2PSocketManager.DisconnectListener> _listeners;
    
    public MessageHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        _listeners = new CopyOnWriteArraySet();
        _log = ctx.logManager().getLog(MessageHandler.class);
        _context.statManager().createRateStat("stream.packetReceiveFailure", "When do we fail to decrypt or otherwise receive a packet sent to us?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
    }
        
    /** Instruct the client that the given session has received a message with
     * size # of bytes.
     * This shouldn't be called anymore since we are registering as a muxed listener.
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message
     */
    public void messageAvailable(I2PSession session, int msgId, long size) {
        messageAvailable(session, msgId, size, I2PSession.PROTO_UNSPECIFIED,
                         I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED);
    }

    /** Instruct the client that the given session has received a message with
     * size # of bytes.
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message
     */
    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
        byte data[] = null;
        try {
            data = session.receiveMessage(msgId);
        } catch (I2PSessionException ise) {
            _context.statManager().addRateData("stream.packetReceiveFailure", 1, 0);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving the message", ise);
            return;
        }
        if (data == null) return;
        Packet packet = new Packet();
        try {
            packet.readPacket(data, 0, data.length);
            packet.setRemotePort(fromPort);
            packet.setLocalPort(toPort);
            _manager.getPacketHandler().receivePacket(packet);
        } catch (IllegalArgumentException iae) {
            _context.statManager().addRateData("stream.packetReceiveFailure", 1, 0);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received an invalid packet", iae);
        }
    }

    /** Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     * @param session session to report abuse to
     * @param severity how bad the abuse is
     */
    public void reportAbuse(I2PSession session, int severity) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("Abuse reported with severity " + severity);
        _manager.disconnectAllHard();
    }

    /**
     * Notify the client that the session has been terminated
     *
     * @param session that has been terminated
     */
    public void disconnected(I2PSession session) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("I2PSession disconnected");
        _manager.disconnectAllHard();
        
        for (Iterator<I2PSocketManager.DisconnectListener> iter = _listeners.iterator(); iter.hasNext(); ) {
            I2PSocketManager.DisconnectListener lsnr = iter.next();
            lsnr.sessionDisconnected();
        }
        _listeners.clear();
    }

    /**
     * Notify the client that some error occurred
     *
     * @param session of the client
     * @param message to send to the client about the error
     * @param error the actual error
     */
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("error occurred: " + message + "- " + error.getMessage()); 
        if (_log.shouldLog(Log.WARN))
            _log.warn("cause", error);
        //_manager.disconnectAllHard();
    }
    
    public void addDisconnectListener(I2PSocketManager.DisconnectListener lsnr) { 
            _listeners.add(lsnr);
    }
    public void removeDisconnectListener(I2PSocketManager.DisconnectListener lsnr) {
            _listeners.remove(lsnr);
    }
}
