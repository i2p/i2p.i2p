package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 *  From udp.html on the website:

<p>The automation of collaborative reachability testing for peers is
enabled by a sequence of PeerTest messages.  With its proper 
execution, a peer will be able to determine their own reachability
and may update its behavior accordingly.  The testing process is 
quite simple:</p>

<pre>
        Alice                  Bob                  Charlie
    PeerTest -------------------&gt;
                             PeerTest--------------------&gt;
                                &lt;-------------------PeerTest
         &lt;-------------------PeerTest
         &lt;------------------------------------------PeerTest
    PeerTest------------------------------------------&gt;
         &lt;------------------------------------------PeerTest
</pre>

<p>Each of the PeerTest messages carry a nonce identifying the
test series itself, as initialized by Alice.  If Alice doesn't 
get a particular message that she expects, she will retransmit
accordingly, and based upon the data received or the messages
missing, she will know her reachability.  The various end states
that may be reached are as follows:</p>

<ul>
<li>If she doesn't receive a response from Bob, she will retransmit
up to a certain number of times, but if no response ever arrives,
she will know that her firewall or NAT is somehow misconfigured, 
rejecting all inbound UDP packets even in direct response to an
outbound packet.  Alternately, Bob may be down or unable to get 
Charlie to reply.</li>

<li>If Alice doesn't receive a PeerTest message with the 
expected nonce from a third party (Charlie), she will retransmit
her initial request to Bob up to a certain number of times, even
if she has received Bob's reply already.  If Charlie's first message 
still doesn't get through but Bob's does, she knows that she is
behind a NAT or firewall that is rejecting unsolicited connection
attempts and that port forwarding is not operating properly (the
IP and port that Bob offered up should be forwarded).</li>

<li>If Alice receives Bob's PeerTest message and both of Charlie's
PeerTest messages but the enclosed IP and port numbers in Bob's 
and Charlie's second messages don't match, she knows that she is 
behind a symmetric NAT, rewriting all of her outbound packets with
different 'from' ports for each peer contacted.  She will need to
explicitly forward a port and always have that port exposed for 
remote connectivity, ignoring further port discovery.</li>

<li>If Alice receives Charlie's first message but not his second,
she will retransmit her PeerTest message to Charlie up to a 
certain number of times, but if no response is received she knows
that Charlie is either confused or no longer online.</li>
</ul>

