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
    
    public TunnelCreateStatusMessage() { 
	setTunnelId(null);
	setStatus(-1);
	setFromHash(null);
    }
    
    public TunnelId getTunnelId() { return _tunnelId; }
    public void setTunnelId(TunnelId id) { _tunnelId = id; }
    
    public int getStatus() { return _status; }
    public void setStatus(int status) { _status = status; }
    
    /**
     * Contains the SHA256 Hash of the RouterIdentity sending the message
     */
    public Hash getFromHash() { return _from; }
    public void setFromHash(Hash from) { _from = from; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
	if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
	    _tunnelId = new TunnelId();
	    _tunnelId.readBytes(in);
	    _status = (int)DataHelper.readLong(in, 1);
	    _from = new Hash();
	    _from.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
	if ( (_tunnelId == null) || (_from == null) ) throw new I2NPMessageException("Not enough data to write out");
	
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
	    _tunnelId.writeBytes(os);
	    DataHelper.writeLong(os, 1, (_status < 0 ? 255 : _status));
	    _from.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
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
