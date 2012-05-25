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
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

/**
 * Defines the message a router sends to another router to test the network
 * database reachability, as well as the reply message sent back.
 *
 * @author jrandom
 */
public class DatabaseStoreMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DatabaseStoreMessage.class);
    public final static int MESSAGE_TYPE = 1;
    private Hash _key;
    private int _type;
    private LeaseSet _leaseSet;
    private RouterInfo _info;
    private byte[] _leaseSetCache;
    private byte[] _routerInfoCache;
    private long _replyToken;
    private TunnelId _replyTunnel;
    private Hash _replyGateway;
    
    public final static int KEY_TYPE_ROUTERINFO = 0;
    public final static int KEY_TYPE_LEASESET = 1;
    
    public DatabaseStoreMessage(I2PAppContext context) {
        super(context);
        setValueType(-1);
        setKey(null);
        setLeaseSet(null);
        setRouterInfo(null);
        setReplyToken(0);
        setReplyTunnel(null);
        setReplyGateway(null);
    }
    
    /**
     * Defines the key in the network database being stored
     *
     */
    public Hash getKey() { return _key; }
    public void setKey(Hash key) { _key = key; }
    
    /**
     * Defines the router info value in the network database being stored
     *
     */
    public RouterInfo getRouterInfo() { return _info; }
    public void setRouterInfo(RouterInfo routerInfo) {
        _info = routerInfo;
        if (_info != null)
            setValueType(KEY_TYPE_ROUTERINFO);
    }
    
    /**
     * Defines the lease set value in the network database being stored
     *
     */
    public LeaseSet getLeaseSet() { return _leaseSet; }
    public void setLeaseSet(LeaseSet leaseSet) {
        _leaseSet = leaseSet;
        if (_leaseSet != null)
            setValueType(KEY_TYPE_LEASESET);
    }
    
    /**
     * Defines type of key being stored in the network database -
     * either KEY_TYPE_ROUTERINFO or KEY_TYPE_LEASESET
     *
     */
    public int getValueType() { return _type; }
    public void setValueType(int type) { _type = type; }
    
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
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        byte keyData[] = new byte[Hash.HASH_LENGTH];
        System.arraycopy(data, curIndex, keyData, 0, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        _key = new Hash(keyData);
        
        _type = (int)DataHelper.fromLong(data, curIndex, 1);
        curIndex++;
        
        _replyToken = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        
        if (_replyToken > 0) {
            long tunnel = DataHelper.fromLong(data, curIndex, 4);
            if (tunnel > 0)
                _replyTunnel = new TunnelId(tunnel);
            curIndex += 4;
            
            byte gw[] = new byte[Hash.HASH_LENGTH];
            System.arraycopy(data, curIndex, gw, 0, Hash.HASH_LENGTH);
            curIndex += Hash.HASH_LENGTH;
            _replyGateway = new Hash(gw);
        } else {
            _replyTunnel = null;
            _replyGateway = null;
        }
        
        if (_type == KEY_TYPE_LEASESET) {
            _leaseSet = new LeaseSet();
            try {
                _leaseSet.readBytes(new ByteArrayInputStream(data, curIndex, data.length-curIndex));
            } catch (DataFormatException dfe) {
                throw new I2NPMessageException("Error reading the leaseSet", dfe);
            }
        } else if (_type == KEY_TYPE_ROUTERINFO) {
            _info = new RouterInfo();
            int compressedSize = (int)DataHelper.fromLong(data, curIndex, 2);
            curIndex += 2;
            
            try {
                byte decompressed[] = DataHelper.decompress(data, curIndex, compressedSize);
                _info.readBytes(new ByteArrayInputStream(decompressed));
            } catch (DataFormatException dfe) {
                throw new I2NPMessageException("Error reading the routerInfo", dfe);
            } catch (IOException ioe) {
                throw new I2NPMessageException("Compressed routerInfo was corrupt", ioe);
            }
        } else {
            throw new I2NPMessageException("Invalid type of key read from the structure - " + _type);
        }
    }
    
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        int len = Hash.HASH_LENGTH + 1 + 4; // key+type+replyToken
        if (_replyToken > 0) 
            len += 4 + Hash.HASH_LENGTH; // replyTunnel+replyGateway
        if (_type == KEY_TYPE_LEASESET) {
            _leaseSetCache = _leaseSet.toByteArray();
            len += _leaseSetCache.length;
        } else if (_type == KEY_TYPE_ROUTERINFO) {
            byte uncompressed[] = _info.toByteArray();
            byte compressed[] = DataHelper.compress(uncompressed);
            _routerInfoCache = compressed;
            len += compressed.length + 2;
        }
        return len;
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if (_key == null) throw new I2NPMessageException("Invalid key");
        if ( (_type != KEY_TYPE_LEASESET) && (_type != KEY_TYPE_ROUTERINFO) ) throw new I2NPMessageException("Invalid key type");
        if ( (_type == KEY_TYPE_LEASESET) && (_leaseSet == null) ) throw new I2NPMessageException("Missing lease set");
        if ( (_type == KEY_TYPE_ROUTERINFO) && (_info == null) ) throw new I2NPMessageException("Missing router info");
        
        System.arraycopy(_key.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        byte type[] = DataHelper.toLong(1, _type);
        out[curIndex++] = type[0];
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
        
        if (_type == KEY_TYPE_LEASESET) {
            // initialized in calculateWrittenLength
            System.arraycopy(_leaseSetCache, 0, out, curIndex, _leaseSetCache.length);
            curIndex += _leaseSetCache.length;
        } else if (_type == KEY_TYPE_ROUTERINFO) {
            byte len[] = DataHelper.toLong(2, _routerInfoCache.length);
            out[curIndex++] = len[0];
            out[curIndex++] = len[1];
            System.arraycopy(_routerInfoCache, 0, out, curIndex, _routerInfoCache.length);
            curIndex += _routerInfoCache.length;
        }
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getKey()) +
               DataHelper.hashCode(getLeaseSet()) +
               DataHelper.hashCode(getRouterInfo()) +
               getValueType() +
               (int)getReplyToken() +
               DataHelper.hashCode(getReplyTunnel()) +
               DataHelper.hashCode(getReplyGateway());
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseStoreMessage) ) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)object;
            return DataHelper.eq(getKey(),msg.getKey()) &&
                   DataHelper.eq(getLeaseSet(),msg.getLeaseSet()) &&
                   DataHelper.eq(getRouterInfo(),msg.getRouterInfo()) &&
                   DataHelper.eq(getValueType(),msg.getValueType()) &&
                   getReplyToken() == msg.getReplyToken() &&
                   DataHelper.eq(getReplyTunnel(), msg.getReplyTunnel()) &&
                   DataHelper.eq(getReplyGateway(), msg.getReplyGateway());
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DatabaseStoreMessage: ");
        buf.append("\n\tExpiration: ").append(getMessageExpiration());
        buf.append("\n\tUnique ID: ").append(getUniqueId());
        buf.append("\n\tKey: ").append(getKey());
        buf.append("\n\tValue Type: ").append(getValueType());
        buf.append("\n\tRouter Info: ").append(getRouterInfo());
        buf.append("\n\tLease Set: ").append(getLeaseSet());
        buf.append("\n\tReply token: ").append(getReplyToken());
        buf.append("\n\tReply tunnel: ").append(getReplyTunnel());
        buf.append("\n\tReply gateway: ").append(getReplyGateway());
        buf.append("]");
        return buf.toString();
    }
}
