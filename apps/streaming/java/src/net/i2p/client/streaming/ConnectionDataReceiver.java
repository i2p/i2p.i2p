package net.i2p.client.streaming;

import java.io.InterruptedIOException;
import java.io.IOException;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
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
        
        if (_log.shouldLog(Log.ERROR) && !doSend)
            _log.error("writeData called: size="+size + " doSend=" + doSend 
                       + " unackedReceived: " + _connection.getUnackedPacketsReceived()
                       + " con: " + _connection, new Exception("write called by"));

        if (doSend) {
            PacketLocal packet = send(buf, off, size);
            return packet;
        } else {
            return _dummyStatus;
        }
    }
    
    
    public PacketLocal send(byte buf[], int off, int size) {
        PacketLocal packet = buildPacket(buf, off, size);
        _connection.sendPacket(packet);
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
    
    private PacketLocal buildPacket(byte buf[], int off, int size) {
        boolean ackOnly = isAckOnly(size);
        PacketLocal packet = new PacketLocal(_context, _connection.getRemotePeer(), _connection);
        byte data[] = new byte[size];
        if (size > 0)
            System.arraycopy(buf, off, data, 0, size);
        packet.setPayload(data);
		if (ackOnly)
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
        
        if (_connection.getOutputStream().getClosed()) {
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

    
    private static final class DummyStatus implements MessageOutputStream.WriteStatus {
        public final void waitForAccept(int maxWaitMs) { return; }
        public final void waitForCompletion(int maxWaitMs) { return; }
        public final boolean writeAccepted() { return true; }
        public final boolean writeFailed() { return false; }
        public final boolean writeSuccessful() { return true; }
    }    
}
