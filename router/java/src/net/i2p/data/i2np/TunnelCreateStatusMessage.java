package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
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
    private TunnelId _tunnelId;
    private int _status;
    private Hash _from;
    
    public final static int STATUS_SUCCESS = 0;
    public final static int STATUS_FAILED_DUPLICATE_ID = 1;
    public final static int STATUS_FAILED_OVERLOADED = 2;
    public final static int STATUS_FAILED_CERTIFICATE = 3;
    public final static int STATUS_FAILED_DELETED = 100;
    
    public TunnelCreateStatusMessage(I2PAppContext context) {
        super(context);
        setTunnelId(null);
        setStatus(-1);
        setFromHash(null);
    }
    
    public TunnelId getTunnelId() { return _tunnelId; }
    public void setTunnelId(TunnelId id) { 
        _tunnelId = id; 
        if ( (id != null) && (id.getTunnelId() <= 0) )
            throw new IllegalArgumentException("wtf, tunnelId " + id);
    }
    
    public int getStatus() { return _status; }
    public void setStatus(int status) { _status = status; }
    
    /**
     * Contains the SHA256 Hash of the RouterIdentity sending the message
     */
    public Hash getFromHash() { return _from; }
    public void setFromHash(Hash from) { _from = from; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _tunnelId = new TunnelId(DataHelper.fromLong(data, curIndex, 4));
        curIndex += 4;
        
        if (_tunnelId.getTunnelId() <= 0)
            throw new I2NPMessageException("wtf, negative tunnelId? " + _tunnelId);
        
        _status = (int)DataHelper.fromLong(data, curIndex, 1);
        curIndex++;
        byte peer[] = new byte[Hash.HASH_LENGTH];
        System.arraycopy(data, curIndex, peer, 0, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        _from = new Hash(peer);
    }
    
        
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        return 4 + 1 + Hash.HASH_LENGTH; // id + status + from
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ( (_tunnelId == null) || (_from == null) ) throw new I2NPMessageException("Not enough data to write out");
        if (_tunnelId.getTunnelId() < 0) throw new I2NPMessageException("Negative tunnelId!? " + _tunnelId);
        
        byte id[] = DataHelper.toLong(4, _tunnelId.getTunnelId());
        System.arraycopy(id, 0, out, curIndex, 4);
        curIndex += 4;
        byte status[] = DataHelper.toLong(1, _status);
        out[curIndex++] = status[0];
        System.arraycopy(_from.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return DataHelper.hashCode(getTunnelId()) +
               getStatus() +
               DataHelper.hashCode(getFromHash());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelCreateStatusMessage) ) {
            TunnelCreateStatusMessage msg = (TunnelCreateStatusMessage)object;
            return DataHelper.eq(getTunnelId(),msg.getTunnelId()) &&
                   DataHelper.eq(getFromHash(),msg.getFromHash()) &&
                   (getStatus() == msg.getStatus());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[TunnelCreateStatusMessage: ");
        buf.append("\n\tTunnel ID: ").append(getTunnelId());
        buf.append("\n\tStatus: ").append(getStatus());
        buf.append("\n\tFrom: ").append(getFromHash());
        buf.append("]");
        return buf.toString();
    }
}
