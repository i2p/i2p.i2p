package net.i2p.client.streaming.impl;

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
 *<p>
 * I2PSession -&gt; MessageHandler -&gt; PacketHandler -&gt; ConnectionPacketHandler -&gt; MessageInputStream
 *<p>
 * One of these is instantiated per-Destination
 * (i.e. per-ConnectionManager, not per-Connection).
 * It doesn't store any state.

 */
class ConnectionPacketHandler {
    private final I2PAppContext _context;
    private final Log _log;

    public static final int MAX_SLOW_START_WINDOW = 24;
    
    public ConnectionPacketHandler(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(ConnectionPacketHandler.class);
        _context.statManager().createRateStat("stream.con.receiveMessageSize", "Size of a message received on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.receiveDuplicateSize", "Size of a duplicate message received on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.packetsAckedPerMessageReceived", "Avg number of acks in a message", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.sendsBeforeAck", "How many times a message was sent before it was ACKed?", "Stream", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.resetReceived", "How many messages had we sent successfully before receiving a RESET?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.trend", "What direction the RTT is trending in (with period = windowsize)", "Stream", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.initialRTT.in", "What is the actual RTT for the first packet of an inbound conn?", "Stream", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.initialRTT.out", "What is the actual RTT for the first packet of an outbound conn?", "Stream", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createFrequencyStat("stream.ack.dup.immediate","How often duplicate packets get acked immediately","Stream",new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.ack.dup.sent","Whether the ack for a duplicate packet was sent as scheduled","Stream",new long[] { 10*60*1000, 60*60*1000 });
    }
    
    /** distribute a packet to the connection specified */
    void receivePacket(Packet packet, Connection con) throws I2PException {
        boolean ok = verifyPacket(packet, con);
        if (!ok) {
            boolean isTooFast = con.getSendStreamId() <= 0;
            if ( (!packet.isFlagSet(Packet.FLAG_RESET)) && (!isTooFast) && (_log.shouldLog(Log.WARN)) )
                _log.warn("Packet does NOT verify: " + packet + " on " + con);
            packet.releasePayload();
            return;
        }

        final long seqNum = packet.getSequenceNum();
        if (con.getHardDisconnected()) {
            if ( (seqNum > 0) || (packet.getPayloadSize() > 0) || 
                 (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE | Packet.FLAG_CLOSE)) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received a data packet after hard disconnect: " + packet + " on " + con);
                // the following will send a RESET
                con.disconnect(false);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received a packet after hard disconnect, ignoring: " + packet + " on " + con);
            }
            packet.releasePayload();
            return;
        }
        
        if ( (con.getCloseSentOn() > 0) && (con.getUnackedPacketsSent() <= 0) && 
             (seqNum > 0) && (packet.getPayloadSize() > 0)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Received new data when we've sent them data and all of our data is acked: " 
                          + packet + " on " + con + "");
            // this is fine, half-close
            // Major bug before 0.9.9, packets were dropped here and a reset sent
            // If we are fully closed, will handle that in the canAccept test below
        }

        if (packet.isFlagSet(Packet.FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            int size = packet.getOptionalMaxSize();
            if (size < ConnectionOptions.MIN_MESSAGE_SIZE) {
                // log.error? connection reset?
                size = ConnectionOptions.MIN_MESSAGE_SIZE;
            }
            if (size < con.getOptions().getMaxMessageSize()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Reducing our max message size to " + size 
                              + " from " + con.getOptions().getMaxMessageSize());
                con.getOptions().setMaxMessageSize(size);
                con.getOutputStream().setBufferSize(size);
            }
        }
        
        con.packetReceived();
        
        boolean choke = false;
        if (packet.isFlagSet(Packet.FLAG_DELAY_REQUESTED)) {
            if (packet.getOptionalDelay() >= Packet.MIN_DELAY_CHOKE) {
                // requested choke 
                choke = true;
                if (_log.shouldWarn())
                    _log.warn("Got a choke on connection " + con + ": " + packet);
                //con.getOptions().setRTT(con.getOptions().getRTT() + 10*1000);
            }
            // Only call this if the flag is set
            con.setChoked(choke);
        }
        
        if (!con.getInputStream().canAccept(seqNum, packet.getPayloadSize())) {
            if (con.getInputStream().isLocallyClosed()) {
                if (_log.shouldWarn())
                    _log.warn("More data received after local close on connection " + con +
                              ", sending reset and dropping " + packet);
                // the following will send a RESET
                con.disconnect(false);
            } else {
                if (_log.shouldWarn())
                    _log.warn("Inbound buffer exceeded on connection " + con +
                              ", choking and dropping " + packet);
                // this will call ackImmediately()
                con.setChoking(true);
                // TODO we could still process the acks for this packet before discarding
            }
            packet.releasePayload();
            return;
        } // else we will call setChoking(false) below

        _context.statManager().addRateData("stream.con.receiveMessageSize", packet.getPayloadSize());
        
        boolean allowAck = true;
        final boolean isSYN = packet.isFlagSet(Packet.FLAG_SYNCHRONIZE);
        
        // We allow the SendStreamID to be 0 so that the originator can send
        // multiple packets before he gets the first ACK back.
        // If we want to limit the number of packets we receive without a
        // SendStreamID, do it in PacketHandler.receiveUnknownCon().
        if ( (!isSYN) && 
             (packet.getReceiveStreamId() <= 0) )
            allowAck = false;

        // Receive the message.
        // Note that this is called even for empty packets, including CLOSE packets, so the
        // MessageInputStream will know the last sequence number.
        // But not ack-only packets!
        boolean isNew;
        if (seqNum > 0 || isSYN) {
            isNew = con.getInputStream().messageReceived(seqNum, packet.getPayload()) &&
                    allowAck;
        } else {
            isNew = false;
        }

        if (isNew && packet.getPayloadSize() > 1500) {
            // don't clear choking unless it was new, and a big packet
            // this will call ackImmediately() if changed
            // TODO if this filled in a hole, we shouldn't unchoke
            // TODO a bunch of small packets should unchoke also
            con.setChoking(false);
        }
        
        //if ( (packet.getSequenceNum() == 0) && (packet.getPayloadSize() > 0) ) {
        //    if (_log.shouldLog(Log.DEBUG))
        //        _log.debug("seq=0 && size=" + packet.getPayloadSize() + ": isNew? " + isNew 
        //                   + " packet: " + packet + " con: " + con);
        //}

        if (_log.shouldLog(Log.DEBUG)) {
            String type;
            if (!allowAck)
                type = "Non-SYN before SYN";
            else if (isNew)
                type = "New";
            else if (packet.getPayloadSize() <= 0)
                type = "Ack-only";
            else
                type = "Dup";
            _log.debug(type + " IB pkt: " + packet + " on " + con);
        }

        boolean ackOnly = false;
        
        if (isNew) {
            con.incrementUnackedPacketsReceived();
            con.incrementBytesReceived(packet.getPayloadSize());
            
            if (packet.isFlagSet(Packet.FLAG_DELAY_REQUESTED) && (packet.getOptionalDelay() <= 0) ) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Scheduling immediate ack for " + packet);
                //con.setNextSendTime(_context.clock().now() + con.getOptions().getSendAckDelay());
                // honor request "almost" immediately
                // TODO the 250 below _may_ be a big limiter in how fast local "loopback" connections
                // can go, however if it goes too fast then we start choking which causes
                // frequent stalls anyway.
                con.setNextSendTime(_context.clock().now() + 250);
            } else {
                int delay = con.getOptions().getSendAckDelay();
                if (packet.isFlagSet(Packet.FLAG_DELAY_REQUESTED)) // delayed ACK requested
                    delay = packet.getOptionalDelay();
                con.setNextSendTime(delay + _context.clock().now());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Scheduling ack in " + delay + "ms for received packet " + packet);
            }
        } else {
            if ( (seqNum > 0) || (packet.getPayloadSize() > 0) || isSYN) {
                _context.statManager().addRateData("stream.con.receiveDuplicateSize", packet.getPayloadSize());
                con.incrementDupMessagesReceived(1);
        
                // take note of congestion
                
                final long now = _context.clock().now();
                final int ackDelay = con.getOptions().getSendAckDelay();
                final long lastSendTime = con.getLastSendTime();
                
                if (_log.shouldLog(Log.INFO))
                    _log.info(String.format("%s congestion.. dup packet %s ackDelay %d lastSend %s ago",
                                    con, packet, ackDelay, DataHelper.formatDuration(now - lastSendTime)));
                
                // If this is longer than his RTO, he will always retransmit, and
                // will be stuck at a window size of 1 forever. So we take the minimum
                // of the ackDelay and half our estimated RTT to be sure.
                final long nextSendTime = lastSendTime + Math.min(ackDelay, con.getOptions().getRTT() / 2);
                if (nextSendTime <= now) {
                    if (_log.shouldLog(Log.INFO)) 
                        _log.info("immediate ack");
                    con.ackImmediately();
                    _context.statManager().updateFrequency("stream.ack.dup.immediate");
                } else {
                    final long delay = nextSendTime - now;
                    if (_log.shouldLog(Log.INFO)) 
                        _log.info("scheduling ack in " + delay);
                    con.schedule(new AckDup(con), delay);
                }

            } else {
                if (isSYN) {
                    //con.incrementUnackedPacketsReceived();
                    con.setNextSendTime(_context.clock().now() + con.getOptions().getSendAckDelay());
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("ACK only packet received: " + packet);
                    ackOnly = true;
                }
            }
        }

