package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Defines the message a router sends to another router in response to a
 * search (DatabaseFindNearest or DatabaseLookup) when it doesn't have the value,
 * specifying what routers it would search.
 *
 * @author jrandom
 */
public class DatabaseSearchReplyMessage extends FastI2NPMessageImpl {
    public final static int MESSAGE_TYPE = 3;
    private Hash _key;
    private List<Hash> _peerHashes;
    private Hash _from;
    
    public DatabaseSearchReplyMessage(I2PAppContext context) {
        super(context);
        // do this in netdb if we need it
        //_context.statManager().createRateStat("netDb.searchReplyMessageSend", "How many search reply messages we send", "NetworkDatabase", new long[] { 60*1000, 5*60*1000, 10*60*1000, 60*60*1000 });
        //_context.statManager().createRateStat("netDb.searchReplyMessageReceive", "How many search reply messages we receive", "NetworkDatabase", new long[] { 60*1000, 5*60*1000, 10*60*1000, 60*60*1000 });
        _peerHashes = new ArrayList(3);
    }
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }

    /**
     * @throws IllegalStateException if key previously set, to protect saved checksum
     */
    public void setSearchKey(Hash key) {
        if (_key != null)
            throw new IllegalStateException();
        _key = key;
    }
    
    public int getNumReplies() { return _peerHashes.size(); }
    public Hash getReply(int index) { return _peerHashes.get(index); }
    public void addReply(Hash peer) { _peerHashes.add(peer); }
    //public void addReplies(Collection replies) { _peerHashes.addAll(replies); }
    
    public Hash getFromHash() { return _from; }
    public void setFromHash(Hash from) { _from = from; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        //byte keyData[] = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(data, curIndex, keyData, 0, Hash.HASH_LENGTH);
        _key = Hash.create(data, curIndex);
        curIndex += Hash.HASH_LENGTH;
        //_key = new Hash(keyData);
        
        int num = (int)DataHelper.fromLong(data, curIndex, 1);
        curIndex++;
        
        _peerHashes.clear();
        for (int i = 0; i < num; i++) {
            //byte peer[] = new byte[Hash.HASH_LENGTH];
            //System.arraycopy(data, curIndex, peer, 0, Hash.HASH_LENGTH);
            Hash p = Hash.create(data, curIndex);
            curIndex += Hash.HASH_LENGTH;
            addReply(p);
        }
            
        //byte from[] = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(data, curIndex, from, 0, Hash.HASH_LENGTH);
        _from = Hash.create(data, curIndex);
        curIndex += Hash.HASH_LENGTH;
        //_from = new Hash(from);

        //_context.statManager().addRateData("netDb.searchReplyMessageReceive", num*32 + 64, 1);
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        return Hash.HASH_LENGTH + 1 + getNumReplies()*Hash.HASH_LENGTH + Hash.HASH_LENGTH;
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if (_key == null)
            throw new I2NPMessageException("Key in reply to not specified");
        if (_peerHashes == null)
            throw new I2NPMessageException("Peer replies are null");
        if (_from == null)
            throw new I2NPMessageException("No 'from' address specified!");

        System.arraycopy(_key.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        byte len[] = DataHelper.toLong(1, _peerHashes.size());
        out[curIndex++] = len[0];
        for (int i = 0; i < getNumReplies(); i++) {
            System.arraycopy(getReply(i).getData(), 0, out, curIndex, Hash.HASH_LENGTH);
            curIndex += Hash.HASH_LENGTH;
        }
        System.arraycopy(_from.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseSearchReplyMessage) ) {
            DatabaseSearchReplyMessage msg = (DatabaseSearchReplyMessage)object;
            return DataHelper.eq(_key,msg._key) &&
            DataHelper.eq(_from,msg._from) &&
            DataHelper.eq(_peerHashes,msg._peerHashes);
        } else {
            return false;
        }
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_key) +
        DataHelper.hashCode(_from) +
        DataHelper.hashCode(_peerHashes);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DatabaseSearchReplyMessage: ");
        buf.append("\n\tSearch Key: ").append(_key);
        buf.append("\n\tReplies: # = ").append(getNumReplies());
        for (int i = 0; i < getNumReplies(); i++) {
            buf.append("\n\t\tReply [").append(i).append("]: ").append(getReply(i));
        }
        buf.append("\n\tFrom: ").append(_from);
        buf.append("]");
        return buf.toString();
    }
}
