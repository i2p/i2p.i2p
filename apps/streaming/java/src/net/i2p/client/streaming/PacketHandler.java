package net.i2p.client.streaming;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;

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
    
    void receivePacket(Packet packet) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("packet received: " + packet);
        
        byte sendId[] = packet.getSendStreamId();
        if (!isNonZero(sendId))
            sendId = null;
        
        Connection con = (sendId != null ? _manager.getConnectionByInboundId(sendId) : null); 
        if (con != null) {
            receiveKnownCon(con, packet);
            System.out.println(new Date() + ": Receive packet " + packet + " on con " + con);
        } else {
            receiveUnknownCon(packet, sendId);
            System.out.println(new Date() + ": Receive packet " + packet + " on an unknown con");
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
        } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
            if (sendId == null) {
                // this is the initial SYN to establish a connection
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Syn packet reply on a stream we don't know about: " + packet);
            }
        } else {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Packet received on an unknown stream (and not a SYN): " + packet);
                StringBuffer buf = new StringBuffer(128);
                Set cons = _manager.listConnections();
                for (Iterator iter = cons.iterator(); iter.hasNext(); ) {
                    Connection con = (Connection)iter.next();
                    buf.append(Base64.encode(con.getReceiveStreamId())).append(" ");
                }
                _log.warn("Other streams: " + buf.toString());
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
