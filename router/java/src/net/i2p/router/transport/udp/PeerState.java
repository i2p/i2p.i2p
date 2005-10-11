package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.net.InetAddress;
import java.net.UnknownHostException;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Contain all of the state about a UDP connection to a peer.
 *
 */
public class PeerState {
    private I2PAppContext _context;
    private Log _log;
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    private Hash _remotePeer;
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    private SessionKey _currentMACKey;
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    private SessionKey _currentCipherKey;
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    private SessionKey _nextMACKey;
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    private SessionKey _nextCipherKey;
    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    private byte[] _nextKeyingMaterial;
    /** true if we began the current rekeying, false otherwise */
    private boolean _rekeyBeganLocally;
    /** when were the current cipher and MAC keys established/rekeyed? */
    private long _keyEstablishedTime;
    /** how far off is the remote peer from our clock, in seconds? */
    private short _clockSkew;
    /** what is the current receive second, for congestion control? */
    private long _currentReceiveSecond;
    /** when did we last send them a packet? */
    private long _lastSendTime;
    /** when did we last receive a packet from them? */
    private long _lastReceiveTime;
    /** how many consecutive messages have we sent and not received an ACK to */
    private int _consecutiveFailedSends;
    /** when did we last have a failed send (beginning of period) */
    private long _lastFailedSendPeriod;
    /** list of messageIds (Long) that we have received but not yet sent */
    private List _currentACKs;
    /** when did we last send ACKs to the peer? */
    private volatile long _lastACKSend;
    /** when did we decide we need to ACK to this peer? */
    private volatile long _wantACKSendSince;
    /** have we received a packet with the ECN bit set in the current second? */
    private boolean _currentSecondECNReceived;
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    private boolean _remoteWantsPreviousACKs;
    /** how many bytes should we send to the peer in a second */
    private volatile int _sendWindowBytes;
    /** how many bytes can we send to the peer in the current second */
    private volatile int _sendWindowBytesRemaining;
    private long _lastSendRefill;
    private int _sendBps;
    private int _sendBytes;
    private int _receiveBps;
    private int _receiveBytes;
    private int _sendACKBps;
    private int _sendACKBytes;
    private int _receiveACKBps;
    private int _receiveACKBytes;
    private long _receivePeriodBegin;
    private volatile long _lastCongestionOccurred;
    /** 
     * when sendWindowBytes is below this, grow the window size quickly,
     * but after we reach it, grow it slowly
     *
     */
    private volatile int _slowStartThreshold;
    /** what IP is the peer sending and receiving packets on? */
    private byte[] _remoteIP;
    /** cached IP address */
    private transient InetAddress _remoteIPAddress;
    /** what port is the peer sending and receiving packets on? */
    private int _remotePort;
    /** cached RemoteHostId, used to find the peerState by remote info */
    private RemoteHostId _remoteHostId;
    /** if we need to contact them, do we need to talk to an introducer? */
    private boolean _remoteRequiresIntroduction;
    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     */
    private long _weRelayToThemAs;
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    private long _theyRelayToUsAs;
    /** what is the largest packet we can send to the peer? */
    private int _mtu;
    /** when did we last check the MTU? */
    private long _mtuLastChecked;
    /** current round trip time estimate */
    private volatile int _rtt;
    /** smoothed mean deviation in the rtt */
    private volatile int _rttDeviation;
    /** current retransmission timeout */
    private volatile int _rto;
    
    /** how many packets will be considered within the retransmission rate calculation */
    static final long RETRANSMISSION_PERIOD_WIDTH = 100;
    
    private long _messagesReceived;
    private long _messagesSent;
    private long _packetsTransmitted;
    /** how many packets were retransmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private long _packetsRetransmitted;
    /** how many packets were transmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private long _packetsPeriodTransmitted;
    private int _packetsPeriodRetransmitted;
    private int _packetRetransmissionRate;
    /** at what time did we last break off the retransmission counter period */
    private long _retransmissionPeriodStart;
    /** how many dup packets were received within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private long _packetsReceivedDuplicate;
    private long _packetsReceived;
    
    /** Message (Long) to InboundMessageState for active message */
    private Map _inboundMessages;
    
