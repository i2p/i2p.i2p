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
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
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
    private List _peerHashes;
    private Hash _from;
    
    public DatabaseSearchReplyMessage(I2PAppContext context) {
        super(context);
        _context.statManager().createRateStat("netDb.searchReplyMessageSend", "How many search reply messages we send", "Network Database", new long[] { 60*1000, 5*60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("netDb.searchReplyMessageReceive", "How many search reply messages we receive", "Network Database", new long[] { 60*1000, 5*60*1000, 10*60*1000, 60*60*1000 });
        setSearchKey(null);
        _peerHashes = new ArrayList(3);
        setFromHash(null);
    }
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }
    public void setSearchKey(Hash key) { _key = key; }
    
    public int getNumReplies() { return _peerHashes.size(); }
    public Hash getReply(int index) { return (Hash)_peerHashes.get(index); }
    public void addReply(Hash peer) { _peerHashes.add(peer); }
    //public void addReplies(Collection replies) { _peerHashes.addAll(replies); }
    
    public Hash getFromHash() { return _from; }
    public void setFromHash(Hash from) { _from = from; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            _key = new Hash();
            _key.readBytes(in);
            
            int num = (int)DataHelper.readLong(in, 1);
            _peerHashes.clear();
            for (int i = 0; i < num; i++) {
                Hash peer = new Hash();
                peer.readBytes(in);
                addReply(peer);
            }
            
            _from = new Hash();
            _from.readBytes(in);

            _context.statManager().addRateData("netDb.searchReplyMessageReceive", num*32 + 64, 1);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
        if (_key == null)
            throw new I2NPMessageException("Key in reply to not specified");
        if (_peerHashes == null)
            throw new I2NPMessageException("Peer replies are null");
        if (_from == null)
            throw new I2NPMessageException("No 'from' address specified!");
        
        byte rv[] = null;
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
            _key.writeBytes(os);
            
            DataHelper.writeLong(os, 1, _peerHashes.size());
            for (int i = 0; i < getNumReplies(); i++) {
                Hash peer = getReply(i);
                peer.writeBytes(os);
            }
            
            _from.writeBytes(os);

            rv = os.toByteArray();
            _context.statManager().addRateData("netDb.searchReplyMessageSendSize", rv.length, 1);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return rv;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseSearchReplyMessage) ) {
            DatabaseSearchReplyMessage msg = (DatabaseSearchReplyMessage)object;
            return DataHelper.eq(getSearchKey(),msg.getSearchKey()) &&
            DataHelper.eq(getFromHash(),msg.getFromHash()) &&
            DataHelper.eq(_peerHashes,msg._peerHashes);
        } else {
            return false;
        }
    }
    
    public int hashCode() {
        return DataHelper.hashCode(getSearchKey()) +
        DataHelper.hashCode(getFromHash()) +
        DataHelper.hashCode(_peerHashes);
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
