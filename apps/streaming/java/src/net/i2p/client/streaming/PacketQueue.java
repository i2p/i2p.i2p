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
        packet.prepare();
        int size = 0;
        if (packet.shouldSign())
            size = packet.writeSignedPacket(_buf, 0, _context, _session.getPrivateKey());
        else
            size = packet.writePacket(_buf, 0);
        
        SessionKey keyUsed = packet.getKeyUsed();
        if (keyUsed == null)
            keyUsed = new SessionKey();
        Set tagsSent = packet.getTagsSent();
        if (tagsSent == null)
            tagsSent = new HashSet();
        try {
            // cache this from before sendMessage
            String conStr = (packet.getConnection() != null ? packet.getConnection().toString() : "");
            if (packet.getAckTime() > 0) {
                _log.debug("Not resending " + packet);
                return;
            }
            // this should not block!
            long begin = _context.clock().now();
            boolean sent = _session.sendMessage(packet.getTo(), _buf, 0, size, keyUsed, tagsSent);
            long end = _context.clock().now();
            if (!sent) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Send failed for " + packet);
            } else {
                packet.setKeyUsed(keyUsed);
                packet.setTagsSent(tagsSent);
                packet.incrementSends();
                if (_log.shouldLog(Log.DEBUG)) {
                    String msg = "SEND " + packet + (tagsSent.size() > 0 
                                                     ? " with " + tagsSent.size() + " tags"
                                                     : "")
                                                     + " send # " + packet.getNumSends()
                                                     + " sendTime: " + (end-begin)
                                                     + " con: " + conStr;
                    _log.debug(msg);
                }
                PacketHandler.displayPacket(packet, "SEND");
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to send the packet " + packet, ise);
        }
    }
    
}
