package net.i2p.client.streaming;

import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Receive a packet for a particular connection - placing the data onto the
 * queue, marking packets as acked, updating various fields, etc.
 *
 */
public class ConnectionPacketHandler {
    private I2PAppContext _context;
    private Log _log;
    
    public ConnectionPacketHandler(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(ConnectionPacketHandler.class);
        _context.statManager().createRateStat("stream.con.receiveMessageSize", "Size of a message received on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.receiveDuplicateSize", "Size of a duplicate message received on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.packetsAckedPerMessageReceived", "Size of a duplicate message received on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.sendsBeforeAck", "How many times a message was sent before it was ACKed?", "Stream", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.resetReceived", "How many messages had we sent successfully before receiving a RESET?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
    }
    
    /** distribute a packet to the connection specified */
    void receivePacket(Packet packet, Connection con) throws I2PException {
        boolean ok = verifyPacket(packet, con);
        if (!ok) {
            if ( (!packet.isFlagSet(Packet.FLAG_RESET)) && (_log.shouldLog(Log.ERROR)) )
                _log.error("Packet does NOT verify: " + packet);
            return;
        }

        if (con.getHardDisconnected()) {
            if ( (packet.getSequenceNum() > 0) || (packet.getPayloadSize() > 0) || 
                 (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) || (packet.isFlagSet(Packet.FLAG_CLOSE)) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received a data packet after hard disconnect: " + packet + " on " + con);
                con.sendReset();
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received a packet after hard disconnect, ignoring: " + packet + " on " + con);
            }
            return;
        }

        if (packet.isFlagSet(Packet.FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            if (packet.getOptionalMaxSize() < con.getOptions().getMaxMessageSize()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Reducing our max message size to " + packet.getOptionalMaxSize() 
                              + " from " + con.getOptions().getMaxMessageSize());
                con.getOptions().setMaxMessageSize(packet.getOptionalMaxSize());
                con.getOutputStream().setBufferSize(packet.getOptionalMaxSize());
            }
        }

        con.packetReceived();
        
        long ready = con.getInputStream().getHighestReadyBockId();
        int available = con.getOptions().getInboundBufferSize() - con.getInputStream().getTotalReadySize();
        int allowedBlocks = available/con.getOptions().getMaxMessageSize();
        if ( (packet.getPayloadSize() > 0) && (packet.getSequenceNum() > ready + allowedBlocks) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Inbound buffer exceeded on connection " + con + " (" 
                          + ready + "/"+ (ready+allowedBlocks) + "/" + available
                          + ": dropping " + packet);
            ack(con, packet.getAckThrough(), packet.getNacks(), null, false);
            con.getOptions().setChoke(5*1000);
            return;
        }
        con.getOptions().setChoke(0);

        _context.statManager().addRateData("stream.con.receiveMessageSize", packet.getPayloadSize(), 0);
        
        boolean isNew = con.getInputStream().messageReceived(packet.getSequenceNum(), packet.getPayload());
        
        if ( (packet.getSequenceNum() == 0) && (packet.getPayloadSize() > 0) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("seq=0 && size=" + packet.getPayloadSize() + ": isNew? " + isNew 
                           + " packet: " + packet + " con: " + con);
        }

        // close *after* receiving the data, as well as after verifying the signatures / etc
        if (packet.isFlagSet(Packet.FLAG_CLOSE) && packet.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED))
            con.closeReceived();
        
        boolean fastAck = false;
        
        if (isNew) {
            con.incrementUnackedPacketsReceived();
            con.incrementBytesReceived(packet.getPayloadSize());
            
            if (packet.isFlagSet(Packet.FLAG_DELAY_REQUESTED) && (packet.getOptionalDelay() <= 0) ) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Scheduling immediate ack for " + packet);
                con.setNextSendTime(_context.clock().now() + con.getOptions().getSendAckDelay());
            } else {
                int delay = con.getOptions().getSendAckDelay();
                if (packet.isFlagSet(Packet.FLAG_DELAY_REQUESTED)) // delayed ACK requested
                    delay += packet.getOptionalDelay();
                con.setNextSendTime(delay + _context.clock().now());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Scheduling ack in " + delay + "ms for received packet " + packet);
            }
        } else {
            if ( (packet.getSequenceNum() > 0) || (packet.getPayloadSize() > 0) || 
                 (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
                _context.statManager().addRateData("stream.con.receiveDuplicateSize", packet.getPayloadSize(), 0);
                con.incrementDupMessagesReceived(1);
        
                // take note of congestion
                if (_log.shouldLog(Log.WARN))
                    _log.warn("congestion.. dup " + packet);
                SimpleTimer.getInstance().addEvent(new AckDup(con), con.getOptions().getSendAckDelay());
                //con.setNextSendTime(_context.clock().now() + con.getOptions().getSendAckDelay());
                //fastAck = true;
            } else {
                if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    //con.incrementUnackedPacketsReceived();
                    con.setNextSendTime(_context.clock().now() + con.getOptions().getSendAckDelay());
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("ACK only packet received: " + packet);
                }
            }
        }
        
