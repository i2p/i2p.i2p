package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.util.SimpleTimer;
import net.i2p.util.Log;

/**
 *
 */
class PeerTestManager {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private PacketBuilder _packetBuilder;
    /** 
     * circular list of nonces which we have received as if we were 'Charlie'
     * (meaning if we see it again, we aren't Bob and shouldn't find our own Charlie).
     * Synchronize against this when updating it
     */
    private long _receiveAsCharlie[];
    /** index into _receiveAsCharlie which we should next write to */
    private int _receiveAsCharlieIndex;
    /** nonce we are currently running our own test as, or -1 */
    private long _currentTestNonce;
    private InetAddress _bobIP;
    private int _bobPort;
    private SessionKey _bobIntroKey;
    private SessionKey _bobCipherKey;
    private SessionKey _bobMACKey;
    private long _testBeginTime;
    private long _lastSendTime;
    private long _receiveBobReplyTime;
    private long _receiveCharlieReplyTime;
    private InetAddress _charlieIP;
    private int _charliePort;
    private SessionKey _charlieIntroKey;
    private int _receiveBobReplyPort;
    private int _receiveCharlieReplyPort;
    
    /** longest we will keep track of a Charlie nonce for */
    private static final int MAX_CHARLIE_LIFETIME = 10*1000;

    public PeerTestManager(RouterContext context, UDPTransport transport) {
        _context = context;
        _transport = transport;
        _log = context.logManager().getLog(PeerTestManager.class);
        _receiveAsCharlie = new long[64];
        _packetBuilder = new PacketBuilder(context);
        _currentTestNonce = -1;
    }
    
    private static final int RESEND_TIMEOUT = 5*1000;
    private static final int MAX_TEST_TIME = 30*1000;
    private static final long MAX_NONCE = (1l << 32) - 1l;
    //public void runTest(InetAddress bobIP, int bobPort, SessionKey bobIntroKey) {
    public void runTest(InetAddress bobIP, int bobPort, SessionKey bobCipherKey, SessionKey bobMACKey) {
        _currentTestNonce = _context.random().nextLong(MAX_NONCE);
        _bobIP = bobIP;
        _bobPort = bobPort;
        //_bobIntroKey = bobIntroKey;
        _bobCipherKey = bobCipherKey;
        _bobMACKey = bobMACKey;
        _charlieIP = null;
        _charliePort = -1;
        _charlieIntroKey = null;
        _testBeginTime = _context.clock().now();
        _lastSendTime = _testBeginTime;
        _receiveBobReplyTime = -1;
        _receiveCharlieReplyTime = -1;
        _receiveBobReplyPort = -1;
        _receiveCharlieReplyPort = -1;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Running test with bob = " + bobIP + ":" + bobPort);
        
        sendTestToBob();
        
        SimpleTimer.getInstance().addEvent(new ContinueTest(), RESEND_TIMEOUT);
    }
    
