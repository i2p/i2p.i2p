package net.i2p.client.streaming;

import java.util.Set;
import java.util.HashSet;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 *
 */
class PacketQueue {
    private I2PAppContext _context;
    private Log _log;
    private I2PSession _session;
    private byte _buf[];
    
    public PacketQueue(I2PAppContext context, I2PSession session) {
        _context = context;
        _session = session;
        _buf = new byte[36*1024];
        _log = context.logManager().getLog(PacketQueue.class);
    }
    
    /**
     * Add a new packet to be sent out ASAP
     */
    public void enqueue(PacketLocal packet) {
        int size = 0;
        if (packet.shouldSign())
            size = packet.writeSignedPacket(_buf, 0, _context, _session.getPrivateKey());
        else
            size = packet.writePacket(_buf, 0);
        
        SessionKey keyUsed = new SessionKey();
        Set tagsSent = new HashSet();
        try {
            // this should not block!
            boolean sent = _session.sendMessage(packet.getTo(), _buf, 0, size, keyUsed, tagsSent);
            if (!sent) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Send failed for " + packet);
            } else {
                packet.setKeyUsed(keyUsed);
                packet.setTagsSent(tagsSent);
                packet.incrementSends();
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to send the packet " + packet, ise);
        }
    }
}