    private static final int DEFAULT_SEND_WINDOW_BYTES = 8*1024;
    private static final int MINIMUM_WINDOW_BYTES = DEFAULT_SEND_WINDOW_BYTES;
    private static final int MAX_SEND_WINDOW_BYTES = 1024*1024;
    /*
     * 596 gives us 588 IP byes, 568 UDP bytes, and with an SSU data message, 
     * 522 fragment bytes, which is enough to send a tunnel data message in 2 
     * packets. A tunnel data message sent over the wire is 1044 bytes, meaning 
     * we need 522 fragment bytes to fit it in 2 packets - add 46 for SSU, 20 
     * for UDP, and 8 for IP, giving us 596.  round up to mod 16, giving a total
     * of 608
     */
    private static final int DEFAULT_MTU = 608;//600; //1500;
    private static final int MIN_RTO = 500 + ACKSender.ACK_FREQUENCY;
    private static final int MAX_RTO = 5000; // 5000;
    
    public PeerState(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerState.class);
        _remotePeer = null;
        _currentMACKey = null;
        _currentCipherKey = null;
        _nextMACKey = null;
        _nextCipherKey = null;
        _nextKeyingMaterial = null;
        _rekeyBeganLocally = false;
        _keyEstablishedTime = -1;
        _clockSkew = 0;
        _currentReceiveSecond = -1;
        _lastSendTime = -1;
        _lastReceiveTime = -1;
        _currentACKs = new ArrayList(8);
        _currentSecondECNReceived = false;
        _remoteWantsPreviousACKs = false;
        _sendWindowBytes = DEFAULT_SEND_WINDOW_BYTES;
        _sendWindowBytesRemaining = DEFAULT_SEND_WINDOW_BYTES;
        _slowStartThreshold = MAX_SEND_WINDOW_BYTES/2;
        _lastSendRefill = _context.clock().now();
        _receivePeriodBegin = _lastSendRefill;
        _sendBps = 0;
        _sendBytes = 0;
        _receiveBps = 0;
        _lastCongestionOccurred = -1;
        _remoteIP = null;
        _remotePort = -1;
        _remoteRequiresIntroduction = false;
        _weRelayToThemAs = 0;
        _theyRelayToUsAs = 0;
        _mtu = DEFAULT_MTU;
        _mtuLastChecked = -1;
        _lastACKSend = -1;
        _rtt = 1000;
        _rttDeviation = _rtt;
        _rto = MAX_RTO;
        _messagesReceived = 0;
        _messagesSent = 0;
        _packetsTransmitted = 0;
        _packetsRetransmitted = 0;
        _packetRetransmissionRate = 0;
        _retransmissionPeriodStart = 0;
        _packetsReceived = 0;
        _packetsReceivedDuplicate = 0;
        _inboundMessages = new HashMap(8);
        _context.statManager().createRateStat("udp.congestionOccurred", "How large the cwin was when congestion occurred (duration == sendBps)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.congestedRTO", "retransmission timeout after congestion (duration == rtt dev)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendACKPartial", "Number of partial ACKs sent (duration == number of full ACKs in that ack packet)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendBps", "How fast we are transmitting when a packet is acked", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receiveBps", "How fast we are receiving when a packet is fully received (at most one per second)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    public Hash getRemotePeer() { return _remotePeer; }
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    public SessionKey getCurrentMACKey() { return _currentMACKey; }
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    public SessionKey getCurrentCipherKey() { return _currentCipherKey; }
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    public SessionKey getNextMACKey() { return _nextMACKey; }
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    public SessionKey getNextCipherKey() { return _nextCipherKey; }
    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    public byte[] getNextKeyingMaterial() { return _nextKeyingMaterial; }
    /** true if we began the current rekeying, false otherwise */
    public boolean getRekeyBeganLocally() { return _rekeyBeganLocally; }
    /** when were the current cipher and MAC keys established/rekeyed? */
    public long getKeyEstablishedTime() { return _keyEstablishedTime; }
    /** how far off is the remote peer from our clock, in seconds? */
    public short getClockSkew() { return _clockSkew; }
    /** what is the current receive second, for congestion control? */
    public long getCurrentReceiveSecond() { return _currentReceiveSecond; }
    /** when did we last send them a packet? */
    public long getLastSendTime() { return _lastSendTime; }
    /** when did we last receive a packet from them? */
    public long getLastReceiveTime() { return _lastReceiveTime; }
    /** how many seconds have we sent packets without any ACKs received? */
    public int getConsecutiveFailedSends() { return _consecutiveFailedSends; }
    /** have we received a packet with the ECN bit set in the current second? */
    public boolean getCurrentSecondECNReceived() { return _currentSecondECNReceived; }
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    public boolean getRemoteWantsPreviousACKs() { return _remoteWantsPreviousACKs; }
    /** how many bytes should we send to the peer in a second */
    public int getSendWindowBytes() { return _sendWindowBytes; }
    /** how many bytes can we send to the peer in the current second */
    public int getSendWindowBytesRemaining() { return _sendWindowBytesRemaining; }
    /** what IP is the peer sending and receiving packets on? */
    public byte[] getRemoteIP() { return _remoteIP; }
    public InetAddress getRemoteIPAddress() {
        if (_remoteIPAddress == null) {
            try {
                _remoteIPAddress = InetAddress.getByAddress(_remoteIP);
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid IP? ", uhe);
            }
        }
        return _remoteIPAddress;
    }
    /** what port is the peer sending and receiving packets on? */
    public int getRemotePort() { return _remotePort; }
    /** if we need to contact them, do we need to talk to an introducer? */
    public boolean getRemoteRequiresIntroduction() { return _remoteRequiresIntroduction; }
    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     */
    public long getWeRelayToThemAs() { return _weRelayToThemAs; }
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    public long getTheyRelayToUsAs() { return _theyRelayToUsAs; }
    /** what is the largest packet we can send to the peer? */
    public int getMTU() { return _mtu; }
    /** when did we last check the MTU? */
    public long getMTULastChecked() { return _mtuLastChecked; }
    
    
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    public void setRemotePeer(Hash peer) { _remotePeer = peer; }
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    public void setCurrentMACKey(SessionKey key) { _currentMACKey = key; }
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    public void setCurrentCipherKey(SessionKey key) { _currentCipherKey = key; }
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    public void setNextMACKey(SessionKey key) { _nextMACKey = key; }
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    public void setNextCipherKey(SessionKey key) { _nextCipherKey = key; }
    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    public void setNextKeyingMaterial(byte data[]) { _nextKeyingMaterial = data; }
    /** true if we began the current rekeying, false otherwise */
    public void setRekeyBeganLocally(boolean local) { _rekeyBeganLocally = local; }
    /** when were the current cipher and MAC keys established/rekeyed? */
    public void setKeyEstablishedTime(long when) { _keyEstablishedTime = when; }
    /** how far off is the remote peer from our clock, in seconds? */
    public void adjustClockSkew(short skew) { 
        _clockSkew = (short)(0.9*(float)_clockSkew + 0.1*(float)skew); 
    }
    /** what is the current receive second, for congestion control? */
    public void setCurrentReceiveSecond(long sec) { _currentReceiveSecond = sec; }
    /** when did we last send them a packet? */
    public void setLastSendTime(long when) { _lastSendTime = when; }
    /** when did we last receive a packet from them? */
    public void setLastReceiveTime(long when) { _lastReceiveTime = when; }
    /** return the smoothed send transfer rate */
    public int getSendBps() { return _sendBps; }
    public int getReceiveBps() { return _receiveBps; }
    public int incrementConsecutiveFailedSends() { 
        long now = _context.clock().now()/(10*1000);
        if (_lastFailedSendPeriod >= now) {
            // ignore... too fast
        } else {
            _lastFailedSendPeriod = now;
            _consecutiveFailedSends++; 
        }
        return _consecutiveFailedSends;
    }
    
    /** how fast we are sending *ack* packets */
    public int getSendACKBps() { return _sendACKBps; }
    public int getReceiveACKBps() { return _receiveACKBps; }
    
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    public void remoteDoesNotWantPreviousACKs() { _remoteWantsPreviousACKs = false; }
    /** 
     * Decrement the remaining bytes in the current period's window,
     * returning true if the full size can be decremented, false if it
     * cannot.  If it is not decremented, the window size remaining is 
     * not adjusted at all.
     */
    public boolean allocateSendingBytes(int size) { return allocateSendingBytes(size, false); }
    public boolean allocateSendingBytes(int size, boolean isForACK) { 
        long now = _context.clock().now();
        long duration = now - _lastSendRefill;
        if (duration >= 1000) {
            _sendWindowBytesRemaining = _sendWindowBytes;
            _sendBytes += size;
            _sendBps = (int)(0.9f*(float)_sendBps + 0.1f*((float)_sendBytes * (1000f/(float)duration)));
            if (isForACK) {
                _sendACKBytes += size;
                _sendACKBps = (int)(0.9f*(float)_sendACKBps + 0.1f*((float)_sendACKBytes * (1000f/(float)duration)));
            }
            _sendBytes = 0;
            _sendACKBytes = 0;
            _lastSendRefill = now;
        }
        //if (true) return true;
        if (size <= _sendWindowBytesRemaining) {
            _sendWindowBytesRemaining -= size; 
            _sendBytes += size;
            _lastSendTime = now;
            if (isForACK) 
                _sendACKBytes += size;
            return true;
        } else {
            return false;
        }
    }
    /** what IP+port is the peer sending and receiving packets on? */
    public void setRemoteAddress(byte ip[], int port) { 
        _remoteIP = ip;
        _remoteIPAddress = null;
        _remotePort = port; 
        _remoteHostId = new RemoteHostId(ip, port);
    }
    /** if we need to contact them, do we need to talk to an introducer? */
    public void setRemoteRequiresIntroduction(boolean required) { _remoteRequiresIntroduction = required; }
    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     */
    public void setWeRelayToThemAs(long tag) { _weRelayToThemAs = tag; }
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    public void setTheyRelayToUsAs(long tag) { _theyRelayToUsAs = tag; }
    /** what is the largest packet we can send to the peer? */
    public void setMTU(int mtu) { 
        _mtu = mtu; 
        _mtuLastChecked = _context.clock().now();
    }
    public int getSlowStartThreshold() { return _slowStartThreshold; }
    
    /** we received the message specified completely */
    public void messageFullyReceived(Long messageId, int bytes) { messageFullyReceived(messageId, bytes, false); }
    public void messageFullyReceived(Long messageId, int bytes, boolean isForACK) {
        if (bytes > 0) {
            _receiveBytes += bytes;
            if (isForACK)
                _receiveACKBytes += bytes;
        } else {
            if (true || _retransmissionPeriodStart + 1000 < _context.clock().now()) {
                _packetsReceivedDuplicate++;
            } else {
                _retransmissionPeriodStart = _context.clock().now();
                _packetsReceivedDuplicate = 1;
            }
        }
        
        long now = _context.clock().now();
        long duration = now - _receivePeriodBegin;
        if (duration >= 1000) {
            _receiveBps = (int)(0.9f*(float)_receiveBps + 0.1f*((float)_receiveBytes * (1000f/(float)duration)));
            if (isForACK)
                _receiveACKBps = (int)(0.9f*(float)_receiveACKBps + 0.1f*((float)_receiveACKBytes * (1000f/(float)duration)));
            _receiveACKBytes = 0;
            _receiveBytes = 0;
            _receivePeriodBegin = now;
           _context.statManager().addRateData("udp.receiveBps", _receiveBps, 0);
        }
        
        synchronized (_currentACKs) {
            if (_wantACKSendSince <= 0)
                _wantACKSendSince = now;
            if (!_currentACKs.contains(messageId))
                _currentACKs.add(messageId);
        }
        _messagesReceived++;
    }
    
    public void messagePartiallyReceived() {
        if (_wantACKSendSince <= 0)
            _wantACKSendSince = _context.clock().now();
    }
    
    /** 
     * Fetch the internal id (Long) to InboundMessageState for incomplete inbound messages.
     * Access to this map must be synchronized explicitly!
     */
    public Map getInboundMessages() { return _inboundMessages; }
    
    /** 
     * either they told us to back off, or we had to resend to get 
     * the data through.  
     *
     */
    private boolean congestionOccurred() {
        long now = _context.clock().now();
        if (_lastCongestionOccurred + _rto > now)
            return false; // only shrink once every few seconds
        _lastCongestionOccurred = now;
        
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes, _sendBps);
        
        int congestionAt = _sendWindowBytes;
        //if (true)
        //    _sendWindowBytes -= 10000;
        //else
            _sendWindowBytes = _sendWindowBytes/2; //(_sendWindowBytes*2) / 3;
        if (_sendWindowBytes < MINIMUM_WINDOW_BYTES)
            _sendWindowBytes = MINIMUM_WINDOW_BYTES;
        //if (congestionAt/2 < _slowStartThreshold)
            _slowStartThreshold = congestionAt/2;
        return true;
    }
    
