package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;

import java.net.InetAddress;
import java.net.UnknownHostException;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Contain all of the state about a UDP connection to a peer
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
    /** how many seconds have we sent packets without any ACKs received? */
    private int _consecutiveSendingSecondsWithoutACKs;
    /** list of messageIds (Long) that we have received but not yet sent */
    private List _currentACKs;
    /** when did we last send ACKs to the peer? */
    private long _lastACKSend;
    /** have we received a packet with the ECN bit set in the current second? */
    private boolean _currentSecondECNReceived;
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    private boolean _remoteWantsPreviousACKs;
    /** how many bytes should we send to the peer in a second */
    private int _sendWindowBytes;
    /** how many bytes can we send to the peer in the current second */
    private int _sendWindowBytesRemaining;
    private long _lastSendRefill;
    private long _lastCongestionOccurred;
    /** 
     * when sendWindowBytes is below this, grow the window size quickly,
     * but after we reach it, grow it slowly
     *
     */
    private int _slowStartThreshold;
    /** what IP is the peer sending and receiving packets on? */
    private byte[] _remoteIP;
    /** cached IP address */
    private transient InetAddress _remoteIPAddress;
    /** what port is the peer sending and receiving packets on? */
    private int _remotePort;
    /** cached remoteIP + port, used to find the peerState by remote info */
    private String _remoteHostString;
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
    
    private long _messagesReceived;
    private long _messagesSent;
    
    private static final int DEFAULT_SEND_WINDOW_BYTES = 16*1024;
    private static final int MINIMUM_WINDOW_BYTES = DEFAULT_SEND_WINDOW_BYTES;
    private static final int MAX_SEND_WINDOW_BYTES = 1024*1024;
    private static final int DEFAULT_MTU = 1024;
    
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
        _clockSkew = Short.MIN_VALUE;
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
        _lastCongestionOccurred = -1;
        _remoteIP = null;
        _remotePort = -1;
        _remoteRequiresIntroduction = false;
        _weRelayToThemAs = 0;
        _theyRelayToUsAs = 0;
        _mtu = DEFAULT_MTU;
        _mtuLastChecked = -1;
        _lastACKSend = -1;
        _messagesReceived = 0;
        _messagesSent = 0;
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
    public int getConsecutiveSendingSecondsWithoutACKS() { return _consecutiveSendingSecondsWithoutACKs; }
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
    public void setClockSkew(short skew) { _clockSkew = skew; }
    /** what is the current receive second, for congestion control? */
    public void setCurrentReceiveSecond(long sec) { _currentReceiveSecond = sec; }
    /** when did we last send them a packet? */
    public void setLastSendTime(long when) { _lastSendTime = when; }
    /** when did we last receive a packet from them? */
    public void setLastReceiveTime(long when) { _lastReceiveTime = when; }
    public void incrementConsecutiveSendingSecondsWithoutACKS() { _consecutiveSendingSecondsWithoutACKs++; }
    public void resetConsecutiveSendingSecondsWithoutACKS() { _consecutiveSendingSecondsWithoutACKs = 0; }
    
    /*
    public void migrateACKs(List NACKs, long newSecond) {
        _previousSecondACKs = _currentSecondACKs;
        if (_currentSecondECNReceived)
            _sendWindowBytes /= 2;
        if (_sendWindowBytes < MINIMUM_WINDOW_BYTES)
            _sendWindowBytes = MINIMUM_WINDOW_BYTES;
        _sendWindowBytesRemaining = _sendWindowBytes;
        _currentSecondECNReceived = false;
        _remoteWantsPreviousACKs = true;
        _currentReceiveSecond = newSecond;
    }
     */
    
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
    public boolean allocateSendingBytes(int size) { 
        long now = _context.clock().now();
        if (_lastSendRefill + 1000 <= now) {
            _sendWindowBytesRemaining = _sendWindowBytes;
            _lastSendRefill = now;
        }
        if (size <= _sendWindowBytesRemaining) {
            _sendWindowBytesRemaining -= size; 
            _lastSendTime = now;
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
        _remoteHostString = calculateRemoteHostString(ip, port);
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
    public void messageFullyReceived(Long messageId) {
        synchronized (_currentACKs) {
            if (!_currentACKs.contains(messageId))
                _currentACKs.add(messageId);
        }
        _messagesReceived++;
    }
    
    /** 
     * either they told us to back off, or we had to resend to get 
     * the data through.  
     *
     */
    public void congestionOccurred() {
        long now = _context.clock().now();
        if (_lastCongestionOccurred + 2000 > now)
            return; // only shrink once every other second
        _lastCongestionOccurred = now;
        
        _sendWindowBytes /= 2;
        if (_sendWindowBytes < MINIMUM_WINDOW_BYTES)
            _sendWindowBytes = MINIMUM_WINDOW_BYTES;
        if (_sendWindowBytes < _slowStartThreshold)
            _slowStartThreshold = _sendWindowBytes;
    }
    
    /** pull off the ACKs (Long) to send to the peer */
    public List retrieveACKs() {
        List rv = null;
        synchronized (_currentACKs) {
            rv = new ArrayList(_currentACKs);
            _currentACKs.clear();
        }
        return rv;
    }
    
    /** we sent a message which was ACKed containing the given # of bytes */
    public void messageACKed(int bytesACKed) {
        _consecutiveSendingSecondsWithoutACKs = 0;
        if (_sendWindowBytes <= _slowStartThreshold) {
            _sendWindowBytes += bytesACKed;
        } else {
            double prob = ((double)bytesACKed) / ((double)_sendWindowBytes);
            if (_context.random().nextDouble() <= prob)
                _sendWindowBytes += bytesACKed;
        }
        if (_sendWindowBytes > MAX_SEND_WINDOW_BYTES)
            _sendWindowBytes = MAX_SEND_WINDOW_BYTES;
        _lastReceiveTime = _context.clock().now();
        _messagesSent++;
    }
    
    public long getMessagesSent() { return _messagesSent; }
    public long getMessagesReceived() { return _messagesReceived; }
    
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
    
    public String getRemoteHostString() { return _remoteHostString; }

    public static String calculateRemoteHostString(byte ip[], int port) {
        StringBuffer buf = new StringBuffer(ip.length * 4 + 5);
        for (int i = 0; i < ip.length; i++)
            buf.append((int)ip[i]).append('.');
        buf.append(port);
        return buf.toString();
    }
    
    public static String calculateRemoteHostString(UDPPacket packet) {
        InetAddress remAddr = packet.getPacket().getAddress();
        int remPort = packet.getPacket().getPort();
        return calculateRemoteHostString(remAddr.getAddress(), remPort);
    }
    
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
        buf.append(_remoteHostString);
        if (_remotePeer != null)
            buf.append(" ").append(_remotePeer.toBase64().substring(0,6));
        return buf.toString();
    }
}
