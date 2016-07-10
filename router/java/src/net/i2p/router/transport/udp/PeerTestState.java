package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.SessionKey;

/**
 *  Track the state of a peer test.
 *  Used only by PeerTestManager.
 */
class PeerTestState {
    private final long _testNonce;
    private final Role _ourRole;
    private final boolean _isIPv6;
    private InetAddress _aliceIP;
    private int _alicePort;
    private InetAddress _bobIP;
    private int _bobPort;
    private InetAddress _charlieIP;
    private int _charliePort;
    private InetAddress _aliceIPFromCharlie;
    private int _alicePortFromCharlie;
    private SessionKey _aliceIntroKey;
    private SessionKey _charlieIntroKey;
    private SessionKey _bobCipherKey;
    private SessionKey _bobMACKey;
    private final long _beginTime;
    private long _lastSendTime;
    private long _receiveAliceTime;
    private long _receiveBobTime;
    private long _receiveCharlieTime;
    private final AtomicInteger _packetsRelayed = new AtomicInteger();
    
    public enum Role {ALICE, BOB, CHARLIE};
    
    public PeerTestState(Role role, boolean isIPv6, long nonce, long now) {
        _ourRole = role;
        _isIPv6 = isIPv6;
        _testNonce = nonce;
        _beginTime = now;
    }

    public long getNonce() { return _testNonce; }

    /** Are we Alice, bob, or Charlie. */
    public Role getOurRole() { return _ourRole; }

    /**
     * Is this an IPv6 test?
     * @since 0.9.27
     */
    public boolean isIPv6() { return _isIPv6; }

    /**
     * If we are Alice, this will contain the IP that Bob says we
     * can be reached at - the IP Charlie says we can be reached 
     * at is _aliceIPFromCharlie
     *
     */
    public InetAddress getAliceIP() { return _aliceIP; }
    public void setAliceIP(InetAddress ip) { _aliceIP = ip; }
    public InetAddress getBobIP() { return _bobIP; }
    public void setBobIP(InetAddress ip) { _bobIP = ip; }
    public InetAddress getCharlieIP() { return _charlieIP; }
    public void setCharlieIP(InetAddress ip) { _charlieIP = ip; }
    public InetAddress getAliceIPFromCharlie() { return _aliceIPFromCharlie; }
    public void setAliceIPFromCharlie(InetAddress ip) { _aliceIPFromCharlie = ip; }
    /**
     * If we are Alice, this will contain the port that Bob says we
     * can be reached at - the port Charlie says we can be reached
     * at is _alicePortFromCharlie
     *
     */
    public int getAlicePort() { return _alicePort; }
    public void setAlicePort(int alicePort) { _alicePort = alicePort; }
    public int getBobPort() { return _bobPort; }
    public void setBobPort(int bobPort) { _bobPort = bobPort; }
    public int getCharliePort() { return _charliePort; }
    public void setCharliePort(int charliePort) { _charliePort = charliePort; }
    
    public int getAlicePortFromCharlie() { return _alicePortFromCharlie; }
    public void setAlicePortFromCharlie(int alicePortFromCharlie) { _alicePortFromCharlie = alicePortFromCharlie; }
    
    public SessionKey getAliceIntroKey() { return _aliceIntroKey; }
    public void setAliceIntroKey(SessionKey key) { _aliceIntroKey = key; }
    public SessionKey getCharlieIntroKey() { return _charlieIntroKey; }
    public void setCharlieIntroKey(SessionKey key) { _charlieIntroKey = key; }
    public SessionKey getBobCipherKey() { return _bobCipherKey; }
    public void setBobCipherKey(SessionKey key) { _bobCipherKey = key; }
    public SessionKey getBobMACKey() { return _bobMACKey; }
    public void setBobMACKey(SessionKey key) { _bobMACKey = key; }
    
    /** when did this test begin? */
    public long getBeginTime() { return _beginTime; }

    /** when did we last send out a packet? */
    public long getLastSendTime() { return _lastSendTime; }
    public void setLastSendTime(long when) { _lastSendTime = when; }

    /**
     * when did we last hear from alice?
     */
    public long getReceiveAliceTime() { return _receiveAliceTime; }
    public void setReceiveAliceTime(long when) { _receiveAliceTime = when; }

    /** when did we last hear from bob? */
    public long getReceiveBobTime() { return _receiveBobTime; }
    public void setReceiveBobTime(long when) { _receiveBobTime = when; }

    /** when did we last hear from charlie? */
    public long getReceiveCharlieTime() { return _receiveCharlieTime; }
    public void setReceiveCharlieTime(long when) { _receiveCharlieTime = when; }
    
    /** @return new value */
    public int incrementPacketsRelayed() { return _packetsRelayed.incrementAndGet(); }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("PeerTest ").append(_testNonce)
           .append(" as ").append(_ourRole.toString());
        if (_aliceIP != null)
            buf.append("; Alice: ").append(_aliceIP).append(':').append(_alicePort);
        if (_aliceIPFromCharlie != null)
            buf.append(" (fromCharlie ").append(_aliceIPFromCharlie).append(':').append(_alicePortFromCharlie).append(')');
        if (_bobIP != null)
            buf.append("; Bob: ").append(_bobIP).append(':').append(_bobPort);
        if (_charlieIP != null)
            buf.append(" Charlie: ").append(_charlieIP).append(':').append(_charliePort);
        buf.append("; last send after ").append(_lastSendTime - _beginTime);
        if (_receiveAliceTime > 0)
            buf.append("; rcvd from Alice after ").append(_receiveAliceTime - _beginTime);
        if (_receiveBobTime > 0)
            buf.append("; rcvd from Bob after ").append(_receiveBobTime - _beginTime);
        if (_receiveCharlieTime > 0)
            buf.append("; rcvd from Charlie after ").append(_receiveCharlieTime - _beginTime);
        buf.append("; pkts relayed: ").append(_packetsRelayed.get());
        return buf.toString();
    }
}