    /**
     * Grab a list of message ids (Long) that we want to send to the remote
     * peer, regardless of the packet size, but don't remove it from our 
     * "want to send" list.  If the message id is transmitted to the peer,
     * removeACKMessage(Long) should be called.
     *
     */
    public List getCurrentFullACKs() {
        synchronized (_currentACKs) {
            return new ArrayList(_currentACKs);
        }
    }
    public void removeACKMessage(Long messageId) {
        synchronized (_currentACKs) {
            _currentACKs.remove(messageId);
        }
        _lastACKSend = _context.clock().now();
    }
    
    /** 
     * grab a list of ACKBitfield instances, some of which may fully 
     * ACK a message while others may only partially ACK a message.  
     * the values returned are limited in size so that they will fit within
     * the peer's current MTU as an ACK - as such, not all messages may be
     * ACKed with this call.  Be sure to check getWantedACKSendSince() which
     * will be unchanged if there are ACKs remaining.
     *
     */
    public List retrieveACKBitfields() {
        List rv = null;
        int bytesRemaining = countMaxACKData();
        synchronized (_currentACKs) {
            rv = new ArrayList(_currentACKs.size());
            while ( (bytesRemaining >= 4) && (_currentACKs.size() > 0) ) {
                long id = ((Long)_currentACKs.remove(0)).longValue();
                rv.add(new FullACKBitfield(id));
                bytesRemaining -= 4;
            }
            if (_currentACKs.size() <= 0)
                _wantACKSendSince = -1;
        }
            
        int partialIncluded = 0;
        if (bytesRemaining > 4) {
            // ok, there's room to *try* to fit in some partial ACKs, so
            // we should try to find some packets to partially ACK 
            // (preferably the ones which have the most received fragments)
            List partial = new ArrayList();
            fetchPartialACKs(partial);
            // we may not be able to use them all, but lets try...
            for (int i = 0; (bytesRemaining > 4) && (i < partial.size()); i++) {
                ACKBitfield bitfield = (ACKBitfield)partial.get(i);
                int bytes = (bitfield.fragmentCount() / 7) + 1;
                if (bytesRemaining > bytes + 4) { // msgId + bitfields
                    if (rv == null)
                        rv = new ArrayList(partial.size());
                    rv.add(bitfield);
                    bytesRemaining -= bytes + 4;
                    partialIncluded++;
                } else {
                    // continue on to another partial, in case there's a 
                    // smaller one that will fit
                }
            }
        }

        _lastACKSend = _context.clock().now();
        if (rv == null)
            rv = Collections.EMPTY_LIST;
        if (partialIncluded > 0)
            _context.statManager().addRateData("udp.sendACKPartial", partialIncluded, rv.size() - partialIncluded);
        return rv;
    }
    
