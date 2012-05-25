package net.i2p.router.transport.udp;

import java.net.InetAddress;

import net.i2p.data.SessionKey;

/**
 *
 */
class PeerTestState {
    private long _testNonce;
    private short _ourRole;
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
    private long _beginTime;
    private long _lastSendTime;
    private long _receiveAliceTime;
    private long _receiveBobTime;
    private long _receiveCharlieTime;
    private int _packetsRelayed;
    
    public static final short ALICE = 1;
    public static final short BOB = 2;
    public static final short CHARLIE = 3;
    
    public synchronized long getNonce() { return _testNonce; }
    public synchronized void setNonce(long nonce) { _testNonce = nonce; }
    /** Are we Alice, bob, or Charlie. */
    public synchronized short getOurRole() { return _ourRole; }
    public synchronized void setOurRole(short role) { _ourRole = role; }
    /**
     * If we are Alice, this will contain the IP that Bob says we
     * can be reached at - the IP Charlie says we can be reached 
     * at is _aliceIPFromCharlie
     *
     */
    public synchronized InetAddress getAliceIP() { return _aliceIP; }
    public synchronized void setAliceIP(InetAddress ip) { _aliceIP = ip; }
    public synchronized InetAddress getBobIP() { return _bobIP; }
    public synchronized void setBobIP(InetAddress ip) { _bobIP = ip; }
    public synchronized InetAddress getCharlieIP() { return _charlieIP; }
    public synchronized void setCharlieIP(InetAddress ip) { _charlieIP = ip; }
    public synchronized InetAddress getAliceIPFromCharlie() { return _aliceIPFromCharlie; }
    public synchronized void setAliceIPFromCharlie(InetAddress ip) { _aliceIPFromCharlie = ip; }
    /**
     * If we are Alice, this will contain the port that Bob says we
     * can be reached at - the port Charlie says we can be reached
     * at is _alicePortFromCharlie
     *
     */
    public synchronized int getAlicePort() { return _alicePort; }
    public synchronized void setAlicePort(int alicePort) { _alicePort = alicePort; }
    public synchronized int getBobPort() { return _bobPort; }
    public synchronized void setBobPort(int bobPort) { _bobPort = bobPort; }
    public synchronized int getCharliePort() { return _charliePort; }
    public synchronized void setCharliePort(int charliePort) { _charliePort = charliePort; }
    
    public synchronized int getAlicePortFromCharlie() { return _alicePortFromCharlie; }
    public synchronized void setAlicePortFromCharlie(int alicePortFromCharlie) { _alicePortFromCharlie = alicePortFromCharlie; }
    
    public synchronized SessionKey getAliceIntroKey() { return _aliceIntroKey; }
    public synchronized void setAliceIntroKey(SessionKey key) { _aliceIntroKey = key; }
    public synchronized SessionKey getCharlieIntroKey() { return _charlieIntroKey; }
    public synchronized void setCharlieIntroKey(SessionKey key) { _charlieIntroKey = key; }
    public synchronized SessionKey getBobCipherKey() { return _bobCipherKey; }
    public synchronized void setBobCipherKey(SessionKey key) { _bobCipherKey = key; }
    public synchronized SessionKey getBobMACKey() { return _bobMACKey; }
    public synchronized void setBobMACKey(SessionKey key) { _bobMACKey = key; }
    
    /** when did this test begin? */
    public synchronized long getBeginTime() { return _beginTime; }
    public synchronized void setBeginTime(long when) { _beginTime = when; }
    /** when did we last send out a packet? */
    public synchronized long getLastSendTime() { return _lastSendTime; }
    public synchronized void setLastSendTime(long when) { _lastSendTime = when; }
    /** when did we last hear from alice? */
    public synchronized long getReceiveAliceTime() { return _receiveAliceTime; }
    public synchronized void setReceiveAliceTime(long when) { _receiveAliceTime = when; }
    /** when did we last hear from bob? */
    public synchronized long getReceiveBobTime() { return _receiveBobTime; }
    public synchronized void setReceiveBobTime(long when) { _receiveBobTime = when; }
    /** when did we last hear from charlie? */
    public synchronized long getReceiveCharlieTime() { return _receiveCharlieTime; }
    public synchronized void setReceiveCharlieTime(long when) { _receiveCharlieTime = when; }
    
    public int getPacketsRelayed() { return _packetsRelayed; }
    public void incrementPacketsRelayed() { ++_packetsRelayed; }
    
    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("Role: ");
        if (_ourRole == ALICE) buf.append("Alice");
        else if (_ourRole == BOB) buf.append("Bob");
        else if (_ourRole == CHARLIE) buf.append("Charlie");
        else buf.append("unkown!");
        if (_aliceIP != null)
            buf.append(" alice: ").append(_aliceIP).append(':').append(_alicePort);
        if (_aliceIPFromCharlie != null)
            buf.append(" (fromCharlie ").append(_aliceIPFromCharlie).append(':').append(_alicePortFromCharlie).append(')');
        if (_bobIP != null)
            buf.append(" bob: ").append(_bobIP).append(':').append(_bobPort);
        if (_charlieIP != null)
            buf.append(" charlie: ").append(_charlieIP).append(':').append(_charliePort);
        buf.append(" last send after ").append(_lastSendTime - _beginTime).append("ms");
        if (_receiveAliceTime > 0)
            buf.append(" receive from alice after ").append(_receiveAliceTime - _beginTime).append("ms");
        if (_receiveBobTime > 0)
            buf.append(" receive from bob after ").append(_receiveBobTime - _beginTime).append("ms");
        if (_receiveCharlieTime > 0)
            buf.append(" receive from charlie after ").append(_receiveCharlieTime - _beginTime).append("ms");
        buf.append(" packets relayed: ").append(_packetsRelayed);
        return buf.toString();
    }
}