<p>Alice should choose Bob arbitrarily from known peers who seem
to be capable of participating in peer tests.  Bob in turn should
choose Charlie arbitrarily from peers that he knows who seem to be
capable of participating in peer tests and who are on a different
IP from both Bob and Alice.  If the first error condition occurs
(Alice doesn't get PeerTest messages from Bob), Alice may decide
to designate a new peer as Bob and try again with a different nonce.</p>

<p>Alice's introduction key is included in all of the PeerTest 
messages so that she doesn't need to already have an established
session with Bob and so that Charlie can contact her without knowing
any additional information.  Alice may go on to establish a session
with either Bob or Charlie, but it is not required.</p>

 */
class PeerTestManager {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder _packetBuilder;
    /** map of Long(nonce) to PeerTestState for tests currently in progress (as Bob/Charlie) */
    private final Map<Long, PeerTestState> _activeTests;
    /** current test we are running (as Alice), or null */
    private PeerTestState _currentTest;
    private boolean _currentTestComplete;
    /** as Alice */
    private final Queue<Long> _recentTests;
    
    /** longest we will keep track of a Charlie nonce for */
    private static final int MAX_CHARLIE_LIFETIME = 10*1000;

    /**
     *  Have seen peer tests (as Alice) get stuck (_currentTest != null)
     *  so I've thrown some synchronizization on the methods;
     *  don't know the root cause or whether this fixes it
     */
    public PeerTestManager(RouterContext context, UDPTransport transport) {
        _context = context;
        _transport = transport;
        _log = context.logManager().getLog(PeerTestManager.class);
        _activeTests = new ConcurrentHashMap();
        _recentTests = new LinkedBlockingQueue();
        _packetBuilder = new PacketBuilder(context, transport);
        _context.statManager().createRateStat("udp.statusKnownCharlie", "How often the bob we pick passes us to a charlie we already have a session with?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveTestReply", "How often we get a reply to our peer test?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveTest", "How often we get a packet requesting us to participate in a peer test?", "udp", UDPTransport.RATES);
    }
    
    private static final int RESEND_TIMEOUT = 5*1000;
    private static final int MAX_TEST_TIME = 30*1000;
    private static final long MAX_NONCE = (1l << 32) - 1l;
    //public void runTest(InetAddress bobIP, int bobPort, SessionKey bobIntroKey) {

    /**
     *  The next few methods are for when we are Alice
     */
    public synchronized void runTest(InetAddress bobIP, int bobPort, SessionKey bobCipherKey, SessionKey bobMACKey) {
        if (_currentTest != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We are already running a test with bob = " + _currentTest.getBobIP() + ", aborting test with bob = " + bobIP);
            return;
        }
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
        _currentTestComplete = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Running test with bob = " + bobIP + ":" + bobPort + " " + test.getNonce());
        while (_recentTests.size() > 16)
            _recentTests.poll();
        _recentTests.offer(Long.valueOf(test.getNonce()));
        
        sendTestToBob();
        
        SimpleScheduler.getInstance().addEvent(new ContinueTest(), RESEND_TIMEOUT);
    }
    
    private class ContinueTest implements SimpleTimer.TimedEvent {
        public void timeReached() {
            synchronized (PeerTestManager.this) {
                PeerTestState state = _currentTest;
                if (state == null) {
                    // already completed
                    return;
                } else if (expired()) {
                    testComplete(true);
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
                    SimpleScheduler.getInstance().addEvent(ContinueTest.this, RESEND_TIMEOUT);
                }
            }
        }
    }

    /** call from a synchronized method */
    private boolean expired() { 
        PeerTestState state = _currentTest;
        if (state != null)
            return state.getBeginTime() + MAX_TEST_TIME < _context.clock().now(); 
        else
            return true;
    }
    
    /** call from a synchronized method */
    private void sendTestToBob() {
        PeerTestState test = _currentTest;
        if (!expired()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to bob: " + test.getBobIP() + ":" + test.getBobPort());
            _transport.send(_packetBuilder.buildPeerTestFromAlice(test.getBobIP(), test.getBobPort(), test.getBobCipherKey(), test.getBobMACKey(), //_bobIntroKey, 
                            test.getNonce(), _transport.getIntroKey()));
        } else {
            _currentTest = null;
        }
    }
    /** call from a synchronized method */
    private void sendTestToCharlie() {
        PeerTestState test = _currentTest;
        if (!expired()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to charlie: " + test.getCharlieIP() + ":" + test.getCharliePort());
            _transport.send(_packetBuilder.buildPeerTestFromAlice(test.getCharlieIP(), test.getCharliePort(), test.getCharlieIntroKey(), 
                            test.getNonce(), _transport.getIntroKey()));
        } else {
            _currentTest = null;
        }
    }
    
    /**
     * If we have sent a packet to charlie within the last 10 minutes, ignore any test 
     * results we get from them, as our NAT will have poked a hole anyway
     * NAT idle timeouts vary widely, from 30s to 10m or more.
     * Set this too high and a high-traffic router may rarely get a good test result.
     * Set it too low and a router will think it is reachable when it isn't.
     * Maybe a router should need two consecutive OK results before believing it?
     *
     */
    private static final long CHARLIE_RECENT_PERIOD = 10*60*1000;

    /**
     * Receive a PeerTest message which contains the correct nonce for our current 
     * test. We are Alice.
     */
    private synchronized void receiveTestReply(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo) {
        _context.statManager().addRateData("udp.receiveTestReply", 1, 0);
        PeerTestState test = _currentTest;
        if (expired())
            return;
        if (_currentTestComplete)
            return;
        if ( (DataHelper.eq(from.getIP(), test.getBobIP().getAddress())) && (from.getPort() == test.getBobPort()) ) {
            // The reply is from Bob

            int ipSize = testInfo.readIPSize();
            if (ipSize != 4 && ipSize != 16) {
                // There appears to be a bug where Bob is sending us a zero-length IP.
                // We could proceed without setting the IP, but then when Charlie
                // sends us his message, we will think we are behind a symmetric NAT
                // because the Bob and Charlie IPs won't match.
                // So for now we just return and pretend we didn't hear from Bob at all.
                // Which is essentially what catching the uhe below did,
                // but without the error message to the log.
                // To do: fix the bug.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bad IP length " + ipSize +
                               " from bob's reply: " + from + ", " + testInfo);
                return;
            }
            byte ip[] = new byte[ipSize];
            testInfo.readIP(ip, 0);
            try {
                InetAddress addr = InetAddress.getByAddress(ip);
                test.setAliceIP(addr);
                test.setReceiveBobTime(_context.clock().now());
                test.setAlicePort(testInfo.readPort());

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Receive test reply from bob @ " + from + " via our " + test.getAlicePort() + "/" + test.getAlicePortFromCharlie());
                if (test.getAlicePortFromCharlie() > 0)
                    testComplete(false);
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unable to get our IP (length " + ipSize +
                               ") from bob's reply: " + from + ", " + testInfo, uhe);
            }
        } else {
            // The reply is from Charlie

            PeerState charlieSession = _transport.getPeerState(from);
            long recentBegin = _context.clock().now() - CHARLIE_RECENT_PERIOD;
            if ( (charlieSession != null) && 
                 ( (charlieSession.getLastACKSend() > recentBegin) ||
                   (charlieSession.getLastSendTime() > recentBegin) ) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bob chose a charlie we already have a session to, cancelling the test and rerunning (bob: " 
                              + _currentTest + ", charlie: " + from + ")");
                // why are we doing this instead of calling testComplete() ?
                _currentTestComplete = true;
                _context.statManager().addRateData("udp.statusKnownCharlie", 1, 0);
                honorStatus(CommSystemFacade.STATUS_UNKNOWN);
                _currentTest = null;
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
                        testComplete(true);
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Charlie @ " + from + " said we were an invalid IP address: " + uhe.getMessage(), uhe);
                }
            } else {
                if (test.getPacketsRelayed() > MAX_RELAYED_PER_TEST) {
                    testComplete(false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Received too many packets on the test: " + test);
                    return;
                }
                
                if (_log.shouldLog(Log.INFO) && charlieSession != null)
                    _log.info("Bob chose a charlie we last acked " + DataHelper.formatDuration(_context.clock().now() - charlieSession.getLastACKSend()) + " last sent " + DataHelper.formatDuration(_context.clock().now() - charlieSession.getLastSendTime()) + " (bob: " 
                              + _currentTest + ", charlie: " + from + ")");

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
     * @param forgetTest must be true to clear out this test and allow another
     *
     * call from a synchronized method
     */
    private void testComplete(boolean forgetTest) {
        _currentTestComplete = true;
        short status = -1;
        PeerTestState test = _currentTest;

        // Don't do this or we won't call honorStatus()
        // to set the status to UNKNOWN or REJECT_UNSOLICITED
        // if (expired()) { 
        //     _currentTest = null;
        //    return;
        // }

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
        if (forgetTest)
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
     * We could be Alice, Bob, or Charlie.
     */
    public void receiveTest(RemoteHostId from, UDPPacketReader reader) {
        _context.statManager().addRateData("udp.receiveTest", 1, 0);
        UDPPacketReader.PeerTestReader testInfo = reader.getPeerTestReader();
        byte testIP[] = null;
        int testPort = testInfo.readPort();
        long nonce = testInfo.readNonce();
        PeerTestState test = _currentTest;
        if ( (test != null) && (test.getNonce() == nonce) ) {
            // we are Alice
            receiveTestReply(from, testInfo);
            return;
        }

        // we are Bob or Charlie

        if ( (testInfo.readIPSize() > 0) && (testPort > 0) ) {
            testIP = new byte[testInfo.readIPSize()];
            testInfo.readIP(testIP, 0);
        }
       
        PeerTestState state = _activeTests.get(Long.valueOf(nonce));
        
        if (state == null) {
            if ( (testIP == null) || (testPort <= 0) ) {
                // we are bob, since we haven't seen this nonce before AND its coming from alice
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("test IP/port are blank coming from " + from + ", assuming we are Bob and they are alice");
                receiveFromAliceAsBob(from, testInfo, nonce, null);
            } else {
                if (_recentTests.contains(Long.valueOf(nonce))) {
                    // ignore the packet, as its a holdover from a recently completed locally
                    // initiated test
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("We are charlie, as the testIP/port is " + RemoteHostId.toString(testIP) + ":" + testPort + " and the state is unknown for " + nonce);
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
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Received from a fourth party as bob!  alice: " + state.getAliceIP() + ", charlie: " + state.getCharlieIP() + ", dave: " + from);
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
    
    // Below here are methods for when we are Bob or Charlie

    private static final int MAX_RELAYED_PER_TEST = 5;
    
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

            state.incrementPacketsRelayed();
            if (state.getPacketsRelayed() > MAX_RELAYED_PER_TEST) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Receive from bob (" + from + ") as charlie with alice @ " + aliceIP + ":" + alicePort
                              + ", but we've already relayed too many packets to that test, so we're dropping it");
                return;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from bob (" + from + ") as charlie, sending back to bob and sending to alice @ " + aliceIP + ":" + alicePort);
            
            if (isNew) {
                _activeTests.put(Long.valueOf(nonce), state);
                SimpleScheduler.getInstance().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
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
            charlie = _transport.pickTestPeer(from);
            if (charlie != null)
                charlieInfo = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
        } else {
            charlie = _transport.getPeerState(new RemoteHostId(state.getCharlieIP().getAddress(), state.getCharliePort()));
            if (charlie != null)
                charlieInfo = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
        }
        
        if ( (charlie == null) || (charlieInfo == null) ) {
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
            
            state.incrementPacketsRelayed();
            if (state.getPacketsRelayed() > MAX_RELAYED_PER_TEST) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Receive from alice (" + aliceIP + ":" + from.getPort() 
                              + ") as bob, but we've already relayed too many packets to that test, so we're dropping it");
                return;
            }
            
            if (isNew) {
                _activeTests.put(Long.valueOf(nonce), state);
                SimpleScheduler.getInstance().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
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
        
        state.incrementPacketsRelayed();
        if (state.getPacketsRelayed() > MAX_RELAYED_PER_TEST) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received from charlie (" + from + ") as bob (" + state + "), but we've already relayed too many, so drop it");
            return;
        }
        
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
                _activeTests.remove(Long.valueOf(_nonce));
        }
    }
}
