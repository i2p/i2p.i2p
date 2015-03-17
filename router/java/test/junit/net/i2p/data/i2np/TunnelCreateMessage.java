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
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

/**
 * Defines the message sent to a router to request that it participate in a
 * tunnel using the included configuration settings.
 *
 */
public class TunnelCreateMessage extends I2NPMessageImpl {
    private Log _log;
    public final static int MESSAGE_TYPE = 6;
    private Hash _nextRouter;
    private TunnelId _nextTunnelId;
    private int _durationSeconds;
    private SessionKey _layerKey;
    private SessionKey _ivKey;
    private Properties _options;
    private Hash _replyGateway;
    private TunnelId _replyTunnel;
    private SessionTag _replyTag;
    private SessionKey _replyKey;
    private boolean _isGateway;
    private long _nonce;
    private Certificate _certificate;
    
    private byte[] _optionsCache;
    private byte[] _certificateCache;
    
    public static final long MAX_NONCE_VALUE = ((1l << 32l) - 1l);
    
    private static final Hash INVALID_HASH = new Hash(new byte[Hash.HASH_LENGTH]); // all 0s
    private static final TunnelId INVALID_TUNNEL = TunnelId.INVALID;

    public TunnelCreateMessage(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(TunnelCreateMessage.class);
    }
    