        boolean fastAck;
        if (isSYN && (packet.getSendStreamId() <= 0) ) {
            // don't honor the ACK 0 in SYN packets received when the other side
            // has obviously not seen our messages
            fastAck = false;
        } else {
            fastAck = ack(con, packet.getAckThrough(), packet.getNacks(), packet, isNew, choke);
        }
        con.eventOccurred();
        if (fastAck && !choke) {
            if (!isNew) {
                // if we're congested (fastAck) but this is also a new packet, 
                // we've already scheduled an ack above, so there is no need to schedule 
                // a fast ack (we can wait a few ms)
            } else {
                long timeSinceSend = _context.clock().now() - con.getLastSendTime();
                if (timeSinceSend >= 2000) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Fast ack for dup " + packet);
                    con.ackImmediately();
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not fast acking dup " + packet + " since we last sent " + timeSinceSend + "ms ago");
                }
            }
        }
        
        if (ackOnly || !isNew) {
            // non-ack message payloads are queued in the MessageInputStream
            packet.releasePayload();
        }

        // close *after* receiving the data, as well as after verifying the signatures / etc
        // update the TCB Cache now that we've processed the acks and updated our rtt etc.
        if (packet.isFlagSet(Packet.FLAG_CLOSE) && packet.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED)) {
            con.closeReceived();
            if (isNew)
                con.updateShareOpts();
        }

        //if (choke)
        //    con.fastRetransmit();
    }
    
    /**
     * Process the acks in a received packet, and adjust our window and RTT
     * @param isNew was it a new packet? false for ack-only
     * @param choke did we get a choke in the packet?
     * @return are we congested?
     */
    private boolean ack(Connection con, long ackThrough, long nacks[], Packet packet, boolean isNew, boolean choke) {
        if (ackThrough < 0) return false;
        //if ( (nacks != null) && (nacks.length > 0) )
        //    con.getOptions().setRTT(con.getOptions().getRTT() + nacks.length*1000);

        boolean firstAck = isNew && con.getHighestAckedThrough() < 0;

        int numResends = 0;
        List<PacketLocal> acked = null;
        // if we don't know the streamIds for both sides of the connection, there's no way we
        // could actually be acking data (this fixes the buggered up ack of packet 0 problem).
        // this is called after packet verification, which places the stream IDs as necessary if
        // the SYN verifies (so if we're acking w/out stream IDs, no SYN has been received yet)
        if ( (packet != null) && (packet.getSendStreamId() > 0) && (packet.getReceiveStreamId() > 0) &&
             (con != null) && (con.getSendStreamId() > 0) && (con.getReceiveStreamId() > 0) &&
             (packet.getSendStreamId() != Packet.STREAM_ID_UNKNOWN) &&
             (packet.getReceiveStreamId() != Packet.STREAM_ID_UNKNOWN) &&
             (con.getSendStreamId() != Packet.STREAM_ID_UNKNOWN) &&
             (con.getReceiveStreamId() != Packet.STREAM_ID_UNKNOWN) )
            acked = con.ackPackets(ackThrough, nacks);
        else
            return false;
        
        boolean lastPacketAcked = false;
        final boolean receivedAck = con.getOptions().receivedAck();
        if ( (acked != null) && (!acked.isEmpty()) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(acked.size() + " of our packets acked with " + packet);
            // use the highest RTT, since these would likely be bunched together,
            // and the highest rtt lets us set our resend delay properly
            // RFC 6298 part 3 dictates only use packets that haven't been re-sent.
            int highestRTT = -1;
            for (int i = 0; i < acked.size(); i++) {
                PacketLocal p = acked.get(i);
                
                final int numSends = p.getNumSends();
                final int ackTime = p.getAckTime();
                
                if (numSends > 1 && receivedAck)
                    numResends++;
                else if (ackTime > highestRTT) 
                    highestRTT = ackTime;
                
                _context.statManager().addRateData("stream.sendsBeforeAck", numSends, ackTime);
                
                // ACK the tags we delivered so we can use them
                //if ( (p.getKeyUsed() != null) && (p.getTagsSent() != null) 
                //      && (p.getTagsSent().size() > 0) ) {
                //    _context.sessionKeyManager().tagsDelivered(p.getTo().getPublicKey(), 
                //                                               p.getKeyUsed(), 
                //                                               p.getTagsSent());
                //}
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet acked after " + ackTime + "ms: " + p);
            }
            if (highestRTT > 0) {
                if (_log.shouldLog(Log.INFO)) {
                    int oldrtt = con.getOptions().getRTT();
                    int oldrto = con.getOptions().getRTO();
                    int olddev = con.getOptions().getRTTDev();
                    con.getOptions().updateRTT(highestRTT);
                    _log.info("acked: " + acked.size() + " highestRTT: " + highestRTT +
                              " RTT: " + oldrtt + " -> " + con.getOptions().getRTT() +
                              " RTO: " + oldrto + " -> " + con.getOptions().getRTO() +
                              " Dev: " + olddev + " -> " + con.getOptions().getRTTDev());
                } else {
                    con.getOptions().updateRTT(highestRTT);
                }
                if (firstAck) {
                    if (con.isInbound())
                        _context.statManager().addRateData("stream.con.initialRTT.in", highestRTT);
                    else
                        _context.statManager().addRateData("stream.con.initialRTT.out", highestRTT);
                }
            }
            _context.statManager().addRateData("stream.con.packetsAckedPerMessageReceived", acked.size(), highestRTT);
            if (con.getCloseSentOn() > 0 && con.getUnackedPacketsSent() <= 0)
                lastPacketAcked = true;
        }

        boolean rv = adjustWindow(con, isNew, packet.getSequenceNum(), numResends, (acked != null ? acked.size() : 0), choke);
        if (lastPacketAcked)
            con.notifyLastPacketAcked();
        return rv;
    }
    
    /**
     * This either does nothing or increases the window, it never decreases it.
     * Decreasing is done in Connection.ResendPacketEvent.retransmit()
     *
     * @param isNew was it a new packet? false for ack-only
     * @param sequenceNum 0 for ack-only
     * @param choke did we get a choke in the packet?
     * @return are we congested?
     */
    private boolean adjustWindow(Connection con, boolean isNew, long sequenceNum, int numResends, int acked, boolean choke) {
        boolean congested;
        if (choke || (!isNew && sequenceNum > 0) || con.isChoked()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Congestion occurred on the sending side. Not adjusting window "+con);
            congested = true;
        } else {
            congested = false;
        }

        long lowest = con.getHighestAckedThrough();
        // RFC 2581
        // Why wait until we get a whole cwin to start updating the window?
        // That means we don't start increasing the window until after 1 RTT.
        // And whether we increase the window or not (probably not since 1/N),
        // we reset the CongestionWindowEnd and have to wait another RTT.
        // So we add the acked > 1 and UnackedPacketsSent > 0 cases,
        // so we almost always go through the window adjustment code,
        // unless we're just sending a single packet now and then.
        // This keeps the window size from going sky-high from  ping traffic alone.
        // Since we don't adjust the window down after idle? (RFC 2581 sec. 4.1)
        if (lowest >= con.getCongestionWindowEnd() ||
            acked > 1 ||
            con.getUnackedPacketsSent() > 0) {
            // new packet that ack'ed uncongested data, or an empty ack
            int oldWindow = con.getOptions().getWindowSize();
            int newWindowSize = oldWindow;

            int trend = con.getOptions().getRTTTrend();

            _context.statManager().addRateData("stream.trend", trend, newWindowSize);
            
            if ( (!congested) && (acked > 0) && (numResends <= 0) ) {
                if (newWindowSize < con.getLastCongestionSeenAt() / 2) {
                    // Don't make this <= LastCongestion/2 or we'll jump right back to where we were
                    // slow start - exponential growth
                    // grow acked/N times (where N = the slow start factor)
                    // always grow at least 1
                    int factor = con.getOptions().getSlowStartGrowthRateFactor();
                    if (factor <= 1) {
                        // above a certain point, don't grow exponentially
                        // as it often leads to a big packet loss (30-50) all at once that
                        // takes quite a while (a minute or more) to recover from,
                        // especially if crypto tags are lost
                        if (newWindowSize >= MAX_SLOW_START_WINDOW)
                            newWindowSize++;
                        else
                            newWindowSize = Math.min(MAX_SLOW_START_WINDOW, newWindowSize + acked);
                    } else if (acked < factor)
                        newWindowSize++;
                    else
                        newWindowSize += acked / factor;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("slow start acks = " + acked + " for " + con);
                // this is too fast since we mostly disabled the CongestionWindowEnd test above
                //} else if (trend < 0) {
                //    // rtt is shrinking, so lets increment the cwin
                //    newWindowSize++;
                //    if (_log.shouldLog(Log.DEBUG))
                //        _log.debug("trend < 0 for " + con);
                } else {
                    // congestion avoidance
                    // linear growth - increase window 1/N per RTT
                    // we can't use newWindowSize += acked/(oldWindow*N) (where N = the cong. avoid. factor), since we're
                    // integers, so lets use a random distribution instead
                    int shouldIncrement = _context.random().nextInt(con.getOptions().getCongestionAvoidanceGrowthRateFactor()*newWindowSize);
                    if (shouldIncrement < acked)
                        newWindowSize++;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("cong. avoid acks = " + acked + " for " + con);
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No change to window: " + con.getOptions().getWindowSize() +
                               " congested? " + congested + " acked: " + acked + " resends: " + numResends);
            }
            
            if (newWindowSize <= 0)
                newWindowSize = 1;

            con.getOptions().setWindowSize(newWindowSize);
            con.setCongestionWindowEnd(newWindowSize + lowest);
                                
            if (_log.shouldLog(Log.INFO))
                _log.info("New window size " + newWindowSize + "/" + oldWindow + "/" + con.getOptions().getWindowSize() + " congestionSeenAt: "
                           + con.getLastCongestionSeenAt() + " (#resends: " + numResends 
                           + ") for " + con);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No change to window: " + con.getOptions().getWindowSize() +
                           " highestAckedThrough: " + lowest + " congestionWindowEnd: " + con.getCongestionWindowEnd() +
                           " acked: " + acked + " unacked: " + con.getUnackedPacketsSent());
        }
        
        con.windowAdjusted();
        return congested;
    }
    
    /**
     * If we don't know the send stream id yet (we're just creating a connection), allow
     * the first three packets to come in.  The first of those should be the SYN, of course...
     */
    private static final int MAX_INITIAL_PACKETS = ConnectionOptions.INITIAL_WINDOW_SIZE;
    
    /**
     * Make sure this packet is ok and that we can continue processing its data.
     *
     * SIDE EFFECT:
     * Sets the SendStreamId and RemotePeer for the con,
     * using the packet's ReceiveStreamId and OptionalFrom,
     * If this is a SYN packet and the con's SendStreamId is not set.
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
            
            if (con.getSendStreamId() <= 0) {
                if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    con.setSendStreamId(packet.getReceiveStreamId());
                    Destination dest = packet.getOptionalFrom();
                    if (dest == null) {
                        if (_log.shouldWarn())
                            _log.warn("SYN Packet without FROM");
                        return false;
                    }
                    con.setRemotePeer(dest);
                    return true;
                } else {
                    // neither RST nor SYN and we dont have the stream id yet?
                    if (packet.getSequenceNum() < MAX_INITIAL_PACKETS) {
                        return true;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Packet without RST or SYN where we dont know stream ID: " 
                                      + packet);
                        return false;
                    }
                }
            } else {
                if (con.getSendStreamId() != packet.getReceiveStreamId()) {
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
     *
     * Prior to 0.9.20, the reset packet must contain a FROM field,
     * and we used that for verification.
     * As of 0.9.20, we correctly use the connection's remote peer.
     */
    private void verifyReset(Packet packet, Connection con) {
        if (con.getReceiveStreamId() == packet.getSendStreamId()) {
            Destination from = con.getRemotePeer();
            if (from == null)
                from = packet.getOptionalFrom();
            boolean ok = packet.verifySignature(_context, from, null);
            if (!ok) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Received unsigned / forged RST on " + con);
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
                _log.warn("Received a packet for the wrong connection? " 
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
            packet.isFlagSet(Packet.FLAG_SYNCHRONIZE | Packet.FLAG_CLOSE)) {
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
        private final long _created;
        private final Connection _con;

        public AckDup(Connection con) {
            _created = _context.clock().now();
            _con = con;
        }

        public void timeReached() {
            boolean sent = false;
            if (_con.getLastSendTime() <= _created) {
                if (_con.getResetReceived() || _con.getResetSent()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Ack dup on " + _con + ", but we have been reset");
                    return;
                }
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Last sent was a while ago, and we want to ack a dup on " + _con);
                // we haven't done anything since receiving the dup, send an
                // ack now
                _con.ackImmediately();
                sent = true;
            } else {                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Ack dup on " + _con + ", but we have sent (" + (_con.getLastSendTime()-_created) + ")");
            }
            _context.statManager().addRateData("stream.ack.dup.sent", sent ? 1 : 0);
        }
    }
}
