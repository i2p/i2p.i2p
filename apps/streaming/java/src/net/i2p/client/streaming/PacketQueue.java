package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.SendMessageOptions;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Queue out packets to be sent through the session.  
 * Well, thats the theory at least... in practice we just
 * send them immediately with no blocking, since the 
 * mode=bestEffort doesnt block in the SDK.
 *<p>
 * MessageOutputStream -> ConnectionDataReceiver -> Connection -> PacketQueue -> I2PSession
 */
class PacketQueue {
    private final I2PAppContext _context;
    private final Log _log;
    private final I2PSession _session;
    private final ConnectionManager _connectionManager;
    private final ByteCache _cache = ByteCache.getInstance(64, 36*1024);
    
    private static final int FLAGS_INITIAL_TAGS = Packet.FLAG_SYNCHRONIZE;
    private static final int FLAGS_FINAL_TAGS = Packet.FLAG_CLOSE |
                                              Packet.FLAG_RESET |
                                              Packet.FLAG_ECHO;
    private static final int INITIAL_TAGS_TO_SEND = 32;
    private static final int MIN_TAG_THRESHOLD = 20;
    private static final int TAG_WINDOW_FACTOR = 5;
    private static final int FINAL_TAGS_TO_SEND = 4;
    private static final int FINAL_TAG_THRESHOLD = 2;

    public PacketQueue(I2PAppContext context, I2PSession session, ConnectionManager mgr) {
        _context = context;
        _session = session;
        _connectionManager = mgr;
        _log = context.logManager().getLog(PacketQueue.class);
        // all createRateStats in ConnectionManager
    }
    
    /**
     * Add a new packet to be sent out ASAP
     *
     * keys and tags disabled since dropped in I2PSession
     * @return true if sent
     */
    public boolean enqueue(PacketLocal packet) {
        // this updates the ack/nack field
        packet.prepare();
        
        //SessionKey keyUsed = packet.getKeyUsed();
        //if (keyUsed == null)
        //    keyUsed = new SessionKey();
        //Set tagsSent = packet.getTagsSent();
        //if (tagsSent == null)
        //    tagsSent = new HashSet(0);

        // cache this from before sendMessage
        String conStr = null;
        if (_log.shouldLog(Log.DEBUG))
            conStr = (packet.getConnection() != null ? packet.getConnection().toString() : "");
        if (packet.getAckTime() > 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Not resending " + packet);
            return false;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending... " + packet);
        }
    
        ByteArray ba = _cache.acquire();
        byte buf[] = ba.getData();

        long begin = 0;
        long end = 0;
        boolean sent = false;
        try {
            int size = 0;
            long beforeWrite = System.currentTimeMillis();
            if (packet.shouldSign())
                size = packet.writeSignedPacket(buf, 0, _context, _session.getPrivateKey());
            else
                size = packet.writePacket(buf, 0);
            long writeTime = System.currentTimeMillis() - beforeWrite;
            if ( (writeTime > 1000) && (_log.shouldLog(Log.WARN)) )
                _log.warn("took " + writeTime + "ms to write the packet: " + packet);

            // last chance to short circuit...
            if (packet.getAckTime() > 0) return false;
            
            // this should not block!
            begin = _context.clock().now();
            long expires = 0;
            Connection.ResendPacketEvent rpe = (Connection.ResendPacketEvent) packet.getResendEvent();
            if (rpe != null)
                // we want the router to expire it a little before we do,
                // so if we retransmit it will use a new tunnel/lease combo
                expires = rpe.getNextSendTime() - 500;
            SendMessageOptions options = new SendMessageOptions();
            if (expires > 0)
                options.setDate(expires);
            if (packet.isFlagSet(FLAGS_INITIAL_TAGS)) {
                Connection con = packet.getConnection();
                if (con != null && con.isInbound())
                    options.setSendLeaseSet(false);
                options.setTagsToSend(INITIAL_TAGS_TO_SEND);
                options.setTagThreshold(MIN_TAG_THRESHOLD);
            } else if (packet.isFlagSet(FLAGS_FINAL_TAGS)) {
                options.setSendLeaseSet(false);
                options.setTagsToSend(FINAL_TAGS_TO_SEND);
                options.setTagThreshold(FINAL_TAG_THRESHOLD);
            } else {
                Connection con = packet.getConnection();
                if (con != null) {
                    if (con.isInbound() && con.getLifetime() < 2*60*1000)
                        options.setSendLeaseSet(false);
                    // increase threshold with higher window sizes to prevent stalls
                    // after tag delivery failure
                    int wdw = con.getOptions().getWindowSize();
                    int thresh = Math.max(MIN_TAG_THRESHOLD, wdw * TAG_WINDOW_FACTOR);
                    options.setTagThreshold(thresh);
                }
            }
            sent = _session.sendMessage(packet.getTo(), buf, 0, size,
                                 I2PSession.PROTO_STREAMING, packet.getLocalPort(), packet.getRemotePort(),
                                 options);
            end = _context.clock().now();
            
            if ( (end-begin > 1000) && (_log.shouldLog(Log.WARN)) ) 
                _log.warn("Took " + (end-begin) + "ms to sendMessage(...) " + packet);
            
            _context.statManager().addRateData("stream.con.sendMessageSize", size, packet.getLifetime());
            if (packet.getNumSends() > 1)
                _context.statManager().addRateData("stream.con.sendDuplicateSize", size, packet.getLifetime());
            
            Connection con = packet.getConnection();
            if (con != null) {
                con.incrementBytesSent(size);
                if (packet.getNumSends() > 1)
                    con.incrementDupMessagesSent(1);
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to send the packet " + packet, ise);
        }
        
        _cache.release(ba);
        
        if (!sent) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Send failed for " + packet);
            Connection c = packet.getConnection();
            if (c != null) // handle race on b0rk
                c.disconnect(false);
        } else {
            //packet.setKeyUsed(keyUsed);
            //packet.setTagsSent(tagsSent);
            packet.incrementSends();
            if (_log.shouldLog(Log.DEBUG)) {
                String msg = "SEND " + packet
                             + " send # " + packet.getNumSends()
                             + " sendTime: " + (end-begin)
                             + " con: " + conStr;
                _log.debug(msg);
            }
            Connection c = packet.getConnection();
            String suffix = (c != null ? "wsize " + c.getOptions().getWindowSize() + " rto " + c.getOptions().getRTO() : null);
            _connectionManager.getPacketHandler().displayPacket(packet, "SEND", suffix);
        }
        
        if ( (packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
            // ack only, so release it asap
            packet.releasePayload();
        } else if (packet.isFlagSet(Packet.FLAG_ECHO) && !packet.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED) ) {
            // pong
            packet.releasePayload();
        } else if (packet.isFlagSet(Packet.FLAG_RESET)) {
            // reset
            packet.releasePayload();
        }
        return sent;
    }
    
}