        fastAck = fastAck || ack(con, packet.getAckThrough(), packet.getNacks(), packet, isNew);
        con.eventOccurred();
        if (fastAck) {
            if (con.getLastSendTime() + 2000 < _context.clock().now()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Fast ack for dup " + packet);
                con.ackImmediately();
            }
        }
    }
    
    private boolean ack(Connection con, long ackThrough, long nacks[], Packet packet, boolean isNew) {
        int numResends = 0;
        List acked = con.ackPackets(ackThrough, nacks);
        if ( (acked != null) && (acked.size() > 0) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(acked.size() + " of our packets acked with " + packet);
            // use the highest RTT, since these would likely be bunched together,
            // and the highest rtt lets us set our resend delay properly
            int highestRTT = -1;
            for (int i = 0; i < acked.size(); i++) {
                PacketLocal p = (PacketLocal)acked.get(i);
                if (p.getAckTime() > highestRTT) {
                    //if (p.getNumSends() <= 1)
                    highestRTT = p.getAckTime();
                }
                _context.statManager().addRateData("stream.sendsBeforeAck", p.getNumSends(), p.getAckTime());
                
                if (p.getNumSends() > 1)
                    numResends++;
                
                // ACK the tags we delivered so we can use them
                if ( (p.getKeyUsed() != null) && (p.getTagsSent() != null) 
                      && (p.getTagsSent().size() > 0) ) {
                    _context.sessionKeyManager().tagsDelivered(p.getTo().getPublicKey(), 
                                                               p.getKeyUsed(), 
                                                               p.getTagsSent());
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet acked after " + p.getAckTime() + "ms: " + p);
            }
            if (highestRTT > 0) {
                con.getOptions().updateRTT(highestRTT);
            }
            _context.statManager().addRateData("stream.con.packetsAckedPerMessageReceived", acked.size(), highestRTT);
        }

        if (packet != null)
            return adjustWindow(con, isNew, packet.getSequenceNum(), numResends, (acked != null ? acked.size() : 0));
        else
            return adjustWindow(con, false, -1, numResends, (acked != null ? acked.size() : 0));
    }
    
    
    private boolean adjustWindow(Connection con, boolean isNew, long sequenceNum, int numResends, int acked) {
        boolean congested = false;
        if ( (!isNew) && (sequenceNum > 0) ) {
            // dup real packet
            int oldSize = con.getOptions().getWindowSize();
            con.congestionOccurred();
            oldSize >>>= 1;
            if (oldSize <= 0)
                oldSize = 1;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Congestion occurred - new windowSize " + oldSize + " congestionSeenAt: "
                           + con.getLastCongestionSeenAt() + " (#resends: " + numResends 
                           + ") for " + con);

            con.getOptions().setWindowSize(oldSize);
            
            congested = true;
        } 
        
        long lowest = con.getHighestAckedThrough();
        if (lowest >= con.getCongestionWindowEnd()) {
            // new packet that ack'ed uncongested data, or an empty ack
            int oldWindow = con.getOptions().getWindowSize();
            int newWindowSize = oldWindow;

            if ( (!congested) && (acked > 0) && (numResends <= 0) ) {
                if (newWindowSize > con.getLastCongestionSeenAt() / 2) {
                    // congestion avoidance

                    // we can't use newWindowSize += 1/newWindowSize, since we're
                    // integers, so lets use a random distribution instead
                    int shouldIncrement = _context.random().nextInt(newWindowSize);
                    if (shouldIncrement <= 0)
                        newWindowSize += 1;
                } else {
                    // slow start
                    newWindowSize += 1;
                }
            }
            
            if (newWindowSize <= 0)
                newWindowSize = 1;
                    
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("New window size " + newWindowSize + "/" + oldWindow + " congestionSeenAt: "
                           + con.getLastCongestionSeenAt() + " (#resends: " + numResends 
                           + ") for " + con);
            con.getOptions().setWindowSize(newWindowSize);
            con.setCongestionWindowEnd(newWindowSize + lowest);
        }
        
        con.windowAdjusted();
        return congested;
    }
    
    /**
     * Make sure this packet is ok and that we can continue processing its data.
     * 
     * @return true if the packet is ok for this connection, false if we shouldn't
     *         continue processing.
     */
    private boolean verifyPacket(Packet packet, Connection con) throws I2PException {
        if (packet.isFlagSet(Packet.FLAG_RESET)) {
            verifyReset(packet, con);
            return false;
        } else {
            verifySignature(packet, con);
            
            if (con.getSendStreamId() == null) {
                if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    con.setSendStreamId(packet.getReceiveStreamId());
                    con.setRemotePeer(packet.getOptionalFrom());
                    return true;
                } else {
                    // neither RST nor SYN and we dont have the stream id yet?
                    if (packet.getSequenceNum() <= 2) {
                        return true;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Packet without RST or SYN where we dont know stream ID: " 
                                      + packet);
                        return false;
                    }
                }
            } else {
                if (!DataHelper.eq(con.getSendStreamId(), packet.getReceiveStreamId())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Packet received with the wrong reply stream id: " 
                                  + con + " / " + packet);
                    return false;
                } else {
                    return true;
                }
            }
        }
    }

    /**
     * Make sure this RST packet is valid, and if it is, act on it.
     */
    private void verifyReset(Packet packet, Connection con) {
        if (DataHelper.eq(con.getReceiveStreamId(), packet.getSendStreamId())) {
            boolean ok = packet.verifySignature(_context, packet.getOptionalFrom(), null);
            if (!ok) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received unsigned / forged RST on " + con);
                return;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reset received");
                // ok, valid RST
                con.resetReceived();
                con.eventOccurred();

                _context.statManager().addRateData("stream.resetReceived", con.getHighestAckedThrough(), con.getLifetime());
                
                // no further processing
                return;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received a packet for the wrong connection?  wtf: " 
                          + con + " / " + packet);
            return;
        }
    }
    
    /**
     * Verify the signature if necessary.  
     *
     * @throws I2PException if the signature was necessary and it was invalid
     */
    private void verifySignature(Packet packet, Connection con) throws I2PException {
        // verify the signature if necessary
        if (con.getOptions().getRequireFullySigned() || 
            packet.isFlagSet(Packet.FLAG_SYNCHRONIZE) ||
            packet.isFlagSet(Packet.FLAG_CLOSE) ) {
            // we need a valid signature
            Destination from = con.getRemotePeer();
            if (from == null)
                from = packet.getOptionalFrom();
            boolean sigOk = packet.verifySignature(_context, from, null);
            if (!sigOk) {
                throw new I2PException("Received unsigned / forged packet: " + packet);
            }
        }
    }    
    
    private class AckDup implements SimpleTimer.TimedEvent {
        private long _created;
        private Connection _con;
        public AckDup(Connection con) {
            _created = _context.clock().now();
            _con = con;
        }
        public void timeReached() {
            if (_con.getLastSendTime() <= _created) {
                if (!_con.getIsConnected()) return;
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Last sent was a while ago, and we want to ack a dup");
                // we haven't done anything since receiving the dup, send an
                // ack now
                _con.ackImmediately();
            }
        }
    }
}
