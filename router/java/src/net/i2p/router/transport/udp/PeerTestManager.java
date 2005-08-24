package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.data.Base64;
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
    /** map of Long(nonce) to PeerTestState for tests currently in progress */
    private Map _activeTests;
    /** current test we are running, or null */
    private PeerTestState _currentTest;
    private List _recentTests;
    
    /** longest we will keep track of a Charlie nonce for */
    private static final int MAX_CHARLIE_LIFETIME = 10*1000;

    public PeerTestManager(RouterContext context, UDPTransport transport) {
        _context = context;
        _transport = transport;
        _log = context.logManager().getLog(PeerTestManager.class);
        _activeTests = new HashMap(64);
        _recentTests = Collections.synchronizedList(new ArrayList(16));
        _packetBuilder = new PacketBuilder(context);
        _currentTest = null;
        _context.statManager().createRateStat("udp.statusKnownCharlie", "How often the bob we pick passes us to a charlie we already have a session with?", "udp", new long[] { 60*1000, 20*60*1000, 60*60*1000 });
    }
    
    private static final int RESEND_TIMEOUT = 5*1000;
    private static final int MAX_TEST_TIME = 30*1000;
    private static final long MAX_NONCE = (1l << 32) - 1l;
    //public void runTest(InetAddress bobIP, int bobPort, SessionKey bobIntroKey) {
    public void runTest(InetAddress bobIP, int bobPort, SessionKey bobCipherKey, SessionKey bobMACKey) {
        PeerTestState test = new PeerTestState();
        test.setNonce(_context.random().nextLong(MAX_NONCE));
        test.setBobIP(bobIP);
        test.setBobPort(bobPort);
        test.setBobCipherKey(bobCipherKey);
        test.setBobMACKey(bobMACKey);
        test.setBeginTime(_context.clock().now());
        test.setLastSendTime(test.getBeginTime());
        test.setOurRole(PeerTestState.ALICE);
        _currentTest = test;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Running test with bob = " + bobIP + ":" + bobPort + " " + test.getNonce());
        while (_recentTests.size() > 16)
            _recentTests.remove(0);
        _recentTests.add(new Long(test.getNonce()));
        
        sendTestToBob();
        
        SimpleTimer.getInstance().addEvent(new ContinueTest(), RESEND_TIMEOUT);
    }
    
    private class ContinueTest implements SimpleTimer.TimedEvent {
        public void timeReached() {
            PeerTestState state = _currentTest;
            if (state == null) {
                // already completed
                return;
            } else if (expired()) {
                testComplete();
            } else if (_context.clock().now() - state.getLastSendTime() >= RESEND_TIMEOUT) {
                if (state.getReceiveBobTime() <= 0) {
                    // no message from Bob yet, send it again
                    sendTestToBob();
                } else if (state.getReceiveCharlieTime() <= 0) {
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
        private boolean expired() { 
            PeerTestState state = _currentTest;
            if (state != null)
                return _currentTest.getBeginTime() + MAX_TEST_TIME < _context.clock().now(); 
            else
                return true;
        }
    }

    private void sendTestToBob() {
        PeerTestState test = _currentTest;
        if (test != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to bob: " + test.getBobIP() + ":" + test.getBobPort());
            _transport.send(_packetBuilder.buildPeerTestFromAlice(test.getBobIP(), test.getBobPort(), test.getBobCipherKey(), test.getBobMACKey(), //_bobIntroKey, 
                            test.getNonce(), _transport.getIntroKey()));
        }
    }
    private void sendTestToCharlie() {
        PeerTestState test = _currentTest;
        if (test != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to charlie: " + test.getCharlieIP() + ":" + test.getCharliePort());
            _transport.send(_packetBuilder.buildPeerTestFromAlice(test.getCharlieIP(), test.getCharliePort(), test.getCharlieIntroKey(), 
                            test.getNonce(), _transport.getIntroKey()));
        }
    }
    

    /**
     * Receive a PeerTest message which contains the correct nonce for our current 
     * test
     */
    private void receiveTestReply(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo) {
        PeerTestState test = _currentTest;
        if (test == null) return;
        if ( (DataHelper.eq(from.getIP(), test.getBobIP().getAddress())) && (from.getPort() == test.getBobPort()) ) {
            byte ip[] = new byte[testInfo.readIPSize()];
            testInfo.readIP(ip, 0);
            try {
                InetAddress addr = InetAddress.getByAddress(ip);
                test.setAliceIP(addr);
                test.setReceiveBobTime(_context.clock().now());
                test.setAlicePort(testInfo.readPort());

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Receive test reply from bob @ " + from.getIP() + " via our " + test.getAlicePort() + "/" + test.getAlicePortFromCharlie());
                if (test.getAlicePortFromCharlie() > 0)
                    testComplete();
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unable to get our IP from bob's reply: " + from + ", " + testInfo, uhe);
            }
        } else {
            PeerState charlieSession = _transport.getPeerState(from);
            if (charlieSession != null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bob chose a charlie we already have a session to, cancelling the test and rerunning (bob: " 
                              + _currentTest + ", charlie: " + from + ")");
                _currentTest = null;
                _context.statManager().addRateData("udp.statusKnownCharlie", 1, 0);
                honorStatus(CommSystemFacade.STATUS_UNKNOWN);
                return;
            }
            
            if (test.getReceiveCharlieTime() > 0) {
                // this is our second charlie, yay!
                test.setAlicePortFromCharlie(testInfo.readPort());
                byte ip[] = new byte[testInfo.readIPSize()];
                testInfo.readIP(ip, 0);
                try {
                    InetAddress addr = InetAddress.getByAddress(ip);
                    test.setAliceIPFromCharlie(addr);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test reply from charlie @ " + test.getCharlieIP() + " via our " 
                                   + test.getAlicePort() + "/" + test.getAlicePortFromCharlie());
                    if (test.getReceiveBobTime() > 0)
                        testComplete();
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Charlie @ " + from + " said we were an invalid IP address: " + uhe.getMessage(), uhe);
                }
            } else {
                // ok, first charlie.  send 'em a packet
                test.setReceiveCharlieTime(_context.clock().now());
                SessionKey charlieIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
                testInfo.readIntroKey(charlieIntroKey.getData(), 0);
                test.setCharlieIntroKey(charlieIntroKey);
                try {
                    test.setCharlieIP(InetAddress.getByAddress(from.getIP()));
                    test.setCharliePort(from.getPort());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test from charlie @ " + from);
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
        PeerTestState test = _currentTest;
        if (test == null) return;
        if (test.getAlicePortFromCharlie() > 0) {
            // we received a second message from charlie
            if ( (test.getAlicePort() == test.getAlicePortFromCharlie()) &&
                 (test.getAliceIP() != null) && (test.getAliceIPFromCharlie() != null) &&
                 (test.getAliceIP().equals(test.getAliceIPFromCharlie())) ) {
                status = CommSystemFacade.STATUS_OK;
            } else {
                status = CommSystemFacade.STATUS_DIFFERENT;
            }
        } else if (test.getReceiveCharlieTime() > 0) {
            // we received only one message from charlie
            status = CommSystemFacade.STATUS_UNKNOWN;
        } else if (test.getReceiveBobTime() > 0) {
            // we received a message from bob but no messages from charlie
            status = CommSystemFacade.STATUS_REJECT_UNSOLICITED;
        } else {
            // we never received anything from bob - he is either down, 
            // ignoring us, or unable to get a Charlie to respond
            status = CommSystemFacade.STATUS_UNKNOWN;
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Test complete: " + test);
        
        honorStatus(status);
        _currentTest = null;
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
        byte testIP[] = null;
        int testPort = testInfo.readPort();
        long nonce = testInfo.readNonce();
        PeerTestState test = _currentTest;
        if ( (test != null) && (test.getNonce() == nonce) ) {
            receiveTestReply(from, testInfo);
            return;
        }
        
        if ( (testInfo.readIPSize() > 0) && (testPort > 0) ) {
            testIP = new byte[testInfo.readIPSize()];
            testInfo.readIP(testIP, 0);
        }
       
        PeerTestState state = null;
        synchronized (_activeTests) {
            state = (PeerTestState)_activeTests.get(new Long(nonce));
        }
        
        if (state == null) {
            if ( (testIP == null) || (testPort <= 0) ) {
                // we are bob, since we haven't seen this nonce before AND its coming from alice
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("test IP/port are blank coming from " + from + ", assuming we are Bob and they are alice");
                receiveFromAliceAsBob(from, testInfo, nonce, null);
            } else {
                if (_recentTests.contains(new Long(nonce))) {
                    // ignore the packet, as its a holdover from a recently completed locally
                    // initiated test
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("We are charlie, as te testIP/port is " + testIP + ":" + testPort + " and the state is unknown for " + nonce);
                    // we are charlie, since alice never sends us her IP and port, only bob does (and,
                    // erm, we're not alice, since it isn't our nonce)
                    receiveFromBobAsCharlie(from, testInfo, nonce, null);
                }
            }
        } else {
            if (state.getOurRole() == PeerTestState.BOB) {
                if (DataHelper.eq(from.getIP(), state.getAliceIP().getAddress()) && 
                    (from.getPort() == state.getAlicePort()) ) {
                    receiveFromAliceAsBob(from, testInfo, nonce, state);
                } else if (DataHelper.eq(from.getIP(), state.getCharlieIP().getAddress()) && 
                           (from.getPort() == state.getCharliePort()) ) {
                    receiveFromCharlieAsBob(from, state);
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Received from a fourth party as bob!  alice: " + state.getAliceIP() + ", charlie: " + state.getCharlieIP() + ", dave: " + from);
                }
            } else if (state.getOurRole() == PeerTestState.CHARLIE) {
                if ( (testIP == null) || (testPort <= 0) ) {
                    receiveFromAliceAsCharlie(from, testInfo, nonce);
                } else {
                    receiveFromBobAsCharlie(from, testInfo, nonce, state);
                }
            }
        }
    }
    
    /**
     * The packet's IP/port does not match the IP/port included in the message, 
     * so we must be Charlie receiving a PeerTest from Bob.
     *  
     */
    private void receiveFromBobAsCharlie(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo, long nonce, PeerTestState state) {
        boolean isNew = false;
        if (state == null) {
            isNew = true;
            state = new PeerTestState();
            state.setOurRole(PeerTestState.CHARLIE);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive test as charlie nonce " + nonce);
            
        int sz = testInfo.readIPSize();
        byte aliceIPData[] = new byte[sz];
        try {
            testInfo.readIP(aliceIPData, 0);
            int alicePort = testInfo.readPort();
            InetAddress aliceIP = InetAddress.getByAddress(aliceIPData);
            InetAddress bobIP = InetAddress.getByAddress(from.getIP());
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
         
            state.setAliceIP(aliceIP);
            state.setAlicePort(alicePort);
            state.setAliceIntroKey(aliceIntroKey);
            state.setNonce(nonce);
            state.setBobIP(bobIP);
            state.setBobPort(from.getPort());
            state.setLastSendTime(_context.clock().now());
            state.setOurRole(PeerTestState.CHARLIE);
            state.setReceiveBobTime(_context.clock().now());
            
            PeerState bob = _transport.getPeerState(from);
            if (bob == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received from bob (" + from + ") who hasn't established a session with us, refusing to help him test " + aliceIP +":" + alicePort);
                return;
            } else {
                state.setBobCipherKey(bob.getCurrentCipherKey());
                state.setBobMACKey(bob.getCurrentMACKey());
            }

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from bob (" + from + ") as charlie, sending back to bob and sending to alice @ " + aliceIP + ":" + alicePort);
            
            if (isNew) {
                synchronized (_activeTests) {
                    _activeTests.put(new Long(nonce), state);
                }
                SimpleTimer.getInstance().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
            }

            UDPPacket packet = _packetBuilder.buildPeerTestToBob(bobIP, from.getPort(), aliceIP, alicePort, aliceIntroKey, nonce, state.getBobCipherKey(), state.getBobMACKey());
            _transport.send(packet);
            
            packet = _packetBuilder.buildPeerTestToAlice(aliceIP, alicePort, aliceIntroKey, _transport.getIntroKey(), nonce);
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from + ", ip size: " + sz + " ip val: " + Base64.encode(aliceIPData), uhe);
        }
    }

    /**
     * The PeerTest message came from the peer referenced in the message (or there wasn't
     * any info in the message), plus we are not acting as Charlie (so we've got to be Bob).
     *
     */
    private void receiveFromAliceAsBob(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo, long nonce, PeerTestState state) {
        // we are Bob, so pick a (potentially) Charlie and send Charlie Alice's info
        PeerState charlie = null;
        RouterInfo charlieInfo = null;
        if (state == null) { // pick a new charlie
            for (int i = 0; i < 5; i++) {
                charlie = _transport.getPeerState(UDPAddress.CAPACITY_TESTING);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Picking charlie as " + charlie + " for alice of " + from);
                if ( (charlie != null) && (!DataHelper.eq(charlie.getRemoteHostId(), from)) ) {
                    charlieInfo = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
                    if (charlieInfo != null)
                        break;
                }
                charlie = null;
            }
        } else {
            charlie = _transport.getPeerState(new RemoteHostId(state.getCharlieIP().getAddress(), state.getCharliePort()));
            if (charlie != null)
                charlieInfo = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
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
            
            UDPAddress addr = new UDPAddress(charlieInfo.getTargetAddress(UDPTransport.STYLE));
            SessionKey charlieIntroKey = new SessionKey(addr.getIntroKey());
            
            //UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, charlieIntroKey, nonce);
            //_transport.send(packet);

            boolean isNew = false;
            if (state == null) {
                isNew = true;
                state = new PeerTestState();
                state.setBeginTime(_context.clock().now());
            }
            state.setAliceIP(aliceIP);
            state.setAlicePort(from.getPort());
            state.setAliceIntroKey(aliceIntroKey);
            state.setNonce(nonce);
            state.setCharlieIP(charlie.getRemoteIPAddress());
            state.setCharliePort(charlie.getRemotePort());
            state.setCharlieIntroKey(charlieIntroKey);
            state.setLastSendTime(_context.clock().now());
            state.setOurRole(PeerTestState.BOB);
            state.setReceiveAliceTime(_context.clock().now());
            
            if (isNew) {
                synchronized (_activeTests) {
                    _activeTests.put(new Long(nonce), state);
                }
                SimpleTimer.getInstance().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
            }
            
            UDPPacket packet = _packetBuilder.buildPeerTestToCharlie(aliceIP, from.getPort(), aliceIntroKey, nonce, 
                                                                     charlie.getRemoteIPAddress(), 
                                                                     charlie.getRemotePort(), 
                                                                     charlie.getCurrentCipherKey(), 
                                                                     charlie.getCurrentMACKey());
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from alice as bob for " + nonce + ", picking charlie @ " + charlie.getRemoteIPAddress() + ":" 
                           + charlie.getRemotePort() + " for alice @ " + aliceIP + ":" + from.getPort());
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
        }
    }
    
    /**
     * The PeerTest message came from one of the Charlies picked for an existing test, so send Alice the
     * packet verifying participation.
     *
     */
    private void receiveFromCharlieAsBob(RemoteHostId from, PeerTestState state) {
        state.setReceiveCharlieTime(_context.clock().now());
        UDPPacket packet = _packetBuilder.buildPeerTestToAlice(state.getAliceIP(), state.getAlicePort(),
                                                               state.getAliceIntroKey(), state.getCharlieIntroKey(), 
                                                               state.getNonce());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive from charlie @ " + from + " as bob, sending alice back the ok @ " + state.getAliceIP() + ":" + state.getAlicePort());

        _transport.send(packet);
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
    private class RemoveTest implements SimpleTimer.TimedEvent {
        private long _nonce;
        public RemoveTest(long nonce) {
            _nonce = nonce;
        }
        public void timeReached() {
            synchronized (_activeTests) {
                _activeTests.remove(new Long(_nonce));
            }
        }
    }
}
