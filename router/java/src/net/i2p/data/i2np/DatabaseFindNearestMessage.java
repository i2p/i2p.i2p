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
import net.i2p.util.Log;

/**
 * Defines the message a router sends to another router to help integrate into
 * the network by searching for routers in a particular keyspace.
 *
 * @author jrandom
 */
public class DatabaseFindNearestMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DatabaseFindNearestMessage.class);
    public final static int MESSAGE_TYPE = 4;
    private Hash _key;
    private Hash _from;
    
    public DatabaseFindNearestMessage() { 
	setSearchKey(null);
	setFromHash(null);
    }
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }
    public void setSearchKey(Hash key) { _key = key; }
    
    /**
     * Contains the SHA256 Hash of the RouterIdentity sending the message
     */
    public Hash getFromHash() { return _from; }
    public void setFromHash(Hash from) { _from = from; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
	if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
	    _key = new Hash();
	    _key.readBytes(in);
	    _from = new Hash();
	    _from.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
	if ( (_key == null) || (_from == null) ) throw new I2NPMessageException("Not enough data to write out");
	
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
	    _key.writeBytes(os);
	    _from.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
	return DataHelper.hashCode(getSearchKey()) +
	       DataHelper.hashCode(getFromHash());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseFindNearestMessage) ) {
            DatabaseFindNearestMessage msg = (DatabaseFindNearestMessage)object;
            return DataHelper.eq(getSearchKey(),msg.getSearchKey()) &&
		   DataHelper.eq(getFromHash(),msg.getFromHash());
        } else {
            return false;
        }
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[DatabaseFindNearestMessage: ");
        buf.append("\n\tSearch Key: ").append(getSearchKey());
        buf.append("\n\tFrom: ").append(getFromHash());
        buf.append("]");
        return buf.toString();
    }
}