    private class ContinueTest implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (_currentTestNonce < 0) {
                // already completed
                return;
            } else if (expired()) {
                testComplete();
            } else {
                if (_receiveBobReplyTime < 0) {
                    // no message from Bob yet, send it again
                    sendTestToBob();
                } else if (_receiveCharlieReplyTime < 0) {
                    // received from Bob, but no reply from Charlie.  send it to 
                    // Bob again so he pokes Charlie
                    sendTestToBob();
                } else {
                    // received from both Bob and Charlie, but we haven't received a
                    // second message from Charlie yet
                    sendTestToCharlie();
                }
                SimpleTimer.getInstance().addEvent(ContinueTest.this, RESEND_TIMEOUT);
            }
        }
        private boolean expired() { return _testBeginTime + MAX_TEST_TIME < _context.clock().now(); }
    }

    private void sendTestToBob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending test to bob: " + _bobIP + ":" + _bobPort);
        _transport.send(_packetBuilder.buildPeerTestFromAlice(_bobIP, _bobPort, _bobCipherKey, _bobMACKey, //_bobIntroKey, 
                        _currentTestNonce, _transport.getIntroKey()));
    }
    private void sendTestToCharlie() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending test to charlie: " + _charlieIP + ":" + _charliePort);
        _transport.send(_packetBuilder.buildPeerTestFromAlice(_charlieIP, _charliePort, _charlieIntroKey, 
                        _currentTestNonce, _transport.getIntroKey()));
    }
    

    /**
     * Receive a PeerTest message which contains the correct nonce for our current 
     * test
     */
    private void receiveTestReply(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo) {
        if ( (DataHelper.eq(from.getIP(), _bobIP.getAddress())) && (from.getPort() == _bobPort) ) {
            _receiveBobReplyTime = _context.clock().now();
            _receiveBobReplyPort = testInfo.readPort();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive test reply from bob @ " + _bobIP + " on " + _receiveBobReplyPort);
        } else {
            if (_receiveCharlieReplyTime > 0) {
                // this is our second charlie, yay!
                _receiveCharlieReplyPort = testInfo.readPort();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Receive test reply from charlie @ " + _charlieIP + " on " + _receiveCharlieReplyPort);
                testComplete();
            } else {
                // ok, first charlie.  send 'em a packet
                _receiveCharlieReplyTime = _context.clock().now();
                _charlieIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
                testInfo.readIntroKey(_charlieIntroKey.getData(), 0);
                _charliePort = from.getPort();
                try {
                    _charlieIP = InetAddress.getByAddress(from.getIP());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test from charlie @ " + _charlieIP + " on " + _charliePort);
                    sendTestToCharlie();
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Charlie's IP is b0rked: " + from + ": " + testInfo);
                }
            }
        }
    }
    
    /**
     * Evaluate the info we have and act accordingly, since the test has either timed out or
     * we have successfully received the second PeerTest from a Charlie.
     *
     */
    private void testComplete() {
        short status = -1;
        if (_receiveCharlieReplyPort > 0) {
            // we received a second message from charlie
            if (_receiveBobReplyPort == _receiveCharlieReplyPort) {
                status = CommSystemFacade.STATUS_OK;
            } else {
                status = CommSystemFacade.STATUS_DIFFERENT;
            }
        } else if (_receiveCharlieReplyTime > 0) {
            // we received only one message from charlie
            status = CommSystemFacade.STATUS_UNKNOWN;
        } else if (_receiveBobReplyTime > 0) {
            // we received a message from bob but no messages from charlie
            status = CommSystemFacade.STATUS_REJECT_UNSOLICITED;
        } else {
            // we never received anything from bob - he is either down or ignoring us
            status = CommSystemFacade.STATUS_UNKNOWN;
        }
        
        honorStatus(status);
        
        // now zero everything out
        _currentTestNonce = -1;
        _bobIP = null;
        _bobPort = -1;
        _bobIntroKey = null;
        _bobCipherKey = null;
        _bobMACKey = null;
        _charlieIP = null;
        _charliePort = -1;
        _charlieIntroKey = null;
        _testBeginTime = -1;
        _lastSendTime = -1;
        _receiveBobReplyTime = -1;
        _receiveCharlieReplyTime = -1;
        _receiveBobReplyPort = -1;
        _receiveCharlieReplyPort = -1;
    }
    
    /**
     * Depending upon the status, fire off different events (using received port/ip/etc as 
     * necessary).
     *
     */
    private void honorStatus(short status) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Test results: status = " + status);
        _transport.setReachabilityStatus(status);
    }
    
    /**
     * Receive a test message of some sort from the given peer, queueing up any packet
     * that should be sent in response, or if its a reply to our own current testing,
     * adjusting our test state.
     *
     */
    public void receiveTest(RemoteHostId from, UDPPacketReader reader) {
        UDPPacketReader.PeerTestReader testInfo = reader.getPeerTestReader();
        byte fromIP[] = null;
        int fromPort = testInfo.readPort();
        long nonce = testInfo.readNonce();
        if (nonce == _currentTestNonce) {
            receiveTestReply(from, testInfo);
            return;
        }
        
        if ( (testInfo.readIPSize() > 0) && (fromPort > 0) ) {
            fromIP = new byte[testInfo.readIPSize()];
            testInfo.readIP(fromIP, 0);
        }
       
        if ( ( (fromIP == null) && (fromPort <= 0) ) || // info is unknown or...
             (DataHelper.eq(fromIP, from.getIP()) && (fromPort == from.getPort())) ) { // info matches sender
            int knownIndex = -1;
            boolean weAreCharlie = false;
            synchronized (_receiveAsCharlie) {
                for (int i = 0; (i < _receiveAsCharlie.length) && (knownIndex == -1); i++)
                    if (_receiveAsCharlie[i] == nonce)
                        knownIndex = i;
            }
            weAreCharlie = (knownIndex != -1);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive test with nonce " + nonce + ", known as charlie @ " + knownIndex);
            
            if (weAreCharlie) {
                receiveFromAliceAsCharlie(from, testInfo, nonce);
            } else {
                receiveFromAliceAsBob(from, testInfo, nonce);
            }
        } else {
            receiveFromBobAsCharlie(from, fromIP, fromPort, nonce, testInfo);
        }
    }
    
    /**
     * The packet's IP/port does not match the IP/port included in the message, 
     * so we must be Charlie receiving a PeerTest from Bob.
     *  
     */
    private void receiveFromBobAsCharlie(RemoteHostId from, byte fromIP[], int fromPort, long nonce, UDPPacketReader.PeerTestReader testInfo) {
        if (fromIP == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("From address received from Bob (we are Charlie) is invalid: " + from + ": " + testInfo);
            return;
        }
        if (fromPort <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("From port received from Bob (we are Charlie) is invalid: " + fromPort + ": " + testInfo);
            return;
        }

        boolean isNew = true;
        int index = -1;
        synchronized (_receiveAsCharlie) {
            for (int i = 0; i < _receiveAsCharlie.length; i++) {
                if (_receiveAsCharlie[i] == nonce) {
                    index = i;
                    isNew = false;
                    break;
                }
            }
            if (index == -1) {
                // ok, new nonce, store 'er
                index = (_receiveAsCharlieIndex + 1) % _receiveAsCharlie.length;
                _receiveAsCharlie[index] = nonce;
                _receiveAsCharlieIndex = index;
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive test as charlie nonce " + nonce + ", stored at index " + index);
            
        if (isNew)
            SimpleTimer.getInstance().addEvent(new RemoveCharlie(nonce, index), MAX_CHARLIE_LIFETIME);
        try {
            InetAddress aliceIP = InetAddress.getByAddress(fromIP);
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
            UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, fromPort, aliceIntroKey, _transport.getIntroKey(), nonce);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from bob as charlie and send to alice @ " + aliceIP + " on " + fromPort);
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
        }
    }

    /**
     * The PeerTest message came from the peer referenced in the message (or there wasn't
     * any info in the message), plus we are not acting as Charlie (so we've got to be Bob).
     *
     */
    private void receiveFromAliceAsBob(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo, long nonce) {
        // we are Bob, so send Alice her PeerTest, pick a Charlie, and 
        // send Charlie Alice's info
        PeerState charlie = null;
        for (int i = 0; i < 5; i++) {
            charlie = _transport.getPeerState(UDPAddress.CAPACITY_TESTING);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Picking charlie as " + charlie + " for alice of " + from);
            if ( (charlie != null) && (!charlie.getRemoteHostId().equals(from)) ) {
                break;
            }
            charlie = null;
        }
        if (charlie == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to pick a charlie");
            return;
        }
        InetAddress aliceIP = null;
        SessionKey aliceIntroKey = null;
        try {
            aliceIP = InetAddress.getByAddress(from.getIP());
            aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
            
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
            if (info == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No info for charlie: " + charlie);
                return;
            }
            
            UDPAddress addr = new UDPAddress(info.getTargetAddress(UDPTransport.STYLE));
            SessionKey charlieIntroKey = new SessionKey(addr.getIntroKey());
            
            UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, charlieIntroKey, nonce);
            _transport.send(packet);

            packet = _packetBuilder.buildPeerTestToCharlie(aliceIP, from.getPort(), aliceIntroKey, nonce, 
                                                           charlie.getRemoteIPAddress(), 
                                                           charlie.getRemotePort(), 
                                                           charlie.getCurrentCipherKey(), 
                                                           charlie.getCurrentMACKey());
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from alice as bob, picking charlie @ " + charlie.getRemoteIPAddress() + ":" 
                           + charlie.getRemotePort() + " for alice @ " + aliceIP + ":" + from.getPort());
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
        }
    }
    
    /** 
     * We are charlie, so send Alice her PeerTest message  
     *
     */
    private void receiveFromAliceAsCharlie(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo, long nonce) {
        try {
            InetAddress aliceIP = InetAddress.getByAddress(from.getIP());
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
            UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, _transport.getIntroKey(), nonce);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from alice as charlie, w/ alice @ " + aliceIP + ":" + from.getPort() + " and nonce " + nonce);
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
        }
    }
    
    /** 
     * forget about charlie's nonce after 60s.  
     */
    private class RemoveCharlie implements SimpleTimer.TimedEvent {
        private long _nonce;
        private int _index;
        public RemoveCharlie(long nonce, int index) {
            _nonce = nonce;
            _index = index;
        }
        public void timeReached() {
            /** only forget about an entry if we haven't already moved on */
            synchronized (_receiveAsCharlie) {
                if (_receiveAsCharlie[_index] == _nonce)
                    _receiveAsCharlie[_index] = -1;
            }
        }
        
    }
}
