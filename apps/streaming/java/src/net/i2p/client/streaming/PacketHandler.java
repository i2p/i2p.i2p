package net.i2p.client.streaming;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.text.SimpleDateFormat;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * receive a packet and dispatch it correctly to the connection specified,
 * the server socket, or queue a reply RST packet.
 *
 */
public class PacketHandler {
    private ConnectionManager _manager;
    private I2PAppContext _context;
    private Log _log;
    
    public PacketHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
    }
    
    private boolean choke(Packet packet) {
        if (false) {
            // artificial choke: 2% random drop and a 1s
            // random delay
            if (_context.random().nextInt(100) >= 98) {
                _log.error("DROP: " + packet);
                return false;
            } else {
                int delay = _context.random().nextInt(1000);
                try { Thread.sleep(delay); } catch (InterruptedException ie) {}
                _log.debug("OK  : " + packet + " delay = " + delay);
                return true;
            }
        } else {
            return true;
        }
    }
    
    void receivePacket(Packet packet) {
        boolean ok = choke(packet);
        if (!ok) return;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("packet received: " + packet);
        
        byte sendId[] = packet.getSendStreamId();
        if (!isNonZero(sendId))
            sendId = null;
        
        Connection con = (sendId != null ? _manager.getConnectionByInboundId(sendId) : null); 
        if (con != null) {
            receiveKnownCon(con, packet);
            displayPacket(packet, con);
        } else {
            receiveUnknownCon(packet, sendId);
            displayPacket(packet, null);
        }
    }
    
    private void displayPacket(Packet packet, Connection con) {
        if (_log.shouldLog(Log.DEBUG)) {
            SimpleDateFormat fmt = new SimpleDateFormat("hh:mm:ss.SSS");
            String now = fmt.format(new Date());
            String msg = packet + (con != null ? " on " + con : " on unknown con");
            //_log.debug(msg);
            System.out.println(now + ": " + msg);
        }
    }
    
    private void receiveKnownCon(Connection con, Packet packet) {
        // the packet is pointed at a stream ID we're receiving on
        if (isValidMatch(con.getSendStreamId(), packet.getReceiveStreamId())) {
            // the packet's receive stream ID also matches what we expect
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("receive valid: " + packet);
            con.getPacketHandler().receivePacket(packet, con);
        } else {
            if (packet.isFlagSet(Packet.FLAG_RESET)) {
                // refused
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("receive reset: " + packet);
                con.getPacketHandler().receivePacket(packet, con);
            } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                if ( (con.getSendStreamId() == null) || 
                     (DataHelper.eq(con.getSendStreamId(), packet.getReceiveStreamId())) ) {
                    // con fully established, w00t
                    con.setSendStreamId(packet.getReceiveStreamId());
                    con.getPacketHandler().receivePacket(packet, con);
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Receive a syn packet with the wrong IDs: " + packet);
                }
            } else {
                // someone is sending us a packet on the wrong stream 
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received a packet on the wrong stream: " + packet);
            }
        }
    }
    
    private void receiveUnknownCon(Packet packet, byte sendId[]) {
        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            if (packet.getSendStreamId() != null) {
                receivePing(packet);
            } else if (packet.getReceiveStreamId() != null) {
                receivePong(packet);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Echo packet received with no stream IDs: " + packet);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Packet received on an unknown stream (and not a SYN): " + packet);
            if (sendId == null) {
                for (Iterator iter = _manager.listConnections().iterator(); iter.hasNext(); ) {
                    Connection con = (Connection)iter.next();
                    if (DataHelper.eq(con.getSendStreamId(), packet.getReceiveStreamId())) {
                        if (con.getAckedPackets() <= 0) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Received additional packets before the syn on " + con + ": " + packet);
                            receiveKnownCon(con, packet);
                            return;
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("hrmph, received while ack of syn was in flight on " + con + ": " + packet + " acked: " + con.getAckedPackets());
                            receiveKnownCon(con, packet);
                            return;
                        }
                    }
                }
            }
            
            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else {
                if (_log.shouldLog(Log.WARN)) {
                    StringBuffer buf = new StringBuffer(128);
                    Set cons = _manager.listConnections();
                    for (Iterator iter = cons.iterator(); iter.hasNext(); ) {
                        Connection con = (Connection)iter.next();
                        buf.append(Base64.encode(con.getReceiveStreamId())).append(" ");
                    }
                    _log.warn("Packet belongs to no other cons: " + packet + " connections: " 
                              + buf.toString() + " sendId: " 
                              + (sendId != null ? Base64.encode(sendId) : " unknown"));
                }
            }
        }
    }
    
    private void receivePing(Packet packet) {
        boolean ok = packet.verifySignature(_context, packet.getOptionalFrom(), null);
        if (!ok) {
            if (_log.shouldLog(Log.WARN)) {
                if (packet.getOptionalFrom() == null)
                    _log.warn("Ping with no from (flagged? " + packet.isFlagSet(Packet.FLAG_FROM_INCLUDED) + ")");
                else if (packet.getOptionalSignature() == null)
                    _log.warn("Ping with no signature (flagged? " + packet.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED) + ")");
                else
                    _log.warn("Forged ping, discard (from=" + packet.getOptionalFrom().calculateHash().toBase64() 
                              + " sig=" + packet.getOptionalSignature().toBase64() + ")");
            }
        } else {
            PacketLocal pong = new PacketLocal(_context, packet.getOptionalFrom());
            pong.setFlag(Packet.FLAG_ECHO, true);
            pong.setFlag(Packet.FLAG_SIGNATURE_INCLUDED, false);
            pong.setReceiveStreamId(packet.getSendStreamId());
            _manager.getPacketQueue().enqueue(pong);
        }
    }
    
    private void receivePong(Packet packet) {
        _manager.receivePong(packet.getReceiveStreamId());
    }
    
    private static final boolean isValidMatch(byte conStreamId[], byte packetStreamId[]) {
        if ( (conStreamId == null) || (packetStreamId == null) || 
             (conStreamId.length != packetStreamId.length) ) 
            return false;
        
        boolean nonZeroFound = false;
        for (int i = 0; i < conStreamId.length; i++) {
            if (conStreamId[i] != packetStreamId[i]) return false;
            if (conStreamId[i] != 0x0) nonZeroFound = true;
        }
        return nonZeroFound;
    }
    
    private static final boolean isNonZero(byte[] b) {
        boolean nonZeroFound = false;
        for (int i = 0; b != null && i < b.length; i++) {
            if (b[i] != 0x0)
                nonZeroFound = true;
        }
        return nonZeroFound;
    }
}
