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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.util.Log;

/**
 * Defines the message a router sends to another router in response to a 
 * search (DatabaseFindNearest or DatabaseLookup) when it doesn't have the value, 
 * specifying what routers it would search.
 *
 * @author jrandom
 */
public class DatabaseSearchReplyMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DatabaseSearchReplyMessage.class);
    public final static int MESSAGE_TYPE = 3;
    private Hash _key;
    private List _routerInfoStructures;
    private Hash _from;
    
    public DatabaseSearchReplyMessage() { 
	setSearchKey(null);
	_routerInfoStructures = new ArrayList();
	setFromHash(null);
    }
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }
    public void setSearchKey(Hash key) { _key = key; }
    
    public int getNumReplies() { return _routerInfoStructures.size(); }
    public RouterInfo getReply(int index) { return (RouterInfo)_routerInfoStructures.get(index); }
    public void addReply(RouterInfo info) { _routerInfoStructures.add(info); }
    public void addReplies(Collection replies) { _routerInfoStructures.addAll(replies); }
    
    public Hash getFromHash() { return _from; }
    public void setFromHash(Hash from) { _from = from; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
	if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
	    _key = new Hash();
	    _key.readBytes(in);
	    
	    int compressedLength = (int)DataHelper.readLong(in, 2);
	    byte compressedData[] = new byte[compressedLength];
	    int read = DataHelper.read(in, compressedData);
	    if (read != compressedLength)
		throw new IOException("Not enough data to decompress");
	    byte decompressedData[] = DataHelper.decompress(compressedData);
	    ByteArrayInputStream bais = new ByteArrayInputStream(decompressedData);
	    int num = (int)DataHelper.readLong(bais, 1);
	    _routerInfoStructures.clear();
	    for (int i = 0; i < num; i++) {
		RouterInfo info = new RouterInfo();
		info.readBytes(bais);
		addReply(info);
	    }
	    
	    _from = new Hash();
	    _from.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
	if (_key == null)
	    throw new I2NPMessageException("Key in reply to not specified");
	if (_routerInfoStructures == null)
	    throw new I2NPMessageException("RouterInfo replies are null");
	if (_routerInfoStructures.size() <= 0)
	    throw new I2NPMessageException("No replies specified in SearchReply!  Always include oneself!");
	if (_from == null)
	    throw new I2NPMessageException("No 'from' address specified!");
	
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
	    _key.writeBytes(os);
	    
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
	    DataHelper.writeLong(baos, 1, _routerInfoStructures.size());
	    for (int i = 0; i < getNumReplies(); i++) {
		RouterInfo info = getReply(i);
		info.writeBytes(baos);
	    }
	    
	    byte compressed[] = DataHelper.compress(baos.toByteArray());
	    DataHelper.writeLong(os, 2, compressed.length);
	    os.write(compressed);
	    _from.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseSearchReplyMessage) ) {
            DatabaseSearchReplyMessage msg = (DatabaseSearchReplyMessage)object;
            return DataHelper.eq(getSearchKey(),msg.getSearchKey()) &&
		   DataHelper.eq(getFromHash(),msg.getFromHash()) && 
		   DataHelper.eq(_routerInfoStructures,msg._routerInfoStructures);
        } else {
            return false;
        }
    }
    
    public int hashCode() {
	return DataHelper.hashCode(getSearchKey()) +
	       DataHelper.hashCode(getFromHash()) +
	       DataHelper.hashCode(_routerInfoStructures);
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[DatabaseSearchReplyMessage: ");
        buf.append("\n\tSearch Key: ").append(getSearchKey());
        buf.append("\n\tReplies: # = ").append(getNumReplies());
	for (int i = 0; i < getNumReplies(); i++) {
	    buf.append("\n\t\tReply [").append(i).append("]: ").append(getReply(i));
	}
        buf.append("\n\tFrom: ").append(getFromHash());
        buf.append("]");
        return buf.toString();
    }
}