    void fetchPartialACKs(List rv) {
        InboundMessageState states[] = null;
        int curState = 0;
        synchronized (_inboundMessages) {
            int numMessages = _inboundMessages.size();
            if (numMessages <= 0) 
                return;
            for (Iterator iter = _inboundMessages.values().iterator(); iter.hasNext(); ) {
                InboundMessageState state = (InboundMessageState)iter.next();
                if (state.isExpired()) {
                    iter.remove();
                } else {
                    if (!state.isComplete()) {
                        if (states == null)
                            states = new InboundMessageState[numMessages];
                        states[curState++] = state;
                    }
                }
            }
        }
        if (states != null) {
            for (int i = curState-1; i >= 0; i--) {
                if (states[i] != null)
                    rv.add(states[i].createACKBitfield());
            }
        }
    }
    
    /** represent a full ACK of a message */
    private static class FullACKBitfield implements ACKBitfield {
        private long _msgId;
        public FullACKBitfield(long id) { _msgId = id; }
        public int fragmentCount() { return 0; }
        public long getMessageId() { return _msgId; }
        public boolean received(int fragmentNum) { return true; }
        public boolean receivedComplete() { return true; }
        public String toString() { return "Full ACK of " + _msgId; }
    }
        
    /** we sent a message which was ACKed containing the given # of bytes */
    public void messageACKed(int bytesACKed, long lifetime, int numSends) {
        _consecutiveFailedSends = 0;
        _lastFailedSendPeriod = -1;
        if (numSends < 2) {
            if (_sendWindowBytes <= _slowStartThreshold) {
                _sendWindowBytes += bytesACKed;
            } else {
                if (false) {
                    _sendWindowBytes += 16; // why 16?
                } else {
                    float prob = ((float)bytesACKed) / ((float)(_sendWindowBytes<<1));
                    float v = _context.random().nextFloat();
                    if (v < 0) v = 0-v;
                    if (v <= prob)
                        _sendWindowBytes += bytesACKed; //512; // bytesACKed;
                }
            }
        }
        if (_sendWindowBytes > MAX_SEND_WINDOW_BYTES)
            _sendWindowBytes = MAX_SEND_WINDOW_BYTES;
        _lastReceiveTime = _context.clock().now();
        
        if (true) {
            if (_sendWindowBytesRemaining + bytesACKed <= _sendWindowBytes)
                _sendWindowBytesRemaining += bytesACKed;
            else
                _sendWindowBytesRemaining = _sendWindowBytes;
        }
        
        _messagesSent++;
        if (numSends < 2)
            recalculateTimeouts(lifetime);
        else
            _log.warn("acked after numSends=" + numSends + " w/ lifetime=" + lifetime + " and size=" + bytesACKed);
        
        _context.statManager().addRateData("udp.sendBps", _sendBps, lifetime);
    }

