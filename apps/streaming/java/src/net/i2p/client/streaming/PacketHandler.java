package net.i2p.client.streaming;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.text.SimpleDateFormat;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * receive a packet and dispatch it correctly to the connection specified,
 * the server socket, or queue a reply RST packet.
 *
 */
public class PacketHandler {
    private ConnectionManager _manager;
    private I2PAppContext _context;
    private Log _log;
    private int _lastDelay;
    
    public PacketHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _lastDelay = _context.random().nextInt(30*1000);
    }
    
    private boolean choke(Packet packet) {
        if (false) {
            // artificial choke: 2% random drop and a 0-30s
            // random tiered delay from 0-30s
            if (_context.random().nextInt(100) >= 95) {
                displayPacket(packet, "DROP");
                return false;
            } else {
                // if (true) return true; // no lag, just drop
                /*
                int delay = _context.random().nextInt(5*1000);
                */
                int delay = _context.random().nextInt(6*1000);
                int delayFactor = _context.random().nextInt(100);
                if (delayFactor > 80) {
                    if (delayFactor > 98)
                        delay *= 5;
                    else if (delayFactor > 95)
                        delay *= 4;
                    else if (delayFactor > 90)
                        delay *= 3;
                    else
                        delay *= 2;
                }
                 
                if (_context.random().nextInt(100) >= 20)
                    delay = _lastDelay;
                
                _lastDelay = delay;
                SimpleTimer.getInstance().addEvent(new Reinject(packet, delay), delay);
                return false;
            }
        } else {
            return true;
        }
    }
    
    private class Reinject implements SimpleTimer.TimedEvent {
        private Packet _packet;
        private int _delay;
        public Reinject(Packet packet, int delay) {
            _packet = packet;
            _delay = delay;
        }
        public void timeReached() {
            _log.debug("Reinjecting after " + _delay + ": " + _packet);
            receivePacketDirect(_packet);
        }
    }
    
    void receivePacket(Packet packet) {
        boolean ok = choke(packet);
        if (ok)
            receivePacketDirect(packet);
    }
    
    private void receivePacketDirect(Packet packet) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("packet received: " + packet);
        
        byte sendId[] = packet.getSendStreamId();
        if (!isNonZero(sendId))
            sendId = null;
        
        Connection con = (sendId != null ? _manager.getConnectionByInboundId(sendId) : null); 
        if (con != null) {
            receiveKnownCon(con, packet);
            displayPacket(packet, "RECV");
        } else {
            receiveUnknownCon(packet, sendId);
            displayPacket(packet, "UNKN");
        }
    }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("hh:mm:ss.SSS");
    static void displayPacket(Packet packet, String prefix) {
        String msg = null;
        synchronized (_fmt) {
            msg = _fmt.format(new Date()) + ": " + prefix + " " + packet.toString();
        }
        System.out.println(msg);
    }
    
    private void receiveKnownCon(Connection con, Packet packet) {
        // the packet is pointed at a stream ID we're receiving on
        if (isValidMatch(con.getSendStreamId(), packet.getReceiveStreamId())) {
            // the packet's receive stream ID also matches what we expect
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("receive valid: " + packet);
            try {
                con.getPacketHandler().receivePacket(packet, con);
            } catch (I2PException ie) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received forged packet for " + con, ie);
            }
        } else {
            if (packet.isFlagSet(Packet.FLAG_RESET)) {
                // refused
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("receive reset: " + packet);
                try {
                    con.getPacketHandler().receivePacket(packet, con);
                } catch (I2PException ie) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Received forged reset for " + con, ie);
                }
            } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                if ( (con.getSendStreamId() == null) || 
                     (DataHelper.eq(con.getSendStreamId(), packet.getReceiveStreamId())) ) {
                    byte oldId[] =con.getSendStreamId();
                    // con fully established, w00t
                    con.setSendStreamId(packet.getReceiveStreamId());
                    try {
                        con.getPacketHandler().receivePacket(packet, con);
                    } catch (I2PException ie) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Received forged syn for " + con, ie);
                        con.setSendStreamId(oldId);
                    }
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
                _log.debug("Packet received on an unknown stream (and not an ECHO): " + packet);
            if (sendId == null) {
                Connection con = _manager.getConnectionByOutboundId(packet.getReceiveStreamId());
                if (con != null) {
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
            
            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else {
                if (_log.shouldLog(Log.WARN)) {
                    StringBuffer buf = new StringBuffer(128);
                    Set cons = _manager.listConnections();
                    for (Iterator iter = cons.iterator(); iter.hasNext(); ) {
                        Connection con = (Connection)iter.next();
                        buf.append(con.toString()).append(" ");
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
