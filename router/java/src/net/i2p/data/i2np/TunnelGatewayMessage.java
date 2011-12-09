package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

/**
 * Defines the message sent between one tunnel's endpoint and another's gateway.
 * format: { tunnelId, sizeof(i2npMessage.toByteArray()), i2npMessage.toByteArray() }
 *
 */
public class TunnelGatewayMessage extends I2NPMessageImpl {
    private TunnelId _tunnelId;
    private I2NPMessage _msg;
    private byte _msgData[];
    //private Exception _creator;
    
    public final static int MESSAGE_TYPE = 19;
    /** if we can't deliver a tunnel message in 10s, fuck it */
    private static final int EXPIRATION_PERIOD = 10*1000;
    
    public TunnelGatewayMessage(I2PAppContext context) {
        super(context);
        setMessageExpiration(context.clock().now() + EXPIRATION_PERIOD);
        //_creator = new Exception("i made this");
    }
    
    public TunnelId getTunnelId() { return _tunnelId; }
    public void setTunnelId(TunnelId id) { _tunnelId = id; }
    
    /**
     *  Warning, at the IBGW, where the message was read in,
     *  this will be an UnknownI2NPMessage.
     *  If you need a real message class, use UnknownI2NPMessage.convert().
     */
    public I2NPMessage getMessage() { return _msg; }

    public void setMessage(I2NPMessage msg) { 
        if (msg == null)
            throw new IllegalArgumentException("wtf, dont set me to null");
        _msg = msg; 
    }
    
    protected int calculateWrittenLength() {
        synchronized (this) {
            if (_msgData == null) {
                _msgData = _msg.toByteArray();
                _msg = null;
            }
        }
        return _msgData.length + 4 + 2;
    }
    
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ( (_tunnelId == null) || ( (_msg == null) && (_msgData == null) ) ) {
            _log.log(Log.CRIT, "failing to write out gateway message");
            throw new I2NPMessageException("Not enough data to write out (id=" + _tunnelId + " data=" + _msg + ")");
        }
        
        DataHelper.toLong(out, curIndex, 4, _tunnelId.getTunnelId());
        curIndex += 4;
        synchronized (this) {
            if (_msgData == null) {
                _msgData = _msg.toByteArray();
                _msg = null;
            }
        }
        DataHelper.toLong(out, curIndex, 2, _msgData.length);
        curIndex += 2;
        // where is this coming from?
        if (curIndex + _msgData.length > out.length) {
            _log.log(Log.ERROR, "output buffer too small idx: " + curIndex + " len: " + _msgData.length + " outlen: " + out.length);
            throw new I2NPMessageException("Too much data to write out (id=" + _tunnelId + " data=" + _msg + ")");
        }
        System.arraycopy(_msgData, 0, out, curIndex, _msgData.length);
        curIndex += _msgData.length;
        return curIndex;
    }
    

    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        //I2NPMessageHandler h = new I2NPMessageHandler(_context);
        //readMessage(data, offset, dataSize, type, h);
        readMessage(data, offset, dataSize, type, null);
    }

    /**
     *  Note that for efficiency at the IBGW, this does not fully deserialize the included
     *  I2NP Message. It just puts it in an UnknownI2NPMessage.
     *
     *  @param handler unused, may be null
     */
    @Override
    public void readMessage(byte data[], int offset, int dataSize, int type, I2NPMessageHandler handler) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _tunnelId = new TunnelId(DataHelper.fromLong(data, curIndex, 4));
        curIndex += 4;
        
        if (_tunnelId.getTunnelId() <= 0) 
            throw new I2NPMessageException("Invalid tunnel Id " + _tunnelId);
        
        int len = (int) DataHelper.fromLong(data, curIndex, 2);
        curIndex += 2;
        if (len <= 1 || curIndex + len > data.length || len > dataSize - 6)
            throw new I2NPMessageException("I2NP length in TGM: " + len +
                                           " but remaining bytes: " + Math.min(data.length - curIndex, dataSize - 6));

        // OLD WAY full message parsing and instantiation
        //handler.readMessage(data, curIndex);
        //_msg = handler.lastRead();
        //if (_msg == null)
        //    throw new I2NPMessageException("wtf, message read has no payload?");

        // NEW WAY save lots of effort at the IBGW by reading as an UnknownI2NPMessage instead
        // This will save a lot of object churn and processing,
        // primarily for unencrypted msgs (V)TBRM, DatabaseStoreMessage, and DSRMs.
        // DatabaseStoreMessages in particluar are intensive for readBytes()
        // since the RI is decompressed.
        // For a zero-hop IB tunnel, where we do need the real thing,
        // it is converted to a real message class in TunnelGatewayZeroHop
        // using UnknownI2NPMessage.convert() in TunnelGatewayZeroHop.
        // We also skip processing the checksum as it's covered by the TGM checksum.
        // If a zero-hop, the checksum will be verified in convert().
        int utype = data[curIndex++] & 0xff;
        UnknownI2NPMessage umsg = new UnknownI2NPMessage(_context, utype);
        umsg.readBytesIgnoreChecksum(data, curIndex);
        _msg = umsg;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getTunnelId()) +
               DataHelper.hashCode(_msg);
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelGatewayMessage) ) {
            TunnelGatewayMessage msg = (TunnelGatewayMessage)object;
            return DataHelper.eq(getTunnelId(),msg.getTunnelId()) &&
                   DataHelper.eq(_msgData, msg._msgData) &&
                   DataHelper.eq(getMessage(), msg.getMessage());
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[TunnelGatewayMessage:");
        buf.append(" Tunnel ID: ").append(getTunnelId());
        buf.append(" Message: ").append(_msg);
        buf.append("]");
        return buf.toString();
    }
}
