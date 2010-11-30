package net.i2p.client.streaming;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * receive a packet and dispatch it correctly to the connection specified,
 * the server socket, or queue a reply RST packet.
 *
 */
class PacketHandler {
    private ConnectionManager _manager;
    private I2PAppContext _context;
    private Log _log;
    //private int _lastDelay;
    //private int _dropped;
    
    public PacketHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        //_dropped = 0;
        _log = ctx.logManager().getLog(PacketHandler.class);
        //_lastDelay = _context.random().nextInt(30*1000);
    }
    
/** what is the point of this ? */
/*****
    private boolean choke(Packet packet) { 
        if (true) return true;
        //if ( (_dropped == 0) && true ) { //&& (_manager.getSent() <= 0) ) {
        //    _dropped++;
        //    return false;
        //}
        if (true) {
            // artificial choke: 2% random drop and a 0-5s
            // random tiered delay from 0-30s
            if (_context.random().nextInt(100) >= 98) {
                displayPacket(packet, "DROP", null);
                return false;
            } else {
                // if (true) return true; // no lag, just drop
                // int delay = _context.random().nextInt(5*1000);
                int delay = _context.random().nextInt(1*1000);
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
*****/
    
    void receivePacket(Packet packet) {
        //boolean ok = choke(packet);
        //if (ok)
            receivePacketDirect(packet, true);
    }
    
    void receivePacketDirect(Packet packet, boolean queueIfNoConn) {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("packet received: " + packet);
        
        long sendId = packet.getSendStreamId();
        
        Connection con = (sendId > 0 ? _manager.getConnectionByInboundId(sendId) : null); 
        if (con != null) {
            receiveKnownCon(con, packet);
            if (_log.shouldLog(Log.INFO))
                displayPacket(packet, "RECV", "wsize " + con.getOptions().getWindowSize() + " rto " + con.getOptions().getRTO());
        } else {
            receiveUnknownCon(packet, sendId, queueIfNoConn);
            displayPacket(packet, "UNKN", null);
        }
    }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("HH:mm:ss.SSS");
    void displayPacket(Packet packet, String prefix, String suffix) {
        if (!_log.shouldLog(Log.INFO)) return;
        StringBuilder buf = new StringBuilder(256);
        synchronized (_fmt) {
            buf.append(_fmt.format(new Date()));
        }
        buf.append(": ").append(prefix).append(" ");
        buf.append(packet.toString());
        if (suffix != null)
            buf.append(" ").append(suffix);
        String str = buf.toString();
        System.out.println(str);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(str);
    }
    
    private void receiveKnownCon(Connection con, Packet packet) {
        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            if (packet.getSendStreamId() > 0) {
                if (con.getOptions().getAnswerPings())
                    receivePing(packet);
                else if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping Echo packet on existing con: " + packet);
            } else if (packet.getReceiveStreamId() > 0) {
                receivePong(packet);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Echo packet received with no stream IDs: " + packet);
            }
            packet.releasePayload();
            return;
        } 
        
        // the packet is pointed at a stream ID we're receiving on
        if (isValidMatch(con.getSendStreamId(), packet.getReceiveStreamId())) {
            // the packet's receive stream ID also matches what we expect
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("receive valid: " + packet);
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
            } else {
                if ( (con.getSendStreamId() <= 0) || 
                     (con.getSendStreamId() == packet.getReceiveStreamId()) ||
                     (packet.getSequenceNum() <= ConnectionOptions.MIN_WINDOW_SIZE) ) { // its in flight from the first batch
                    long oldId = con.getSendStreamId();
                    if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                        if (oldId <= 0) {
                            // con fully established, w00t
                            con.setSendStreamId(packet.getReceiveStreamId());
                        } else if (oldId == packet.getReceiveStreamId()) {
                            // ok, as expected...
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Received a syn with the wrong IDs, con=" + con + " packet=" + packet);
                            sendReset(packet);
                            packet.releasePayload();
                            return;
                        }
                    }
                    
                    try {
                        con.getPacketHandler().receivePacket(packet, con);
                    } catch (I2PException ie) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Received forged packet for " + con + "/" + oldId + ": " + packet, ie);
                        con.setSendStreamId(oldId);
                    }
                } else if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Receive a syn packet with the wrong IDs, sending reset: " + packet);
                    sendReset(packet);
                    packet.releasePayload();
                } else {
                    if (!con.getResetSent()) {
                        // someone is sending us a packet on the wrong stream 
                        // It isn't a SYN so it isn't likely to have a FROM to send a reset back to
                        if (_log.shouldLog(Log.ERROR)) {
                            Set cons = _manager.listConnections();
                            StringBuilder buf = new StringBuilder(512);
                            buf.append("Received a packet on the wrong stream: ");
                            buf.append(packet);
                            buf.append("\nthis connection:\n");
                            buf.append(con);
                            buf.append("\nall connections:");
                            for (Iterator iter = cons.iterator(); iter.hasNext();) {
                                Connection cur = (Connection)iter.next();
                                buf.append('\n').append(cur);
                            }
                            _log.error(buf.toString(), new Exception("Wrong stream"));
                        }
                    }
                    packet.releasePayload();
                }
            }
        }
    }
    
    /**
     *  This sends a reset back to the place this packet came from.
     *  If the packet has no 'optional from' or valid signature, this does nothing.
     *  This is not associated with a connection, so no con stats are updated.
     */
    private void sendReset(Packet packet) {
        Destination from = packet.getOptionalFrom();
        if (from == null)
            return;
        boolean ok = packet.verifySignature(_context, from, null);
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Can't send reset after recv spoofed packet: " + packet);
            return;
        }
        PacketLocal reply = new PacketLocal(_context, from);
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(packet.getReceiveStreamId());
        reply.setReceiveStreamId(packet.getSendStreamId());
        reply.setOptionalFrom(_manager.getSession().getMyDestination());
        // this just sends the packet - no retries or whatnot
        _manager.getPacketQueue().enqueue(reply);
    }
    
    private void receiveUnknownCon(Packet packet, long sendId, boolean queueIfNoConn) {
        if (packet.isFlagSet(Packet.FLAG_ECHO)) {
            if (packet.getSendStreamId() > 0) {
                if (_manager.answerPings())
                    receivePing(packet);
                else if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping Echo packet on unknown con: " + packet);
            } else if (packet.getReceiveStreamId() > 0) {
                receivePong(packet);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Echo packet received with no stream IDs: " + packet);
            }
            packet.releasePayload();
        } else {
            if (_log.shouldLog(Log.WARN) && !packet.isFlagSet(Packet.FLAG_SYNCHRONIZE))
                _log.warn("Packet received on an unknown stream (and not an ECHO or SYN): " + packet);
            if (sendId <= 0) {
                Connection con = _manager.getConnectionByOutboundId(packet.getReceiveStreamId());
                if (con != null) {
                    if ( (con.getHighestAckedThrough() <= 5) && (packet.getSequenceNum() <= 5) ) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Received additional packet w/o SendStreamID after the syn on " + con + ": " + packet);
                        receiveKnownCon(con, packet);
                        return;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("hrmph, received while ack of syn was in flight on " + con + ": " + packet + " acked: " + con.getAckedPackets());
                        // allow unlimited packets without a SendStreamID for now
                        receiveKnownCon(con, packet);
                        return;
                    }
                }
            }
            
            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else if (queueIfNoConn) {
                // We can get here on the 2nd+ packet if the 1st (SYN) packet
                // is still on the _synQueue in the ConnectionHandler, and
                // ConnectionManager.receiveConnection() hasn't run yet to put
                // the StreamID on the getConnectionByOutboundId list.
                // Then the 2nd packet gets discarded and has to be retransmitted.
                //
                // We fix this by putting this packet on the syn queue too!
                // Then ConnectionHandler.accept() will check the connection list
                // and call receivePacket() above instead of receiveConnection().
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Packet belongs to no other cons, putting on the syn queue: " + packet);
                }
                if (_log.shouldLog(Log.DEBUG)) {
                    StringBuilder buf = new StringBuilder(128);
                    Set cons = _manager.listConnections();
                    for (Iterator iter = cons.iterator(); iter.hasNext(); ) {
                        Connection con = (Connection)iter.next();
                        buf.append(con.toString()).append(" ");
                    }
                    _log.debug("connections: " + buf.toString() + " sendId: " 
                               + (sendId > 0 ? Packet.toId(sendId) : " unknown"));
                }
                //packet.releasePayload();
                _manager.getConnectionHandler().receiveNewSyn(packet);
            } else {
                // don't queue again (infinite loop!)
                sendReset(packet);
                packet.releasePayload();
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
    
    private static final boolean isValidMatch(long conStreamId, long packetStreamId) {
        return ( (conStreamId == packetStreamId) && (conStreamId != 0) );
    }
}
