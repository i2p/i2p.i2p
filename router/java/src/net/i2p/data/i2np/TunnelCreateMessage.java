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
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

/**
 * Defines the message sent to a router to request that it participate in a
 * tunnel using the included configuration settings.
 *
 * @author jrandom
 */
public class TunnelCreateMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(TunnelCreateMessage.class);
    public final static int MESSAGE_TYPE = 6;
    private int _participantType;
    private TunnelId _tunnelId;
    private Hash _nextRouter;
    private TunnelId _nextTunnelId;
    private long _tunnelDuration;
    private TunnelConfigurationSessionKey _configKey;
    private long _maxPeakMessagesPerMin;
    private long _maxAvgMessagesPerMin;
    private long _maxPeakBytesPerMin;
    private long _maxAvgBytesPerMin;
    private boolean _includeDummyTraffic;
    private boolean _reorderMessages;
    private TunnelSigningPublicKey _verificationPubKey;
    private TunnelSigningPrivateKey _verificationPrivKey;
    private TunnelSessionKey _tunnelKey;
    private Certificate _certificate;
    private SessionTag _replyTag;
    private SessionKey _replyKey;
    private TunnelId _replyTunnel;
    private Hash _replyPeer;
    
    private byte[] _certificateCache;
    
    public static final int PARTICIPANT_TYPE_GATEWAY = 1;
    public static final int PARTICIPANT_TYPE_ENDPOINT = 2;
    public static final int PARTICIPANT_TYPE_OTHER = 3;
    
    private final static long FLAG_DUMMY = 1 << 7;
    private final static long FLAG_REORDER = 1 << 6;
    
    public TunnelCreateMessage(I2PAppContext context) {
        super(context);
        setParticipantType(-1);
        setNextRouter(null);
        setNextTunnelId(null);
        setTunnelId(null);
        setTunnelDurationSeconds(-1);
        setConfigurationKey(null);
        setMaxPeakMessagesPerMin(-1);
        setMaxAvgMessagesPerMin(-1);
        setMaxPeakBytesPerMin(-1);
        setMaxAvgBytesPerMin(-1);
        setIncludeDummyTraffic(false);
        setReorderMessages(false);
        setVerificationPublicKey(null);
        setVerificationPrivateKey(null);
        setTunnelKey(null);
        setCertificate(null);
        setReplyTag(null);
        setReplyKey(null);
        setReplyTunnel(null);
        setReplyPeer(null);
    }
    
    public void setParticipantType(int type) { _participantType = type; }
    public int getParticipantType() { return _participantType; }
    public void setNextRouter(Hash routerIdentityHash) { _nextRouter = routerIdentityHash; }
    public Hash getNextRouter() { return _nextRouter; }
    public void setNextTunnelId(TunnelId id) { _nextTunnelId = id; }
    public TunnelId getNextTunnelId() { return _nextTunnelId; }
    public void setTunnelId(TunnelId id) { _tunnelId = id; }
    public TunnelId getTunnelId() { return _tunnelId; }
    public void setTunnelDurationSeconds(long durationSeconds) { _tunnelDuration = durationSeconds; }
    public long getTunnelDurationSeconds() { return _tunnelDuration; }
    public void setConfigurationKey(TunnelConfigurationSessionKey key) { _configKey = key; }
    public TunnelConfigurationSessionKey getConfigurationKey() { return _configKey; }
    public void setMaxPeakMessagesPerMin(long msgs) { _maxPeakMessagesPerMin = msgs; }
    public long getMaxPeakMessagesPerMin() { return _maxPeakMessagesPerMin; }
    public void setMaxAvgMessagesPerMin(long msgs) { _maxAvgMessagesPerMin = msgs; }
    public long getMaxAvgMessagesPerMin() { return _maxAvgMessagesPerMin; }
    public void setMaxPeakBytesPerMin(long bytes) { _maxPeakBytesPerMin = bytes; }
    public long getMaxPeakBytesPerMin() { return _maxPeakBytesPerMin; }
    public void setMaxAvgBytesPerMin(long bytes) { _maxAvgBytesPerMin = bytes; }
    public long getMaxAvgBytesPerMin() { return _maxAvgBytesPerMin; }
    public void setIncludeDummyTraffic(boolean include) { _includeDummyTraffic = include; }
    public boolean getIncludeDummyTraffic() { return _includeDummyTraffic; }
    public void setReorderMessages(boolean reorder) { _reorderMessages = reorder; }
    public boolean getReorderMessages() { return _reorderMessages; }
    public void setVerificationPublicKey(TunnelSigningPublicKey key) { _verificationPubKey = key; }
    public TunnelSigningPublicKey getVerificationPublicKey() { return _verificationPubKey; }
    public void setVerificationPrivateKey(TunnelSigningPrivateKey key) { _verificationPrivKey = key; }
    public TunnelSigningPrivateKey getVerificationPrivateKey() { return _verificationPrivKey; }
    public void setTunnelKey(TunnelSessionKey key) { _tunnelKey = key; }
    public TunnelSessionKey getTunnelKey() { return _tunnelKey; }
    public void setCertificate(Certificate cert) { _certificate = cert; }
    public Certificate getCertificate() { return _certificate; }
    public void setReplyTag(SessionTag tag) { _replyTag = tag; }
    public SessionTag getReplyTag() { return _replyTag; }
    public void setReplyKey(SessionKey key) { _replyKey = key; }
    public SessionKey getReplyKey() { return _replyKey; }
    public void setReplyTunnel(TunnelId id) { _replyTunnel = id; }
    public TunnelId getReplyTunnel() { return _replyTunnel; }
    public void setReplyPeer(Hash peer) { _replyPeer = peer; }
    public Hash getReplyPeer() { return _replyPeer; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            _participantType = (int)DataHelper.readLong(in, 1);
            if (_participantType != PARTICIPANT_TYPE_ENDPOINT) {
                _nextRouter = new Hash();
                _nextRouter.readBytes(in);
                _nextTunnelId = new TunnelId();
                _nextTunnelId.readBytes(in);
            }
            _tunnelId = new TunnelId();
            _tunnelId.readBytes(in);
            _tunnelDuration = DataHelper.readLong(in, 4);
            _configKey = new TunnelConfigurationSessionKey();
            _configKey.readBytes(in);
            _maxPeakMessagesPerMin = DataHelper.readLong(in, 4);
            _maxAvgMessagesPerMin = DataHelper.readLong(in, 4);
            _maxPeakBytesPerMin = DataHelper.readLong(in, 4);
            _maxAvgBytesPerMin = DataHelper.readLong(in, 4);
            
            int flags = (int)DataHelper.readLong(in, 1);
            _includeDummyTraffic = flagsIncludeDummy(flags);
            _reorderMessages = flagsReorder(flags);
            
            _verificationPubKey = new TunnelSigningPublicKey();
            _verificationPubKey.readBytes(in);
            if (_participantType == PARTICIPANT_TYPE_GATEWAY) {
                _verificationPrivKey = new TunnelSigningPrivateKey();
                _verificationPrivKey.readBytes(in);
            }
            if ( (_participantType == PARTICIPANT_TYPE_ENDPOINT) || (_participantType == PARTICIPANT_TYPE_GATEWAY) ) {
                _tunnelKey = new TunnelSessionKey();
                _tunnelKey.readBytes(in);
            }
            _certificate = new Certificate();
            _certificate.readBytes(in);
            _replyTag = new SessionTag();
            _replyTag.readBytes(in);
            _replyKey = new SessionKey();
            _replyKey.readBytes(in);
            _replyTunnel = new TunnelId();
            _replyTunnel.readBytes(in);
            _replyPeer = new Hash();
            _replyPeer.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        int length = 0;
        length += 1; // participantType
        if (_participantType != PARTICIPANT_TYPE_ENDPOINT) {
            length += Hash.HASH_LENGTH;
            length += 4; // nextTunnelId
        }
        length += 4; // tunnelId
        length += 4; // duration;
        length += SessionKey.KEYSIZE_BYTES; 
        length += 4*4; // max limits
        length += 1; // flags
        length += SigningPublicKey.KEYSIZE_BYTES; 
        if (_participantType == PARTICIPANT_TYPE_GATEWAY)
            length += SigningPrivateKey.KEYSIZE_BYTES;        
        if ( (_participantType == PARTICIPANT_TYPE_ENDPOINT) 
             || (_participantType == PARTICIPANT_TYPE_GATEWAY) )
            length += SessionKey.KEYSIZE_BYTES;
        _certificateCache = _certificate.toByteArray();
        length += _certificateCache.length;
        length += SessionTag.BYTE_LENGTH;
        length += SessionKey.KEYSIZE_BYTES;
        length += 4; // replyTunnel
        length += Hash.HASH_LENGTH; // replyPeer
        return length;
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        byte type[] = DataHelper.toLong(1, _participantType);
        out[curIndex++] = type[0];
        if (_participantType != PARTICIPANT_TYPE_ENDPOINT) {
            System.arraycopy(_nextRouter.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
            curIndex += Hash.HASH_LENGTH;
            byte id[] = DataHelper.toLong(4, _nextTunnelId.getTunnelId());
            System.arraycopy(id, 0, out, curIndex, 4);
            curIndex += 4;
        }
        byte id[] = DataHelper.toLong(4, _tunnelId.getTunnelId());
        System.arraycopy(id, 0, out, curIndex, 4);
        curIndex += 4;
        byte duration[] = DataHelper.toLong(4, _tunnelDuration);
        System.arraycopy(duration, 0, out, curIndex, 4);
        curIndex += 4;
        System.arraycopy(_configKey.getKey().getData(), 0, out, curIndex, SessionKey.KEYSIZE_BYTES);
        curIndex += SessionKey.KEYSIZE_BYTES;
        
        byte val[] = DataHelper.toLong(4, _maxPeakMessagesPerMin);
        System.arraycopy(val, 0, out, curIndex, 4);
        curIndex += 4;
        val = DataHelper.toLong(4, _maxAvgMessagesPerMin);
        System.arraycopy(val, 0, out, curIndex, 4);
        curIndex += 4;
        val = DataHelper.toLong(4, _maxPeakBytesPerMin);
        System.arraycopy(val, 0, out, curIndex, 4);
        curIndex += 4;
        val = DataHelper.toLong(4, _maxAvgBytesPerMin);
        System.arraycopy(val, 0, out, curIndex, 4);
        curIndex += 4;
            
        long flags = getFlags();
        byte flag[] = DataHelper.toLong(1, flags);
        out[curIndex++] = flag[0];
            
        System.arraycopy(_verificationPubKey.getKey().getData(), 0, out, curIndex, SigningPublicKey.KEYSIZE_BYTES);
        curIndex += SigningPublicKey.KEYSIZE_BYTES;
        
        if (_participantType == PARTICIPANT_TYPE_GATEWAY) {
            System.arraycopy(_verificationPrivKey.getKey().getData(), 0, out, curIndex, SigningPrivateKey.KEYSIZE_BYTES);
            curIndex += SigningPrivateKey.KEYSIZE_BYTES;
        }
        
        if ( (_participantType == PARTICIPANT_TYPE_ENDPOINT) || (_participantType == PARTICIPANT_TYPE_GATEWAY) ) {
            System.arraycopy(_tunnelKey.getKey().getData(), 0, out, curIndex, SessionKey.KEYSIZE_BYTES);
            curIndex += SessionKey.KEYSIZE_BYTES;
        }
        System.arraycopy(_certificateCache, 0, out, curIndex, _certificateCache.length);
        curIndex += _certificateCache.length;
        System.arraycopy(_replyTag.getData(), 0, out, curIndex, SessionTag.BYTE_LENGTH);
        curIndex += SessionTag.BYTE_LENGTH;
        System.arraycopy(_replyKey.getData(), 0, out, curIndex, SessionKey.KEYSIZE_BYTES);
        curIndex += SessionKey.KEYSIZE_BYTES;
        id = DataHelper.toLong(4, _replyTunnel.getTunnelId());
        System.arraycopy(id, 0, out, curIndex, 4);
        curIndex += 4;
        System.arraycopy(_replyPeer.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        return curIndex;
    }
    
    private boolean flagsIncludeDummy(long flags) {
        return (0 != (flags & FLAG_DUMMY));
    }
    private boolean flagsReorder(long flags) {
        return (0 != (flags & FLAG_REORDER));
    }
    
    private long getFlags() {
        long val = 0L;
        if (getIncludeDummyTraffic())
            val = val | FLAG_DUMMY;
        if (getReorderMessages())
            val = val | FLAG_REORDER;
        return val;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return (int)(DataHelper.hashCode(getCertificate()) +
                    DataHelper.hashCode(getConfigurationKey()) +
                    DataHelper.hashCode(getNextRouter()) +
                    DataHelper.hashCode(getNextTunnelId()) +
                    DataHelper.hashCode(getReplyPeer()) +
                    DataHelper.hashCode(getReplyTunnel()) +
                    DataHelper.hashCode(getTunnelId()) +
                    DataHelper.hashCode(getTunnelKey()) +
                    DataHelper.hashCode(getVerificationPrivateKey()) +
                    DataHelper.hashCode(getVerificationPublicKey()) +
                    (getIncludeDummyTraffic() ? 1 : 0) +
                    getMaxAvgBytesPerMin() +
                    getMaxAvgMessagesPerMin() +
                    getMaxPeakBytesPerMin() +
                    getMaxPeakMessagesPerMin() +
                    getParticipantType() +
                    (getReorderMessages() ? 1 : 0) +
                    getTunnelDurationSeconds());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelCreateMessage) ) {
            TunnelCreateMessage msg = (TunnelCreateMessage)object;
            return DataHelper.eq(getCertificate(), msg.getCertificate()) &&
            DataHelper.eq(getConfigurationKey(), msg.getConfigurationKey()) &&
            DataHelper.eq(getNextRouter(),  msg.getNextRouter()) &&
            DataHelper.eq(getNextTunnelId(),  msg.getNextTunnelId()) &&
            DataHelper.eq(getReplyTag(), msg.getReplyTag()) &&
            DataHelper.eq(getReplyKey(), msg.getReplyKey()) &&
            DataHelper.eq(getReplyTunnel(), msg.getReplyTunnel()) &&
            DataHelper.eq(getReplyPeer(), msg.getReplyPeer()) &&
            DataHelper.eq(getTunnelId(), msg.getTunnelId()) &&
            DataHelper.eq(getTunnelKey(), msg.getTunnelKey()) &&
            DataHelper.eq(getVerificationPrivateKey(), msg.getVerificationPrivateKey()) &&
            DataHelper.eq(getVerificationPublicKey(), msg.getVerificationPublicKey()) &&
            (getIncludeDummyTraffic() == msg.getIncludeDummyTraffic()) &&
            (getMaxAvgBytesPerMin() == msg.getMaxAvgBytesPerMin()) &&
            (getMaxAvgMessagesPerMin() == msg.getMaxAvgMessagesPerMin()) &&
            (getMaxPeakBytesPerMin() == msg.getMaxPeakBytesPerMin()) &&
            (getMaxPeakMessagesPerMin() == msg.getMaxPeakMessagesPerMin()) &&
            (getParticipantType() == msg.getParticipantType()) &&
            (getReorderMessages() == msg.getReorderMessages()) &&
            (getTunnelDurationSeconds() == msg.getTunnelDurationSeconds());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[TunnelCreateMessage: ");
        buf.append("\n\tParticipant Type: ").append(getParticipantType());
        buf.append("\n\tCertificate: ").append(getCertificate());
        buf.append("\n\tConfiguration Key: ").append(getConfigurationKey());
        buf.append("\n\tNext Router: ").append(getNextRouter());
        buf.append("\n\tNext Tunnel: ").append(getNextTunnelId());
        buf.append("\n\tReply Tag: ").append(getReplyTag());
        buf.append("\n\tReply Key: ").append(getReplyKey());
        buf.append("\n\tReply Tunnel: ").append(getReplyTunnel());
        buf.append("\n\tReply Peer: ").append(getReplyPeer());
        buf.append("\n\tTunnel ID: ").append(getTunnelId());
        buf.append("\n\tTunnel Key: ").append(getTunnelKey());
        buf.append("\n\tVerification Private Key: ").append(getVerificationPrivateKey());
        buf.append("\n\tVerification Public Key: ").append(getVerificationPublicKey());
        buf.append("\n\tInclude Dummy Traffic: ").append(getIncludeDummyTraffic());
        buf.append("\n\tMax Avg Bytes / Minute: ").append(getMaxAvgBytesPerMin());
        buf.append("\n\tMax Peak Bytes / Minute: ").append(getMaxPeakBytesPerMin());
        buf.append("\n\tMax Avg Messages / Minute: ").append(getMaxAvgMessagesPerMin());
        buf.append("\n\tMax Peak Messages / Minute: ").append(getMaxPeakMessagesPerMin());
        buf.append("\n\tReorder Messages: ").append(getReorderMessages());
        buf.append("\n\tTunnel Duration (seconds): ").append(getTunnelDurationSeconds());
        buf.append("]");
        return buf.toString();
    }
    
}
