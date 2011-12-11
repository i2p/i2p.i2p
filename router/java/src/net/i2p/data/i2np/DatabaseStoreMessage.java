package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;

/**
 * Defines the message a router sends to another router to test the network
 * database reachability, as well as the reply message sent back.
 *
 * TODO: Don't decompress and recompress RouterInfos at the OBEP and IBGW.
 * Could this even change the message length or corrupt things?
 *
 * @author jrandom
 */
public class DatabaseStoreMessage extends FastI2NPMessageImpl {
    public final static int MESSAGE_TYPE = 1;
    private Hash _key;
    private DatabaseEntry _dbEntry;
    private byte[] _byteCache;
    private long _replyToken;
    private TunnelId _replyTunnel;
    private Hash _replyGateway;
    
    public DatabaseStoreMessage(I2PAppContext context) {
        super(context);
    }
    
    /**
     * Defines the key in the network database being stored
     *
     */
    public Hash getKey() {
        if (_key != null)
            return _key;   // receive
        if (_dbEntry != null)
            return _dbEntry.getHash();   // create
        return null;
    }
    
    /**
     * Defines the entry in the network database being stored
     */
    public DatabaseEntry getEntry() { return _dbEntry; }

    /**
     * This also sets the key
     * @throws IllegalStateException if data previously set, to protect saved checksum
     */
    public void setEntry(DatabaseEntry entry) {
        if (_dbEntry != null)
            throw new IllegalStateException();
        _dbEntry = entry;
    }
    
    /**
     * If a reply is desired, this token specifies the message ID that should
     * be used for a DeliveryStatusMessage to be sent to the reply tunnel on the
     * reply gateway.  
     *
     * @return positive reply token ID, or 0 if no reply is necessary.
     */
    public long getReplyToken() { return _replyToken; }

    /**
     * Update the reply token.
     *
     * @throws IllegalArgumentException if the token is out of range (min=0, max=I2NPMessage.MAX_ID_VALUE)
     */
    public void setReplyToken(long token) throws IllegalArgumentException { 
        if (token > I2NPMessage.MAX_ID_VALUE)
            throw new IllegalArgumentException("Token too large: " + token + " (max=" + I2NPMessage.MAX_ID_VALUE + ")");
        else if (token < 0) 
            throw new IllegalArgumentException("Token too small: " + token);
        _replyToken = token; 
    }
    
    public TunnelId getReplyTunnel() { return _replyTunnel; } 
    public void setReplyTunnel(TunnelId id) { _replyTunnel = id; }
    