    public void setNextRouter(Hash routerIdentityHash) { _nextRouter = routerIdentityHash; }
    public Hash getNextRouter() { return _nextRouter; }
    public void setNextTunnelId(TunnelId id) { _nextTunnelId = id; }
    public TunnelId getNextTunnelId() { return _nextTunnelId; }
    public void setDurationSeconds(int seconds) { _durationSeconds = seconds; }
    public int getDurationSeconds() { return _durationSeconds; }
    public void setLayerKey(SessionKey key) { _layerKey = key; }
    public SessionKey getLayerKey() { return _layerKey; }
    public void setIVKey(SessionKey key) { _ivKey = key; }
    public SessionKey getIVKey() { return _ivKey; }
    public void setCertificate(Certificate cert) { _certificate = cert; }
    public Certificate getCertificate() { return _certificate; }
    public void setReplyTag(SessionTag tag) { _replyTag = tag; }
    public SessionTag getReplyTag() { return _replyTag; }
    public void setReplyKey(SessionKey key) { _replyKey = key; }
    public SessionKey getReplyKey() { return _replyKey; }
    public void setReplyTunnel(TunnelId id) { _replyTunnel = id; }
    public TunnelId getReplyTunnel() { return _replyTunnel; }
    public void setReplyGateway(Hash peer) { _replyGateway = peer; }
    public Hash getReplyGateway() { return _replyGateway; }
    public void setNonce(long nonce) { _nonce = nonce; }
    public long getNonce() { return _nonce; }
    public void setIsGateway(boolean isGateway) { _isGateway = isGateway; }
    public boolean getIsGateway() { return _isGateway; }
    public Properties getOptions() { return _options; }
    public void setOptions(Properties opts) { _options = opts; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        
        if (DataHelper.eq(INVALID_HASH.getData(), 0, data, offset, Hash.HASH_LENGTH)) {
            _nextRouter = null;
        } else {
            _nextRouter = new Hash(new byte[Hash.HASH_LENGTH]);
            System.arraycopy(data, offset, _nextRouter.getData(), 0, Hash.HASH_LENGTH);
        }
        offset += Hash.HASH_LENGTH;
        
        long id = DataHelper.fromLong(data, offset, 4);
        if (id > 0)
            _nextTunnelId = new TunnelId(id);
        offset += 4;
        
        _durationSeconds = (int)DataHelper.fromLong(data, offset, 2);
        offset += 2;
        
        _layerKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(data, offset, _layerKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        
        _ivKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(data, offset, _ivKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;

        try {
            Properties opts = new Properties();
            _options = opts;
            offset = DataHelper.fromProperties(data, offset, opts);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the options", dfe);
        }
        
        _replyGateway = new Hash(new byte[Hash.HASH_LENGTH]);
        System.arraycopy(data, offset, _replyGateway.getData(), 0, Hash.HASH_LENGTH);
        offset += Hash.HASH_LENGTH;
        
        _replyTunnel = new TunnelId(DataHelper.fromLong(data, offset, 4));
        offset += 4;
        
        _replyTag = new SessionTag(new byte[SessionTag.BYTE_LENGTH]);
        System.arraycopy(data, offset, _replyTag.getData(), 0, SessionTag.BYTE_LENGTH);
        offset += SessionTag.BYTE_LENGTH;
        
        _replyKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(data, offset, _replyKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        
        _nonce = DataHelper.fromLong(data, offset, 4);
        offset += 4;
        
        try {
            Certificate cert = new Certificate();
            _certificate = cert;
            offset += cert.readBytes(data, offset);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the certificate", dfe);
        }
        
        Boolean b = DataHelper.fromBoolean(data, offset);
        if (b == null)
            throw new I2NPMessageException("isGateway == unknown?!");
        _isGateway = b.booleanValue();
        offset += DataHelper.BOOLEAN_LENGTH;
    }
    
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        int length = 0;
        length += Hash.HASH_LENGTH; // nextRouter
        length += 4; // nextTunnel
        length += 2; // duration
        length += SessionKey.KEYSIZE_BYTES; // layerKey
        length += SessionKey.KEYSIZE_BYTES; // ivKey
        
        if (_optionsCache == null)
            _optionsCache = DataHelper.toProperties(_options);
        length += _optionsCache.length;
        
        length += Hash.HASH_LENGTH; // replyGateway
        length += 4; // replyTunnel
        length += SessionTag.BYTE_LENGTH; // replyTag
        length += SessionKey.KEYSIZE_BYTES; // replyKey
        length += 4; // nonce
        if (_certificateCache == null)
            _certificateCache = _certificate.toByteArray();
        length += _certificateCache.length;
        length += DataHelper.BOOLEAN_LENGTH;
        return length;
    }
    
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte data[], int offset) throws I2NPMessageException {
        if (_nextRouter == null)
            System.arraycopy(INVALID_HASH.getData(), 0, data, offset, Hash.HASH_LENGTH);
        else
            System.arraycopy(_nextRouter.getData(), 0, data, offset, Hash.HASH_LENGTH);
        offset += Hash.HASH_LENGTH;
        
        if (_nextTunnelId == null)
            DataHelper.toLong(data, offset, 4, 0);
        else
            DataHelper.toLong(data, offset, 4, _nextTunnelId.getTunnelId());
        offset += 4;
        
        DataHelper.toLong(data, offset, 2, _durationSeconds);
        offset += 2;
        
        System.arraycopy(_layerKey.getData(), 0, data, offset, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        
        System.arraycopy(_ivKey.getData(), 0, data, offset, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        
        if (_optionsCache == null)
            _optionsCache = DataHelper.toProperties(_options);
        System.arraycopy(_optionsCache, 0, data, offset, _optionsCache.length);
        offset += _optionsCache.length;
        
        System.arraycopy(_replyGateway.getData(), 0, data, offset, Hash.HASH_LENGTH);
        offset += Hash.HASH_LENGTH;
        
        DataHelper.toLong(data, offset, 4, _replyTunnel.getTunnelId());
        offset += 4;
        
        System.arraycopy(_replyTag.getData(), 0, data, offset, SessionTag.BYTE_LENGTH);
        offset += SessionTag.BYTE_LENGTH;
        
        System.arraycopy(_replyKey.getData(), 0, data, offset, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        
        DataHelper.toLong(data, offset, 4, _nonce);
        offset += 4;
        
        if (_certificateCache == null)
            _certificateCache = _certificate.toByteArray();
        System.arraycopy(_certificateCache, 0, data, offset, _certificateCache.length);
        offset += _certificateCache.length;
        
        DataHelper.toBoolean(data, offset, _isGateway);
        offset += DataHelper.BOOLEAN_LENGTH;
        
        return offset;
    }
    
    
    @Override
    public byte[] toByteArray() {
        byte rv[] = super.toByteArray();
        if (rv == null)
            throw new RuntimeException("unable to toByteArray(): " + toString());
        return rv;
    }

    @Override
    public int hashCode() {
        return DataHelper.hashCode(getNextRouter()) +
               DataHelper.hashCode(getNextTunnelId()) +
               DataHelper.hashCode(getReplyGateway()) +
               DataHelper.hashCode(getReplyTunnel());
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelCreateMessage) ) {
            TunnelCreateMessage msg = (TunnelCreateMessage)object;
            return DataHelper.eq(getNextRouter(), msg.getNextRouter()) &&
                   DataHelper.eq(getNextTunnelId(), msg.getNextTunnelId()) &&
                   DataHelper.eq(getReplyGateway(), msg.getReplyGateway()) &&
                   DataHelper.eq(getReplyTunnel(), msg.getReplyTunnel());
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[TunnelCreateMessage: ");
        buf.append("\n\tNext Router: ").append(getNextRouter());
        buf.append("\n\tNext Tunnel: ").append(getNextTunnelId());
        buf.append("\n\tReply Tunnel: ").append(getReplyTunnel());
        buf.append("\n\tReply Peer: ").append(getReplyGateway());
        buf.append("]");
        return buf.toString();
    }

    public int getType() { return MESSAGE_TYPE; }    
}
