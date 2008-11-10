package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Receive data from the MessageOutputStream, build a packet,
 * and send it through a connection.  The write calls on this
 * do NOT block, but they also do not necessary imply immediate
 * delivery, or even the generation of a new packet.  This class
 * is the only one that builds useful outbound Packet objects.
 *
 */
class ConnectionDataReceiver implements MessageOutputStream.DataReceiver {
    private I2PAppContext _context;
    private Log _log;
    private Connection _connection;
    private static final MessageOutputStream.WriteStatus _dummyStatus = new DummyStatus();
    
    public ConnectionDataReceiver(I2PAppContext ctx, Connection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConnectionDataReceiver.class);
        _connection = con;
    }
    
    /**
     * This tells the flusher in MessageOutputStream whether to flush.
     * It won't flush if this returns true.
     * It was: return con.getUnackedPacketsSent() > 0;
     * But then, for data that fills more than one packet, the last part of
     * the data isn't sent until all the previous packets are acked. Which is very slow.
     *
     * So let's send data along unless the outbound window is full.
     *
     * @return !flush
     */
    public boolean writeInProcess() {
        Connection con = _connection;
        if (con != null)
            return con.getUnackedPacketsSent() >= con.getOptions().getWindowSize();
        return false;
    }
    
    /**
     * Send some data through the connection, or if there is no new data, this
     * may generate a packet with a plain ACK/NACK or CLOSE, or nothing whatsoever
     * if there's nothing new to send.
     *
     * @param buf data to be sent - may be null
     * @param off offset into the buffer to start writing from
     * @param size how many bytes of the buffer to write (may be 0)
     * @return an object to allow optional blocking for data acceptance or 
     *         delivery.
     */
    public MessageOutputStream.WriteStatus writeData(byte[] buf, int off, int size) {
        Connection con = _connection;
        if (con == null) return _dummyStatus;
        boolean doSend = true;
        if ( (size <= 0) && (con.getLastSendId() >= 0) ) {
            if (con.getOutputStream().getClosed()) {
                if (con.getCloseSentOn() < 0) {
                    doSend = true;
                } else {
                    // closed, no new data, and we've already sent a close packet
                    doSend = false;
                }
            } else {
                // no new data, not closed, already synchronized
                doSend = false;
            }
        }
        
        if (con.getUnackedPacketsReceived() > 0)
            doSend = true;
        
        if (_log.shouldLog(Log.INFO) && !doSend)
            _log.info("writeData called: size="+size + " doSend=" + doSend 
                       + " unackedReceived: " + con.getUnackedPacketsReceived()
                       + " con: " + con, new Exception("write called by"));

        if (doSend) {
            PacketLocal packet = send(buf, off, size);
            if (packet == null) return _dummyStatus;
            
            //dont wait for non-acks
            if ( (packet.getSequenceNum() > 0) || (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) )
                return packet;
            else
                return _dummyStatus;
        } else {
            return _dummyStatus;
        }
    }
    
    
    /**
     * Send some data through the connection, attaching any appropriate flags
     * onto the packet.
     *
     * @param buf data to be sent - may be null
     * @param off offset into the buffer to start writing from
     * @param size how many bytes of the buffer to write (may be 0)
     * @return the packet sent, or null if the connection died
     */
    public PacketLocal send(byte buf[], int off, int size) {
        return send(buf, off, size, false);
    }
    /** 
     * @param buf data to be sent - may be null
     * @param off offset into the buffer to start writing from
     * @param size how many bytes of the buffer to write (may be 0)
     * @param forceIncrement even if the buffer is empty, increment the packetId
     *                       so we get an ACK back
     * @return the packet sent
     */
    public PacketLocal send(byte buf[], int off, int size, boolean forceIncrement) {
        Connection con = _connection;
        if (con == null) return null;
        long before = System.currentTimeMillis();
        PacketLocal packet = buildPacket(con, buf, off, size, forceIncrement);
        long built = System.currentTimeMillis();
        con.sendPacket(packet);
        long sent = System.currentTimeMillis();
        
        if ( (built-before > 5*1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("wtf, took " + (built-before) + "ms to build a packet: " + packet);
        if ( (sent-built> 5*1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("wtf, took " + (sent-built) + "ms to send a packet: " + packet);
        return packet;
    }
    
    private boolean isAckOnly(Connection con, int size) {
        boolean ackOnly = ( (size <= 0) && // no data
                            (con.getLastSendId() >= 0) && // not a SYN
                            ( (!con.getOutputStream().getClosed()) || // not a CLOSE
                            (con.getOutputStream().getClosed() && 
                             con.getCloseSentOn() > 0) )); // or it is a dup CLOSE
        return ackOnly;
    }
    
    private PacketLocal buildPacket(Connection con, byte buf[], int off, int size, boolean forceIncrement) {
        if (size > Packet.MAX_PAYLOAD_SIZE) throw new IllegalArgumentException("size is too large (" + size + ")");
        boolean ackOnly = isAckOnly(con, size);
        boolean isFirst = (con.getAckedPackets() <= 0) && (con.getUnackedPacketsSent() <= 0);
        
        PacketLocal packet = new PacketLocal(_context, con.getRemotePeer(), con);
        //ByteArray data = packet.acquirePayload();
        ByteArray data = new ByteArray(new byte[size]);
        if (size > 0)
            System.arraycopy(buf, off, data.getData(), 0, size);
        data.setValid(size);
        data.setOffset(0);
        packet.setPayload(data);
        if ( (ackOnly && !forceIncrement) && (!isFirst) )
            packet.setSequenceNum(0);
        else
            packet.setSequenceNum(con.getNextOutboundPacketNum());
        packet.setSendStreamId(con.getSendStreamId());
        packet.setReceiveStreamId(con.getReceiveStreamId());
        
        con.getInputStream().updateAcks(packet);
        int choke = con.getOptions().getChoke();
        packet.setOptionalDelay(choke);
        if (choke > 0)
            packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
        packet.setResendDelay(con.getOptions().getResendDelay());
        
        if (con.getOptions().getProfile() == ConnectionOptions.PROFILE_INTERACTIVE)
            packet.setFlag(Packet.FLAG_PROFILE_INTERACTIVE, true);
        else
            packet.setFlag(Packet.FLAG_PROFILE_INTERACTIVE, false);
        
        packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED, con.getOptions().getRequireFullySigned());
        
        //if ( (!ackOnly) && (packet.getSequenceNum() <= 0) ) {
        if (isFirst) {
            packet.setFlag(Packet.FLAG_SYNCHRONIZE);
            packet.setOptionalFrom(con.getSession().getMyDestination());
            packet.setOptionalMaxSize(con.getOptions().getMaxMessageSize());
        }
        if (DataHelper.eq(con.getSendStreamId(), Packet.STREAM_ID_UNKNOWN)) {
            packet.setFlag(Packet.FLAG_NO_ACK);
        }
        
        // don't set the closed flag if this is a plain ACK and there are outstanding
        // packets sent, otherwise the other side could receive the CLOSE prematurely,
        // since this ACK could arrive before the unacked payload message.
        if (con.getOutputStream().getClosed() && 
            ( (size > 0) || (con.getUnackedPacketsSent() <= 0) || (packet.getSequenceNum() > 0) ) ) {
            packet.setFlag(Packet.FLAG_CLOSE);
            con.setCloseSentOn(_context.clock().now());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Closed is set for a new packet on " + con + ": " + packet);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Closed is not set for a new packet on " + _connection + ": " + packet);
        }
        return packet;
    }

    /**
     * Used if no new packet was sent.
     */
    private static final class DummyStatus implements MessageOutputStream.WriteStatus {
        public final void waitForAccept(int maxWaitMs) { return; }
        public final void waitForCompletion(int maxWaitMs) { return; }
        public final boolean writeAccepted() { return true; }
        public final boolean writeFailed() { return false; }
        public final boolean writeSuccessful() { return true; }
    }    
    
    void destroy() {
        //_connection = null;
    }
}