    public Hash getReplyGateway() { return _replyGateway; }
    public void setReplyGateway(Hash peer) { _replyGateway = peer; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _key = Hash.create(data, curIndex);
        curIndex += Hash.HASH_LENGTH;
        
        type = (int)DataHelper.fromLong(data, curIndex, 1);
        curIndex++;
        
        _replyToken = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        
        if (_replyToken > 0) {
            long tunnel = DataHelper.fromLong(data, curIndex, 4);
            if (tunnel > 0)
                _replyTunnel = new TunnelId(tunnel);
            curIndex += 4;
            
            _replyGateway = Hash.create(data, curIndex);
            curIndex += Hash.HASH_LENGTH;
        } else {
            _replyTunnel = null;
            _replyGateway = null;
        }
        
        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
            _dbEntry = new LeaseSet();
            try {
                _dbEntry.readBytes(new ByteArrayInputStream(data, curIndex, data.length-curIndex));
            } catch (DataFormatException dfe) {
                throw new I2NPMessageException("Error reading the leaseSet", dfe);
            } catch (IOException ioe) {
                throw new I2NPMessageException("Error reading the leaseSet", ioe);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            _dbEntry = new RouterInfo();
            int compressedSize = (int)DataHelper.fromLong(data, curIndex, 2);
            curIndex += 2;
            if (compressedSize <= 0 || curIndex + compressedSize > data.length || curIndex + compressedSize > dataSize + offset)
                throw new I2NPMessageException("Compressed RI length: " + compressedSize +
                                               " but remaining bytes: " + Math.min(data.length - curIndex, dataSize + offset -curIndex));
            
            try {
                // TODO we could delay decompression, just copy to a new byte array and store in _byteCache
                // May not be necessary since the IBGW now uses UnknownI2NPMessage.
                // DSMs at the OBEP are generally garlic wrapped, so the OBEP won't see it.
                // If we do delay it, getEntry() will have to check if _dbEntry is null and _byteCache
                // is non-null, and then decompress.
                byte decompressed[] = DataHelper.decompress(data, curIndex, compressedSize);
                _dbEntry.readBytes(new ByteArrayInputStream(decompressed));
            } catch (DataFormatException dfe) {
                throw new I2NPMessageException("Error reading the routerInfo", dfe);
            } catch (IOException ioe) {
                throw new I2NPMessageException("Corrupt compressed routerInfo size = " + compressedSize, ioe);
            }
        } else {
            throw new I2NPMessageException("Invalid type of key read from the structure - " + type);
        }
        //if (!key.equals(_dbEntry.getHash()))
        //    throw new I2NPMessageException("Hash mismatch in DSM");
    }
    
    
    /**
     *  calculate the message body's length (not including the header and footer)
     *
     *  @throws IllegalStateException
     */
    protected int calculateWrittenLength() { 
        // TODO if _byteCache is non-null, don't check _dbEntry
        if (_dbEntry == null)
            throw new IllegalStateException("Missing entry");
        int len = Hash.HASH_LENGTH + 1 + 4; // key+type+replyToken
        if (_replyToken > 0) 
            len += 4 + Hash.HASH_LENGTH; // replyTunnel+replyGateway
        int type = _dbEntry.getType();
        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
            if (_byteCache == null) {
                _byteCache = _dbEntry.toByteArray();
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            // only decompress once
            if (_byteCache == null) {
                byte uncompressed[] = _dbEntry.toByteArray();
                _byteCache = DataHelper.compress(uncompressed);
            }
            len += 2;
        } else {
            throw new IllegalStateException("Invalid key type " + type);
        }
        len += _byteCache.length;
        return len;
    }

    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if (_dbEntry == null) throw new I2NPMessageException("Missing entry");
        int type = _dbEntry.getType();
        if (type != DatabaseEntry.KEY_TYPE_LEASESET && type != DatabaseEntry.KEY_TYPE_ROUTERINFO)
            throw new I2NPMessageException("Invalid key type " + type);
        
        // Use the hash of the DatabaseEntry
        System.arraycopy(getKey().getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        out[curIndex++] = (byte) type;
        byte tok[] = DataHelper.toLong(4, _replyToken);
        System.arraycopy(tok, 0, out, curIndex, 4);
        curIndex += 4;
        
        if (_replyToken > 0) {
            long replyTunnel = 0;
            if (_replyTunnel != null)
                replyTunnel = _replyTunnel.getTunnelId();
            byte id[] = DataHelper.toLong(4, replyTunnel);
            System.arraycopy(id, 0, out, curIndex, 4);
            curIndex += 4;
            System.arraycopy(_replyGateway.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
            curIndex += Hash.HASH_LENGTH;
        }
        
        // _byteCache initialized in calculateWrittenLength
        if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            byte len[] = DataHelper.toLong(2, _byteCache.length);
            out[curIndex++] = len[0];
            out[curIndex++] = len[1];
        }
        System.arraycopy(_byteCache, 0, out, curIndex, _byteCache.length);
        curIndex += _byteCache.length;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getKey()) +
               DataHelper.hashCode(_dbEntry) +
               (int) _replyToken +
               DataHelper.hashCode(_replyTunnel) +
               DataHelper.hashCode(_replyGateway);
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseStoreMessage) ) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)object;
            return DataHelper.eq(getKey(),msg.getKey()) &&
                   DataHelper.eq(_dbEntry,msg.getEntry()) &&
                   _replyToken == msg._replyToken &&
                   DataHelper.eq(_replyTunnel, msg._replyTunnel) &&
                   DataHelper.eq(_replyGateway, msg._replyGateway);
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DatabaseStoreMessage: ");
        buf.append("\n\tExpiration: ").append(_expiration);
        buf.append("\n\tUnique ID: ").append(_uniqueId);
        buf.append("\n\tKey: ").append(getKey());
        buf.append("\n\tEntry: ").append(_dbEntry);
        buf.append("\n\tReply token: ").append(_replyToken);
        buf.append("\n\tReply tunnel: ").append(_replyTunnel);
        buf.append("\n\tReply gateway: ").append(_replyGateway);
        buf.append("]");
        return buf.toString();
    }
}
