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
 * Defines the message a router sends to another router in reply to a
 * TunnelCreateMessage
 *
 * @author jrandom
 */
public class TunnelCreateStatusMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(TunnelCreateStatusMessage.class);
    public final static int MESSAGE_TYPE = 7;
    private TunnelId _receiveTunnelId;
    private int _status;
    private long _nonce;
    
    public final static int STATUS_SUCCESS = 0;
    
    public TunnelCreateStatusMessage(I2PAppContext context) {
        super(context);
        setReceiveTunnelId(null);
        setStatus(-1);
        setNonce(-1);
    }
    
    public TunnelId getReceiveTunnelId() { return _receiveTunnelId; }
    public void setReceiveTunnelId(TunnelId id) { 
        _receiveTunnelId = id; 
        if ( (id != null) && (id.getTunnelId() <= 0) )
            throw new IllegalArgumentException("wtf, tunnelId " + id);
    }
    
    public int getStatus() { return _status; }
    public void setStatus(int status) { _status = status; }
    
    public long getNonce() { return _nonce; }
    public void setNonce(long nonce) { _nonce = nonce; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _receiveTunnelId = new TunnelId(DataHelper.fromLong(data, curIndex, 4));
        curIndex += 4;
        
        if (_receiveTunnelId.getTunnelId() <= 0)
            throw new I2NPMessageException("wtf, negative tunnelId? " + _receiveTunnelId);
        
        _status = (int)DataHelper.fromLong(data, curIndex, 1);
        curIndex++;
        
        _nonce = DataHelper.fromLong(data, curIndex, 4);
    }
    
        
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        return 4 + 1 + 4; // id + status + nonce
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ( (_receiveTunnelId == null) || (_nonce <= 0) ) throw new I2NPMessageException("Not enough data to write out");
        if (_receiveTunnelId.getTunnelId() <= 0) throw new I2NPMessageException("Invalid tunnelId!? " + _receiveTunnelId);
        
        DataHelper.toLong(out, curIndex, 4, _receiveTunnelId.getTunnelId());
        curIndex += 4;
        DataHelper.toLong(out, curIndex, 1, _status);
        curIndex++;
        DataHelper.toLong(out, curIndex, 4, _nonce);
        curIndex += 4;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getReceiveTunnelId()) +
               getStatus() +
               (int)getNonce();
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelCreateStatusMessage) ) {
            TunnelCreateStatusMessage msg = (TunnelCreateStatusMessage)object;
            return DataHelper.eq(getReceiveTunnelId(),msg.getReceiveTunnelId()) &&
                   DataHelper.eq(getNonce(),msg.getNonce()) &&
                   (getStatus() == msg.getStatus());
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[TunnelCreateStatusMessage: ");
        buf.append("\n\tTunnel ID: ").append(getReceiveTunnelId());
        buf.append("\n\tStatus: ").append(getStatus());
        buf.append("\n\tNonce: ").append(getNonce());
        buf.append("]");
        return buf.toString();
    }
}