    /** adjust the tcp-esque timeouts */
    private void recalculateTimeouts(long lifetime) {
        _rttDeviation = _rttDeviation + (int)(0.25d*(Math.abs(lifetime-_rtt)-_rttDeviation));
        
        // the faster we are going, the slower we want to reduce the rtt
        float scale = 0.1f;
        if (_sendBps > 0)
            scale = ((float)lifetime) / (float)((float)lifetime + (float)_sendBps);
        if (scale < 0.001f) scale = 0.001f;
        
        _rtt = (int)(((float)_rtt)*(1.0f-scale) + (scale)*(float)lifetime);
        _rto = _rtt + (_rttDeviation<<2);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Recalculating timeouts w/ lifetime=" + lifetime + ": rtt=" + _rtt
                       + " rttDev=" + _rttDeviation + " rto=" + _rto);
        if (_rto < minRTO())
            _rto = minRTO();
        if (_rto > MAX_RTO)
            _rto = MAX_RTO;
    }
    
    /** we are resending a packet, so lets jack up the rto */
    public void messageRetransmitted(int packets) { 
        long now = _context.clock().now();
        if (true || _retransmissionPeriodStart + 1000 <= now) {
            _packetsRetransmitted += packets;
        } else {
            _packetRetransmissionRate = (int)((float)(0.9f*_packetRetransmissionRate) + (float)(0.1f*_packetsRetransmitted));
            //_packetsPeriodTransmitted = _packetsTransmitted - _retransmissionPeriodStart;
            _packetsPeriodRetransmitted = (int)_packetsRetransmitted;
            _retransmissionPeriodStart = now;
            _packetsRetransmitted = packets;
        }
        congestionOccurred();
        _context.statManager().addRateData("udp.congestedRTO", _rto, _rttDeviation);
        //_rto *= 2; 
    }
    public void packetsTransmitted(int packets) { 
        long now = _context.clock().now();
        _packetsTransmitted += packets; 
        //_packetsPeriodTransmitted += packets;
        if (false && _retransmissionPeriodStart + 1000 <= now) {
            _packetRetransmissionRate = (int)((float)(0.9f*_packetRetransmissionRate) + (float)(0.1f*_packetsRetransmitted));
            _retransmissionPeriodStart = 0;
            _packetsPeriodRetransmitted = (int)_packetsRetransmitted;
            _packetsRetransmitted = 0;
        }
    }
    /** how long does it usually take to get a message ACKed? */
    public int getRTT() { return _rtt; }
    /** how soon should we retransmit an unacked packet? */
    public int getRTO() { return _rto; }
    /** how skewed are the measured RTTs? */
    public long getRTTDeviation() { return _rttDeviation; }
    
    public long getMessagesSent() { return _messagesSent; }
    public long getMessagesReceived() { return _messagesReceived; }
    public long getPacketsTransmitted() { return _packetsTransmitted; }
    public long getPacketsRetransmitted() { return _packetsRetransmitted; }
    public long getPacketsPeriodTransmitted() { return _packetsPeriodTransmitted; }
    public int getPacketsPeriodRetransmitted() { return _packetsPeriodRetransmitted; }
    /** avg number of packets retransmitted for every 100 packets */
    public long getPacketRetransmissionRate() { return _packetRetransmissionRate; }
    public long getPacketsReceived() { return _packetsReceived; }
    public long getPacketsReceivedDuplicate() { return _packetsReceivedDuplicate; }
    public void packetReceived() { _packetsReceived++; }
    
    /** 
     * we received a backoff request, so cut our send window
     */
    public void ECNReceived() {
        congestionOccurred();
        _currentSecondECNReceived = true;
        _lastReceiveTime = _context.clock().now();
    }
    
    public void dataReceived() {
        _lastReceiveTime = _context.clock().now();
    }
    
    /** when did we last send an ACK to the peer? */
    public long getLastACKSend() { return _lastACKSend; }
    public void setLastACKSend(long when) { _lastACKSend = when; }
    public long getWantedACKSendSince() { return _wantACKSendSince; }
    public boolean unsentACKThresholdReached() {
        int threshold = countMaxACKData() / 4;
        synchronized (_currentACKs) {
            return _currentACKs.size() >= threshold;
        }
    }
    private int countMaxACKData() {
        return _mtu 
                - OutboundMessageFragments.IP_HEADER_SIZE
                - OutboundMessageFragments.UDP_HEADER_SIZE
                - UDPPacket.IV_SIZE 
                - UDPPacket.MAC_SIZE
                - 1 // type flag
                - 4 // timestamp
                - 1 // data flag
                - 1 // # ACKs
                - 16; // padding safety
    }

    private int minRTO() {
        if (_packetRetransmissionRate < 10)
            return MIN_RTO;
        else if (_packetRetransmissionRate < 50)
            return 2*MIN_RTO;
        else
            return MAX_RTO;
    }
    
    public RemoteHostId getRemoteHostId() { return _remoteHostId; }
    
    public int hashCode() {
        if (_remotePeer != null) 
            return _remotePeer.hashCode();
        else 
            return super.hashCode();
    }
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o instanceof PeerState) {
            PeerState s = (PeerState)o;
            if (_remotePeer == null)
                return o == this;
            else
                return _remotePeer.equals(s.getRemotePeer());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(64);
        buf.append(_remoteHostId.toString());
        if (_remotePeer != null)
            buf.append(" ").append(_remotePeer.toBase64().substring(0,6));
        return buf.toString();
    }
}
