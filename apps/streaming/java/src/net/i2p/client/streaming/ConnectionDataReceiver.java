package net.i2p.client.streaming;

import java.io.InterruptedIOException;
import java.io.IOException;
import net.i2p.I2PAppContext;
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
    private MessageOutputStream.WriteStatus _dummyStatus;
    
    public ConnectionDataReceiver(I2PAppContext ctx, Connection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConnectionDataReceiver.class);
        _connection = con;
        _dummyStatus = new DummyStatus();
    }
    
    public boolean writeInProcess() {
        return _connection.getUnackedPacketsSent() > 0;
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
        boolean doSend = true;
        if ( (size <= 0) && (_connection.getLastSendId() >= 0) ) {
            if (_connection.getOutputStream().getClosed()) {
                if (_connection.getCloseSentOn() < 0) {
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
        
        if (_connection.getUnackedPacketsReceived() > 0)
            doSend = true;
        
        if (_log.shouldLog(Log.INFO) && !doSend)
            _log.info("writeData called: size="+size + " doSend=" + doSend 
                       + " unackedReceived: " + _connection.getUnackedPacketsReceived()
                       + " con: " + _connection, new Exception("write called by"));

        if (doSend) {
            PacketLocal packet = send(buf, off, size);
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
     * @return the packet sent
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
        long before = System.currentTimeMillis();
        PacketLocal packet = buildPacket(buf, off, size, forceIncrement);
        long built = System.currentTimeMillis();
        _connection.sendPacket(packet);
        long sent = System.currentTimeMillis();
        
        if ( (built-before > 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("wtf, took " + (built-before) + "ms to build a packet: " + packet);
        if ( (sent-built> 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("wtf, took " + (sent-built) + "ms to send a packet: " + packet);
        return packet;
    }
    
    private boolean isAckOnly(int size) {
        boolean ackOnly = ( (size <= 0) && // no data
                            (_connection.getLastSendId() >= 0) && // not a SYN
                            ( (!_connection.getOutputStream().getClosed()) || // not a CLOSE
                            (_connection.getOutputStream().getClosed() && 
                             _connection.getCloseSentOn() > 0) )); // or it is a dup CLOSE
        return ackOnly;
    }
    
    private PacketLocal buildPacket(byte buf[], int off, int size, boolean forceIncrement) {
        boolean ackOnly = isAckOnly(size);
        PacketLocal packet = new PacketLocal(_context, _connection.getRemotePeer(), _connection);
        byte data[] = new byte[size];
        if (size > 0)
            System.arraycopy(buf, off, data, 0, size);
        packet.setPayload(data);
		if (ackOnly && !forceIncrement)
			packet.setSequenceNum(0);
        else
            packet.setSequenceNum(_connection.getNextOutboundPacketNum());
        packet.setSendStreamId(_connection.getSendStreamId());
        packet.setReceiveStreamId(_connection.getReceiveStreamId());
        
        _connection.getInputStream().updateAcks(packet);
        packet.setOptionalDelay(_connection.getOptions().getChoke());
        packet.setOptionalMaxSize(_connection.getOptions().getMaxMessageSize());
        packet.setResendDelay(_connection.getOptions().getResendDelay());
        
        if (_connection.getOptions().getProfile() == ConnectionOptions.PROFILE_INTERACTIVE)
            packet.setFlag(Packet.FLAG_PROFILE_INTERACTIVE, true);
        else
            packet.setFlag(Packet.FLAG_PROFILE_INTERACTIVE, false);
        
        packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED, _connection.getOptions().getRequireFullySigned());
        
        if ( (!ackOnly) && (packet.getSequenceNum() <= 0) ) {
            packet.setFlag(Packet.FLAG_SYNCHRONIZE);
            packet.setOptionalFrom(_connection.getSession().getMyDestination());
        }
        
        // don't set the closed flag if this is a plain ACK and there are outstanding
        // packets sent, otherwise the other side could receive the CLOSE prematurely,
        // since this ACK could arrive before the unacked payload message.
        if (_connection.getOutputStream().getClosed() && 
            ( (size > 0) || (_connection.getUnackedPacketsSent() <= 0) ) ) {
            packet.setFlag(Packet.FLAG_CLOSE);
            _connection.setCloseSentOn(_context.clock().now());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Closed is set for a new packet on " + _connection + ": " + packet);
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Closed is not set for a new packet on " + _connection + ": " + packet);
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
        _connection = null;
    }
}
