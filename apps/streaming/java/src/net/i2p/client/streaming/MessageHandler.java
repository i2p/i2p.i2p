package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionException;
import net.i2p.util.Log;

/**
 *
 */
public class MessageHandler implements I2PSessionListener {
    private ConnectionManager _manager;
    private I2PAppContext _context;
    private Log _log;
    
    public MessageHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        _log = ctx.logManager().getLog(MessageHandler.class);
    }
        
    /** Instruct the client that the given session has received a message with
     * size # of bytes.
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message
     */
    public void messageAvailable(I2PSession session, int msgId, long size) {
        byte data[] = null;
        try {
            data = session.receiveMessage(msgId);
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving the message", ise);
            return;
        }
        Packet packet = new Packet();
        try {
            packet.readPacket(data, 0, data.length);
            _manager.getPacketHandler().receivePacket(packet);
        } catch (IllegalArgumentException iae) {
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
     */
    public void disconnected(I2PSession session) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("I2PSession disconnected");
        _manager.disconnectAllHard();
    }

    /**
     * Notify the client that some error occurred
     *
     */
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("error occurred: " + message, error);
        //_manager.disconnectAllHard();
    }
}
