package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import static net.i2p.router.transport.udp.PeerTestState.Role.*;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.HexDump;
import net.i2p.util.SimpleTimer;

/**
 *  Entry points are runTest() to start a new test as Alice,
 *  and receiveTest() for all received test packets.
 *
 *  IPv6 info: All Alice-Bob and Alice-Charlie communication is via IPv4.
 *  The Bob-Charlie communication may be via IPv6, however Charlie must
 *  be IPv4-capable.
 *  The IP address (of Alice) in the message must be IPv4 if present,
 *  as we only support testing of IPv4.
 *  Testing of IPv6 could be added in the future.
 *
 *  From udp.html on the website:

<p>The automation of collaborative reachability testing for peers is
enabled by a sequence of PeerTest messages.  With its proper 
execution, a peer will be able to determine their own reachability
and may update its behavior accordingly.  The testing process is 
quite simple:</p>

<pre>
        Alice                  Bob                  Charlie

    runTest()
    sendTestToBob()     receiveFromAliceAsBob()
    PeerTest -------------------&gt;

                        sendTestToCharlie()       receiveFromBobAsCharlie()
                             PeerTest--------------------&gt;

                        receiveFromCharlieAsBob()
                                &lt;-------------------PeerTest

    receiveTestReply()
         &lt;-------------------PeerTest

    receiveTestReply()
         &lt;------------------------------------------PeerTest

                                                  receiveFromAliceAsCharlie()
    PeerTest------------------------------------------&gt;

    receiveTestReply()
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
    private final PacketBuilder2 _packetBuilder2;
    /** map of Long(nonce) to PeerTestState for tests currently in progress (as Bob/Charlie) */
    private final Map<Long, PeerTestState> _activeTests;
    /** current test we are running (as Alice), or null */
    private PeerTestState _currentTest;
    private boolean _currentTestComplete;
    /** as Alice */
    private final Queue<Long> _recentTests;
    private final IPThrottler _throttle;
    
    private static final int MAX_RELAYED_PER_TEST_ALICE = 9;
    private static final int MAX_RELAYED_PER_TEST_BOB = 6;
    private static final int MAX_RELAYED_PER_TEST_CHARLIE = 6;
    
    /** longest we will keep track of a Charlie nonce for */
    private static final int MAX_CHARLIE_LIFETIME = 15*1000;

    /** as Bob/Charlie */
    private static final int MAX_ACTIVE_TESTS = 20;
    private static final int MAX_RECENT_TESTS = 40;

    /** for the throttler */
    private static final int MAX_PER_IP = 12;
    private static final long THROTTLE_CLEAN_TIME = 10*60*1000;

    /** initial - ContinueTest adds backoff */
    private static final int RESEND_TIMEOUT = 4*1000;
    private static final int MAX_TEST_TIME = 30*1000;
    private static final long MAX_SKEW = 2*60*1000;
    private static final long MAX_NONCE = (1l << 32) - 1l;

    /**
     *  Have seen peer tests (as Alice) get stuck (_currentTest != null)
     *  so I've thrown some synchronizization on the methods;
     *  don't know the root cause or whether this fixes it
     */
    public PeerTestManager(RouterContext context, UDPTransport transport) {
        _context = context;
        _transport = transport;
        _log = context.logManager().getLog(PeerTestManager.class);
        _activeTests = new ConcurrentHashMap<Long, PeerTestState>();
        _recentTests = new LinkedBlockingQueue<Long>();
        _packetBuilder = transport.getBuilder();
        _packetBuilder2 = transport.getBuilder2();
        _throttle = new IPThrottler(MAX_PER_IP, THROTTLE_CLEAN_TIME);
        _context.statManager().createRateStat("udp.statusKnownCharlie", "How often the bob we pick passes us to a charlie we already have a session with?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveTestReply", "How often we get a reply to our peer test?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveTest", "How often we get a packet requesting us to participate in a peer test?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.testBadIP", "Received IP or port was bad", "udp", UDPTransport.RATES);
    }

    /**
     *  The next few methods are for when we are Alice
     *
     *  @param bob IPv4 only
     */
    public synchronized void runTest(PeerState bob) {
        if (_currentTest != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We are already running a test: " + _currentTest + ", aborting test with bob = " + bob);
            return;
        }
        InetAddress bobIP = bob.getRemoteIPAddress();
        if (_transport.isTooClose(bobIP.getAddress())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not running test with Bob too close to us " + bobIP);
            return;
        }
        PeerTestState test = new PeerTestState(ALICE, bob, bobIP instanceof Inet6Address,
                                               _context.random().nextLong(MAX_NONCE),
                                               _context.clock().now());
        if (bob.getVersion() == 2) {
            PeerState2 b2 = (PeerState2) bob;
            try {
                InetAddress addr = InetAddress.getByAddress(b2.getOurIP());
                test.setAlice(addr, b2.getOurPort(), _context.routerHash());
            } catch (UnknownHostException uhe) {
                if (_log.shouldWarn())
                    _log.warn("Unable to get our IP", uhe);
                return;
            }
        }
        _currentTest = test;
        _currentTestComplete = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Start new test: " + test);
        while (_recentTests.size() > MAX_RECENT_TESTS)
            _recentTests.poll();
        _recentTests.offer(Long.valueOf(test.getNonce()));
        
        test.incrementPacketsRelayed();
        sendTestToBob();
        
        _context.simpleTimer2().addEvent(new ContinueTest(test.getNonce()), RESEND_TIMEOUT);
    }
    
    private class ContinueTest implements SimpleTimer.TimedEvent {
        private final long _nonce;

        public ContinueTest(long nonce) {
            _nonce = nonce;
        }

        public void timeReached() {
            synchronized (PeerTestManager.this) {
                PeerTestState state = _currentTest;
                if (state == null || state.getNonce() != _nonce) {
                    // already completed, possibly on to the next test
                    return;
                } else if (expired()) {
                    if (!_currentTestComplete)
                        testComplete();
                    return;
                }
                long timeSinceSend = _context.clock().now() - state.getLastSendTime();
                if (timeSinceSend >= RESEND_TIMEOUT) {
                    int sent = state.incrementPacketsRelayed();
                    if (sent > MAX_RELAYED_PER_TEST_ALICE) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Sent too many packets: " + state);
                        if (!_currentTestComplete)
                            testComplete();
                        return;
                    }
                    if (state.getReceiveBobTime() <= 0) {
                        // no message from Bob yet, send it again
                        sendTestToBob();
                    } else if (state.getReceiveCharlieTime() <= 0) {
                        // received from Bob, but no reply from Charlie.  send it to 
                        // Bob again so he pokes Charlie
                        // This is only useful for SSU 1; SSU 2 discards dups
                        if (state.getBob().getVersion() == 1)
                            sendTestToBob();
                    } else {
                        // received from both Bob and Charlie, but we haven't received a
                        // second message from Charlie yet
                        sendTestToCharlie();
                    }
                    // retx at 4, 10, 17, 25 elapsed time
                    _context.simpleTimer2().addEvent(ContinueTest.this, RESEND_TIMEOUT + (sent*1000));
                } else {
                    _context.simpleTimer2().addEvent(ContinueTest.this, RESEND_TIMEOUT - timeSinceSend);
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
    
    /**
     * SSU 1 or 2. We are Alice.
     * Call from a synchronized method.
     */
    private void sendTestToBob() {
        PeerTestState test = _currentTest;
        if (!expired()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending test to Bob: " + test);
            UDPPacket packet;
            if (test.getBob().getVersion() == 1) {
                packet = _packetBuilder.buildPeerTestFromAlice(test.getBobIP(), test.getBobPort(),
                                                               test.getBobCipherKey(), test.getBobMACKey(),
                                                               test.getNonce(), _transport.getIntroKey());
            } else {
                SigningPrivateKey spk = _context.keyManager().getSigningPrivateKey();
                PeerState2 bob = (PeerState2) test.getBob();
                // TODO only create this once
                byte[] data = SSU2Util.createPeerTestData(_context, bob.getRemotePeer(), _context.routerHash(),
                                                          ALICE, test.getNonce(), bob.getOurIP(), bob.getOurPort(), spk);
                if (data == null) {
                    if (_log.shouldWarn())
                        _log.warn("sig fail");
                     testComplete();
                     return;
                }
                packet = _packetBuilder2.buildPeerTestFromAlice(data, bob);
            }
            _transport.send(packet);
            test.setLastSendTime(_context.clock().now());
        } else {
            _currentTest = null;
        }
    }

    /**
     * Message 6. SSU 1 or 2. We are Alice.
     * Call from a synchronized method.
     */
    private void sendTestToCharlie() {
        PeerTestState test = _currentTest;
        if (test == null)
            return;
        if (!expired()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending msg 6 to Charlie: " + test);
            test.setLastSendTime(_context.clock().now());
            UDPPacket packet;
            if (test.getBob().getVersion() == 1) {
                packet = _packetBuilder.buildPeerTestFromAlice(test.getCharlieIP(), test.getCharliePort(),
                                                               test.getCharlieIntroKey(), 
                                                               test.getNonce(), _transport.getIntroKey());
            } else {
                long nonce = test.getNonce();
                long sendId = (nonce << 32) | nonce;
                long rcvId = ~sendId;
                InetAddress addr = test.getAliceIP();
                int alicePort = test.getAlicePort();
                byte[] aliceIP = addr.getAddress();
                int iplen = aliceIP.length;
                byte[] data = new byte[13 + iplen];
                data[0] = 1;  // alice
                data[1] = 2;  // version
                DataHelper.toLong(data, 2, 4, nonce);
                DataHelper.toLong(data, 6, 4, _context.clock().now() / 1000);
                data[10] = (byte) iplen;
                System.arraycopy(aliceIP, 0, data, 11, iplen);
                DataHelper.toLong(data, 11 + iplen, 2, alicePort);
                packet = _packetBuilder2.buildPeerTestFromAlice(test.getCharlieIP(), test.getCharliePort(),
                                                                test.getCharlieIntroKey(),
                                                                sendId, rcvId, data);
            }
            _transport.send(packet);
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
     *
     * SSU 1 only.
     *
     * @param fromPeer non-null if an associated session was found, otherwise null
     * @param inSession true if authenticated in-session
     */
    private synchronized void receiveTestReply(RemoteHostId from, PeerState fromPeer, boolean inSession,
                                               UDPPacketReader.PeerTestReader testInfo) {
        _context.statManager().addRateData("udp.receiveTestReply", 1);
        PeerTestState test = _currentTest;
        if (expired())
            return;
        if (_currentTestComplete)
            return;
        if ( (DataHelper.eq(from.getIP(), test.getBobIP().getAddress())) && (from.getPort() == test.getBobPort()) ) {
            // The reply is from Bob

            if (inSession) {
                // i2pd has sent the Bob->Alice message in-session for a long time
                // Java I2P switched to in-session in 0.9.52
                //if (_log.shouldDebug())
                //    _log.debug("Bob replied to us (Alice) in-session " + fromPeer);
            } else {
                // TODO check Bob version, drop if >= 0.9.52
                if (_log.shouldDebug())
                    _log.debug("Bob replied to us (Alice) with intro key " + from + ' ' + fromPeer);
            }

            int ipSize = testInfo.readIPSize();
            boolean expectV6 = test.isIPv6();
            if ((!expectV6 && ipSize != 4) ||
                (expectV6 && ipSize != 16)) {
                // There appears to be an i2pd bug where Bob is sending us a zero-length IP.
                // We could proceed without setting the IP, but then when Charlie
                // sends us his message, we will think we are behind a symmetric NAT
                // because the Bob and Charlie IPs won't match.
                // Stop the test.
                // Sometimes, the first response has an IP but a later one does not,
                // check every time.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bad IP length " + ipSize + " from bob's reply: " + from);
                // reset all state
                // so testComplete() will return UNKNOWN
                test.setAlicePortFromCharlie(0);
                test.setReceiveCharlieTime(0);
                test.setReceiveBobTime(0);
                testComplete();
                return;
            }
            byte ip[] = new byte[ipSize];
            testInfo.readIP(ip, 0);
            try {
                if (test.getReceiveBobTime() <= 0) {
                    InetAddress addr = InetAddress.getByAddress(ip);
                    int testPort = testInfo.readPort();
                    if (testPort == 0)
                        throw new UnknownHostException("port 0");
                    test.setAlice(addr, testPort, null);
                } // else ignore IP/port
                test.setReceiveBobTime(_context.clock().now());

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Receive test reply from Bob: " + test);
                if (test.getAlicePortFromCharlie() > 0)
                    testComplete();
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to get our IP (length " + ipSize +
                               ") from bob's reply: " + from, uhe);
                _context.statManager().addRateData("udp.testBadIP", 1);
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
                _context.statManager().addRateData("udp.statusKnownCharlie", 1);
                honorStatus(Status.UNKNOWN, test.isIPv6());
                _currentTest = null;
                return;
            }
    
            if (test.getReceiveCharlieTime() > 0) {
                // this is our second charlie, yay!
                try {
                    int testPort = testInfo.readPort();
                    if (testPort == 0)
                        throw new UnknownHostException("port 0");
                    test.setAlicePortFromCharlie(testPort);
                    byte ip[] = new byte[testInfo.readIPSize()];
                    int ipSize = ip.length;
                    boolean expectV6 = test.isIPv6();
                    if ((!expectV6 && ipSize != 4) ||
                        (expectV6 && ipSize != 16))
                        throw new UnknownHostException("bad sz - expect v6? " + expectV6 + " act sz: " + ipSize);
                    testInfo.readIP(ip, 0);
                    InetAddress addr = InetAddress.getByAddress(ip);
                    test.setAliceIPFromCharlie(addr);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test reply from Charlie: " + test);
                    if (test.getReceiveBobTime() > 0)
                        testComplete();
                } catch (UnknownHostException uhe) {
                    if (_log.shouldWarn())
                        _log.warn("Charlie @ " + from + " said we were an invalid IP address: " + uhe.getMessage(), uhe);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                }
            } else {
                if (test.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_ALICE) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Sent too many packets on the test: " + test);
                    if (!_currentTestComplete)
                        testComplete();
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
                    test.setCharlie(InetAddress.getByAddress(from.getIP()), from.getPort(), null);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Receive test from Charlie: " + test);
                    sendTestToCharlie();
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Charlie's IP is b0rked: " + from);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                }
            }
        }
    }
    
    /**
     * Evaluate the info we have and act accordingly, since the test has either timed out or
     * we have successfully received the second PeerTest from a Charlie.
     *
     * call from a synchronized method
     */
    private void testComplete() {
        _currentTestComplete = true;
        PeerTestState test = _currentTest;

        // Don't do this or we won't call honorStatus()
        // to set the status to UNKNOWN or REJECT_UNSOLICITED
        // if (expired()) { 
        //     _currentTest = null;
        //    return;
        // }

        boolean isIPv6 = test.isIPv6();
        Status status;
        if (test.getAlicePortFromCharlie() > 0) {
            // we received a second message from charlie
            if ( (test.getAlicePort() == test.getAlicePortFromCharlie()) &&
                 (test.getAliceIP() != null) && (test.getAliceIPFromCharlie() != null) &&
                 (test.getAliceIP().equals(test.getAliceIPFromCharlie())) ) {
                status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_OK : Status.IPV4_OK_IPV6_UNKNOWN;
            } else {
                // we don't have a SNAT state for IPv6
                status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_FIREWALLED : Status.IPV4_SNAT_IPV6_UNKNOWN;
            }
        } else if (test.getReceiveCharlieTime() > 0) {
            // we received only one message from charlie
            status = Status.UNKNOWN;
        } else if (test.getReceiveBobTime() > 0) {
            // we received a message from bob but no messages from charlie
            status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_FIREWALLED : Status.IPV4_FIREWALLED_IPV6_UNKNOWN;
        } else {
            // we never received anything from bob - he is either down, 
            // ignoring us, or unable to get a Charlie to respond
            status = Status.UNKNOWN;
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Test complete: " + test);
        
        honorStatus(status, isIPv6);
        _currentTest = null;
    }
    
    /**
     * Depending upon the status, fire off different events (using received port/ip/etc as 
     * necessary).
     *
     *  @param isIPv6 Is the change an IPv6 change?
     */
    private void honorStatus(Status status, boolean isIPv6) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Test results (IPv6? " + isIPv6 + "): status = " + status);
        _transport.setReachabilityStatus(status, isIPv6);
    }
    
    /**
     * Entry point for all incoming packets. Most of the source and dest validation is here.
     *
     * SSU 1 only.
     *
     * Receive a test message of some sort from the given peer, queueing up any packet
     * that should be sent in response, or if its a reply to our own current testing,
     * adjusting our test state.
     *
     * We could be Alice, Bob, or Charlie.
     *
     * @param fromPeer non-null if an associated session was found, otherwise null
     * @param inSession true if authenticated in-session
     */
    public void receiveTest(RemoteHostId from, PeerState fromPeer, boolean inSession, UDPPacketReader reader) {
        _context.statManager().addRateData("udp.receiveTest", 1);
        byte[] fromIP = from.getIP();
        int fromPort = from.getPort();
        // no need to do these checks if we received it in-session
        if (!inSession || fromPeer == null) {
            if (!TransportUtil.isValidPort(fromPort) ||
                (!_transport.isValid(fromIP)) ||
                _transport.isTooClose(fromIP) ||
                _context.blocklist().isBlocklisted(fromIP)) {
                // spoof check, and don't respond to privileged ports
                if (_log.shouldWarn())
                    _log.warn("Invalid PeerTest address: " + Addresses.toString(fromIP, fromPort));
                _context.statManager().addRateData("udp.testBadIP", 1);
                return;
            }
        }

        UDPPacketReader.PeerTestReader testInfo = reader.getPeerTestReader();
        byte testIP[] = null;
        int testPort = testInfo.readPort();

        if (testInfo.readIPSize() > 0) {
            testIP = new byte[testInfo.readIPSize()];
            testInfo.readIP(testIP, 0);
        }

        if ((testPort > 0 && (!TransportUtil.isValidPort(testPort))) ||
            (testIP != null &&
                               ((!_transport.isValid(testIP)) ||
                                (testIP.length != 4 && testIP.length != 16) ||
                                _context.blocklist().isBlocklisted(testIP)))) {
            // spoof check, and don't respond to privileged ports
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid address in PeerTest: " + Addresses.toString(testIP, testPort));
            _context.statManager().addRateData("udp.testBadIP", 1);
            return;
        }

        // The from IP/port and message's IP/port are now validated.
        // EXCEPT that either the message's IP could be empty or the message's port could be 0.
        // Both of those cases should be checked in receiveXfromY() as appropriate.
        // Also, IP could be us, check is below.

        long nonce = testInfo.readNonce();
        PeerTestState test = _currentTest;
        if ( (test != null) && (test.getNonce() == nonce) ) {
            // we are Alice, we initiated the test
            receiveTestReply(from, fromPeer, inSession, testInfo);
            return;
        }

        // we are Bob or Charlie, we are helping Alice

        if (_throttle.shouldThrottle(fromIP)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("PeerTest throttle from " + Addresses.toString(fromIP, fromPort));
            return;
        }

        // use the same counter for both from and to IPs
        if (testIP != null && _throttle.shouldThrottle(testIP)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("PeerTest throttle to " + Addresses.toString(testIP, testPort));
            return;
        }

        Long lNonce = Long.valueOf(nonce);
        PeerTestState state = _activeTests.get(lNonce);

        if (testIP != null && _transport.isTooClose(testIP)) {
            // spoof check - have to do this after receiveTestReply(), since
            // the field should be us there.
            // Let's also eliminate anybody in the same /16
            if (_recentTests.contains(lNonce)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Got delayed reply on nonce " + nonce +
                              " from: " + Addresses.toString(fromIP, fromPort));
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Nearby address in PeerTest: " + Addresses.toString(testIP, testPort) +
                              " from: " + Addresses.toString(fromIP, fromPort) +
                              " state? " + state);
                _context.statManager().addRateData("udp.testBadIP", 1);
            }
            return;
        }
        
        if (state == null) {
            // NEW TEST
            if ( (testIP == null) || (testPort <= 0) ) {
                // we are bob, since we haven't seen this nonce before AND its coming from alice
                if (_activeTests.size() >= MAX_ACTIVE_TESTS) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Too many active tests, droppping from Alice " + Addresses.toString(fromIP, fromPort));
                    return;
                }
                if (!inSession || fromPeer == null) {
                    // Require an existing session to start a test,
                    // as a way of preventing trouble
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No session, dropping new test from Alice " + Addresses.toString(fromIP, fromPort));
                    return;
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("test IP/port are blank coming from " + from + ", assuming we are Bob and they are alice");
                receiveFromAliceAsBob(from, fromPeer, testInfo, nonce, null);
            } else {
                if (_recentTests.contains(lNonce)) {
                    // ignore the packet, as its a holdover from a recently completed locally
                    // initiated test
                } else {
                    if (_activeTests.size() >= MAX_ACTIVE_TESTS) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Too many active tests, droppping from Bob " + Addresses.toString(fromIP, fromPort));
                        return;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("We are charlie, as the testIP/port is " + Addresses.toString(testIP, testPort) + " and the state is unknown for " + nonce);
                    // we are charlie, since alice never sends us her IP and port, only bob does (and,
                    // erm, we're not alice, since it isn't our nonce)
                    receiveFromBobAsCharlie(from, fromPeer, inSession, testInfo, nonce, null);
                }
            }
        } else {
            // EXISTING TEST
            if (state.getOurRole() == BOB) {
                if (DataHelper.eq(fromIP, state.getAliceIP().getAddress()) && 
                    (fromPort == state.getAlicePort()) ) {
                    if (!inSession || fromPeer == null) {
                        // Still should be in-session
                        if (_log.shouldWarn())
                            _log.warn("No session, dropping test from Alice " + Addresses.toString(fromIP, fromPort));
                        return;
                    }
                    receiveFromAliceAsBob(from, fromPeer, testInfo, nonce, state);
                } else if (DataHelper.eq(fromIP, state.getCharlieIP().getAddress()) && 
                           (fromPort == state.getCharliePort()) ) {
                    receiveFromCharlieAsBob(from, fromPeer, inSession, state);
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Received from a fourth party as bob!  alice: " + state.getAliceIP() + ", charlie: " + state.getCharlieIP() + ", dave: " + from);
                }
            } else if (state.getOurRole() == CHARLIE) {
                if ( (testIP == null) || (testPort <= 0) ) {
                    receiveFromAliceAsCharlie(from, testInfo, nonce, state);
                } else {
                    receiveFromBobAsCharlie(from, fromPeer, inSession, testInfo, nonce, state);
                }
            }
        }
    }

    /**
     * Entry point for all out-of-session packets, messages 5-7 only.
     *
     * SSU 2 only.
     *
     * Receive a test message of some sort from the given peer, queueing up any packet
     * that should be sent in response, or if its a reply to our own current testing,
     * adjusting our test state.
     *
     * We could be Alice or Charlie.
     *
     * @param from non-null
     * @param packet header already decrypted
     * @since 0.9.54
     */
    public void receiveTest(RemoteHostId from, UDPPacket packet) {
        DatagramPacket pkt = packet.getPacket();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rcvConnID = DataHelper.fromLong8(data, off);
        long sendConnID = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        int type = data[off + TYPE_OFFSET] & 0xff;
        if (type != PEER_TEST_FLAG_BYTE)
            return;
        byte[] introKey = _transport.getSSU2StaticIntroKey();
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(introKey, 0);
        long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
        chacha.setNonce(n);
        try {
            // decrypt in-place
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            int payloadLen = len - (LONG_HEADER_SIZE + MAC_LEN);
            SSU2Payload.PayloadCallback cb = new PTCallback(from);
            SSU2Payload.processPayload(_context, cb, data, off + LONG_HEADER_SIZE, payloadLen, false);
        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("Bad PeerTest packet:\n" + HexDump.dump(data, off, len), e);
        } finally {
            chacha.destroy();
        }
    }

    /**
     * Entry point for all incoming packets.
     *
     * SSU 2 only.
     *
     * Receive a test message of some sort from the given peer, queueing up any packet
     * that should be sent in response, or if its a reply to our own current testing,
     * adjusting our test state.
     *
     * We could be Alice, Bob, or Charlie.
     *
     * @param from non-null
     * @param fromPeer non-null if an associated session was found, otherwise null
     * @param msg 1-7
     * @param status 0 = accept, 1-255 = reject
     * @param h Alice or Charlie hash for msg 2 and 4, null for msg 1, 3, 5-7
     * @param data excludes flag, includes signature
     * @since 0.9.54
     */
    public void receiveTest(RemoteHostId from, PeerState2 fromPeer, int msg, int status, Hash h, byte[] data) {
        PeerTestState.Role role;
        if (data[0] == 1) {
            role = ALICE;
        } else if (data[0] == 3) {
            role = CHARLIE;
        } else {
            if (_log.shouldWarn())
                _log.warn("Bad role " + (data[0] & 0xff) + " from " + from + ' ' + fromPeer);
            return;
        }
        if (data[1] != 2) {
            if (_log.shouldWarn())
                _log.warn("Bad version " + (data[1] & 0xff) + " from " + from + ' ' + fromPeer);
            return;
        }
        long nonce = DataHelper.fromLong(data, 2, 4);
        long time = DataHelper.fromLong(data, 6, 4) * 1000;
        int iplen = data[10] & 0xff;
        if (iplen != 0 && iplen != 4 && iplen != 16) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad IP length " + iplen);
            return;
        }
        boolean isIPv6 = iplen == 16;
        byte[] testIP;
        if (iplen != 0) {
            testIP = new byte[iplen];
            System.arraycopy(data, 11, testIP, 0, iplen);
        } else {
            testIP = null;
        }
        int testPort = (int) DataHelper.fromLong(data, 11 + iplen, 2);
        Long lNonce = Long.valueOf(nonce);
        PeerTestState state;
        if (msg == 4 || msg == 5 || msg == 7)
            state = _currentTest;
        else
            state = _activeTests.get(lNonce);

        if (_log.shouldDebug())
            _log.debug("Got peer test" +
                       " msg: " + msg +
                       " status: " + status +
                       " hash: " + h +
                       " role: " + role +
                       " nonce: " + nonce +
                       " time: " + DataHelper.formatTime(time) +
                       " ip/port: " + Addresses.toString(testIP, testPort) +
                       " from " + fromPeer +
                       " state: " + state);

        byte[] fromIP = from.getIP();
        int fromPort = from.getPort();
        // no need to do these checks if we received it in-session
        if (fromPeer == null) {
            if (!TransportUtil.isValidPort(fromPort) ||
                (!_transport.isValid(fromIP)) ||
                _transport.isTooClose(fromIP) ||
                _context.blocklist().isBlocklisted(fromIP)) {
                // spoof check, and don't respond to privileged ports
                if (_log.shouldWarn())
                    _log.warn("Invalid PeerTest address: " + Addresses.toString(fromIP, fromPort));
                _context.statManager().addRateData("udp.testBadIP", 1);
                return;
            }
        }
        if (_throttle.shouldThrottle(fromIP)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("PeerTest throttle from " + Addresses.toString(fromIP, fromPort));
            return;
        }

        // common checks


        // use the same counter for both from and to IPs
        if (_throttle.shouldThrottle(testIP)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("PeerTest throttle to " + Addresses.toString(testIP, testPort));
            return;
        }

        if (msg >= 1 && msg <= 4) {
            if (fromPeer == null) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg " + msg + " out-of-session from " + from);
                return;
            }
        } else {
            if (fromPeer != null) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg " + msg + " in-session from " + fromPeer);
                return;
            }
        }
        if (msg < 3) {
            if (state != null) {
                if (_log.shouldWarn())
                    _log.warn("Dup msg " + msg + " from " + fromPeer);
                return;
            }
            if (_activeTests.size() >= MAX_ACTIVE_TESTS) {
                if (_log.shouldWarn())
                    _log.warn("Too many active tests, droppping from " + Addresses.toString(fromIP, fromPort));
                UDPPacket packet;
                if (msg == 1)
                    packet = _packetBuilder2.buildPeerTestToAlice(SSU2Util.TEST_REJECT_BOB_LIMIT,
                                                                  Hash.FAKE_HASH, data, fromPeer);
                else
                    packet = _packetBuilder2.buildPeerTestToBob(SSU2Util.TEST_REJECT_CHARLIE_LIMIT,
                                                                data, fromPeer);
                _transport.send(packet);
                return;
            }
        } else {
            if (state == null) {
                if (_log.shouldWarn())
                    _log.warn("No state found for msg " + msg + " from " + fromPeer);
                return;
            }
        }
        long now = _context.clock().now();
        long skew = time - now;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
            if (_log.shouldWarn())
                _log.warn("Too skewed for msg " + msg + " from " + fromPeer);
            return;
        }

        switch (msg) {
            // alice to bob, in-session
            case 1: {
                if (status != 0) {
                    if (_log.shouldWarn())
                        _log.warn("Msg 1 status " + status);
                    return;
                }
                PeerState charlie = _transport.pickTestPeer(CHARLIE, fromPeer.getVersion(), isIPv6, from);
                if (charlie == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to pick a charlie (no peer), IPv6? " + isIPv6);
                    // send reject
                    UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(SSU2Util.TEST_REJECT_BOB_NO_CHARLIE,
                                                                            Hash.FAKE_HASH, data, fromPeer);
                    _transport.send(packet);
                    return;
                }
                Hash alice = fromPeer.getRemotePeer();
                RouterInfo aliceRI = _context.netDb().lookupRouterInfoLocally(alice);
                if (aliceRI == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No alice RI");
                    // send reject
                    UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(SSU2Util.TEST_REJECT_BOB_UNSPEC,
                                                                            Hash.FAKE_HASH, data, fromPeer);
                    _transport.send(packet);
                    return;
                }
                InetAddress aliceIP = fromPeer.getRemoteIPAddress();
                int alicePort = fromPeer.getRemotePort();
                state = new PeerTestState(BOB, null, isIPv6, nonce, now);
                state.setAlice(fromPeer);
                state.setAlice(aliceIP, alicePort, alice);
                state.setCharlie(charlie.getRemoteIPAddress(), charlie.getRemotePort(), charlie.getRemotePeer());
                state.setReceiveAliceTime(now);
                state.setLastSendTime(now);
                _activeTests.put(lNonce, state);
                // send alice RI to charlie
                if (_log.shouldDebug())
                    _log.debug("Send Alice RI and msg 2 to charlie on " + state);
                DatabaseStoreMessage dbsm = new DatabaseStoreMessage(_context);
                dbsm.setEntry(aliceRI);
                dbsm.setMessageExpiration(now + 10*1000);
                _transport.send(dbsm, charlie);
                // forward to charlie, don't bother to validate signed data
                // FIXME this will probably get there before the RI
                UDPPacket packet = _packetBuilder2.buildPeerTestToCharlie(alice, data, (PeerState2) charlie);
                _transport.send(packet);
                break;
            }

            // bob to charlie, in-session
            case 2: {
                if (status != 0) {
                    if (_log.shouldWarn())
                        _log.warn("Msg 2 status " + status);
                    return;
                }
                InetAddress aliceIP;
                try {
                    aliceIP = InetAddress.getByAddress(testIP);
                } catch (UnknownHostException uhe) {
                    return;
                }
                RouterInfo aliceRI = null;
                SessionKey aliceIntroKey = null;
                int rcode;
                PeerState aps = _transport.getPeerState(h);
                if (aps != null && aps.isIPv6() == isIPv6) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_CONNECTED;
                } else if (_transport.getEstablisher().getInboundState(from) != null ||
                           _transport.getEstablisher().getOutboundState(from) != null) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_CONNECTED;
                } else if (_context.banlist().isBanlisted(h)) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_BANNED;
                } else if (!TransportUtil.isValidPort(testPort) ||
                          !_transport.isValid(testIP) ||
                         _transport.isTooClose(testIP) ||
                         _context.blocklist().isBlocklisted(testIP)) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_ADDRESS;
                } else {
                    // bob should have sent it to us. Don't bother to lookup
                    // remotely if he didn't, or it was out-of-order or lost.
                    aliceRI = _context.netDb().lookupRouterInfoLocally(h);
                    if (aliceRI != null) {
                        // validate signed data
                        SigningPublicKey spk = aliceRI.getIdentity().getSigningPublicKey();
                        if (SSU2Util.validateSig(_context, SSU2Util.PEER_TEST_PROLOGUE,
                                                 fromPeer.getRemotePeer(), h, data, spk)) {
                            aliceIntroKey = getIntroKey(getAddress(aliceRI, isIPv6));
                            if (aliceIntroKey != null)
                                rcode = SSU2Util.TEST_ACCEPT;
                            else
                                rcode = SSU2Util.TEST_REJECT_CHARLIE_ADDRESS;
                        } else {
                            if (_log.shouldWarn())
                                _log.warn("Signature failed msg 2\n" + aliceRI);
                            rcode = SSU2Util.TEST_REJECT_CHARLIE_SIGFAIL;
                        }
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Alice RI not found " + h);
                        rcode = SSU2Util.TEST_REJECT_CHARLIE_UNKNOWN_ALICE;
                    }
                }
                if (rcode == SSU2Util.TEST_ACCEPT) {
                    state = new PeerTestState(CHARLIE, fromPeer, isIPv6, nonce, now);
                    state.setAlice(aliceIP, testPort, h);
                    state.setAliceIntroKey(aliceIntroKey);
                    state.setReceiveBobTime(now);
                    state.setLastSendTime(now);
                    _activeTests.put(lNonce, state);
                }
                // generate our signed data
                // we sign it even if rejecting, not required though
                SigningPrivateKey spk = _context.keyManager().getSigningPrivateKey();
                data = SSU2Util.createPeerTestData(_context, fromPeer.getRemotePeer(), h,
                                                   CHARLIE, nonce, testIP, testPort, spk);
                if (data == null) {
                    if (_log.shouldWarn())
                        _log.warn("sig fail");
                     if (rcode == SSU2Util.TEST_ACCEPT)
                         _activeTests.remove(lNonce);
                     return;
                }
                UDPPacket packet = _packetBuilder2.buildPeerTestToBob(rcode, data, fromPeer);
                if (_log.shouldDebug())
                    _log.debug("Send msg 3 response " + rcode + " nonce " + lNonce + " to " + fromPeer);
                _transport.send(packet);
                if (rcode == SSU2Util.TEST_ACCEPT) {
                    // send msg 5
                    if (_log.shouldDebug())
                        _log.debug("Send msg 5 to " + Addresses.toString(testIP, testPort) + " on " + state);
                    long rcvId = (nonce << 32) | nonce;
                    long sendId = ~rcvId;
                    // send the same data we sent to Bob
                    packet = _packetBuilder2.buildPeerTestToAlice(aliceIP, testPort,
                                                                  aliceIntroKey, true,
                                                                  sendId, rcvId, data);
                    _transport.send(packet);
                }
                break;
            }

            // charlie to bob, in-session
            case 3: {
                state.setReceiveCharlieTime(now);
                state.setLastSendTime(now);
                PeerState2 alice = state.getAlice();
                Hash charlie = fromPeer.getRemotePeer();
                RouterInfo charlieRI = _context.netDb().lookupRouterInfoLocally(charlie);
                if (charlieRI != null) {
                    // send charlie RI to alice
                    if (_log.shouldDebug())
                        _log.debug("Send Charlie RI to alice on " + state);
                    DatabaseStoreMessage dbsm = new DatabaseStoreMessage(_context);
                    dbsm.setEntry(charlieRI);
                    dbsm.setMessageExpiration(now + 10*1000);
                    _transport.send(dbsm, alice);
                } else  {
                    // oh well, maybe alice has it
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No charlie RI");
                }
                // forward to alice, don't bother to validate signed data
                // FIXME this will probably get there before the RI
                if (_log.shouldDebug())
                    _log.debug("Send msg 4 to alice on " + state);
                UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(status, charlie, data, alice);
                _transport.send(packet);
                // we are done
                _activeTests.remove(lNonce);
                break;
            }

            // bob to alice, in-session
            case 4: {
                PeerTestState test = _currentTest;
                if (test == null || test.getNonce() != nonce) {
                    if (_log.shouldWarn())
                        _log.warn("Test nonce mismatch? " + nonce);
                    return;
                }
                test.setReceiveBobTime(now);
                test.setLastSendTime(now);
                boolean fail = false;
                RouterInfo charlieRI = null;
                SessionKey charlieIntroKey = null;
                InetAddress charlieIP = null;
                int charliePort = 0;
                PeerState cps = _transport.getPeerState(h);
                if (status != 0) {
                    if (_log.shouldInfo())
                        _log.info("Msg 4 status " + status + ' ' + test);
                } else if (cps != null && cps.isIPv6() == isIPv6) {
                    if (_log.shouldInfo())
                        _log.info("Charlie is connected " + test);
                } else if (_transport.getEstablisher().getInboundState(from) != null ||
                           _transport.getEstablisher().getOutboundState(from) != null) {
                    if (_log.shouldInfo())
                        _log.info("Charlie is connecting " + test);
                } else if (_context.banlist().isBanlisted(h)) {
                    if (_log.shouldInfo())
                        _log.info("Test fail ban " + h);
                } else {
                    // bob should have sent it to us. Don't bother to lookup
                    // remotely if he didn't, or it was out-of-order or lost.
                    charlieRI = _context.netDb().lookupRouterInfoLocally(h);
                    if (charlieRI != null) {
                        // validate signed data
                        SigningPublicKey spk = charlieRI.getIdentity().getSigningPublicKey();
                        if (SSU2Util.validateSig(_context, SSU2Util.PEER_TEST_PROLOGUE,
                                                 fromPeer.getRemotePeer(), _context.routerHash(), data, spk)) {
                            RouterAddress ra = getAddress(charlieRI, isIPv6);
                            if (ra != null) {
                                charlieIntroKey = getIntroKey(ra);
                                if (charlieIntroKey == null && _log.shouldWarn())
                                    _log.warn("Charlie intro key not found: " + test + '\n' + charlieRI);
                                byte[] ip = ra.getIP();
                                if (ip != null) {
                                    if (!_transport.isValid(ip) ||
                                        _transport.isTooClose(ip) ||
                                        _context.blocklist().isBlocklisted(ip)) {
                                        if (_log.shouldInfo())
                                            _log.info("Test fail ban/ip " + Addresses.toString(ip));
                                    } else {
                                        try {
                                           charlieIP = InetAddress.getByAddress(ip);
                                        } catch (UnknownHostException uhe) {
                                           if (_log.shouldWarn())
                                                _log.warn("Charlie IP not found: " + test + '\n' + ra, uhe);
                                        }
                                    }
                                } else {
                                    if (_log.shouldWarn())
                                        _log.warn("Charlie IP not found: " + test + '\n' + ra);
                                }
                                charliePort = ra.getPort();
                                if (!TransportUtil.isValidPort(charliePort)) {
                                    if (_log.shouldWarn())
                                        _log.warn("Charlie port bad: " + test + '\n' + ra);
                                    charliePort = 0;
                                }
                            } else {
                                if (_log.shouldWarn())
                                    _log.warn("Charlie address not found" + test + '\n' + charlieRI);
                            }
                        } else {
                            if (_log.shouldWarn())
                                _log.warn("Signature failed msg 4 " + test + '\n' + charlieRI);
                        }
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Charlie RI not found" + test + ' ' + h);
                    }
                }
                if (charlieIntroKey == null || charlieIP == null || charliePort <= 0) {
                    // reset all state
                    // so testComplete() will return UNKNOWN
                    test.setAlicePortFromCharlie(0);
                    test.setReceiveCharlieTime(0);
                    test.setReceiveBobTime(0);
                    testComplete();
                    return;
                }
                test.setCharlie(charlieIP, charliePort, h);
                test.setCharlieIntroKey(charlieIntroKey);
                if (test.getReceiveCharlieTime() > 0) {
                    // send msg 6
                    if (_log.shouldDebug())
                        _log.debug("Send msg 6 to charlie on " + test);
                    synchronized(this) {
                        sendTestToCharlie();
                    }
                } else {
                    // delay, await msg 5
                    if (_log.shouldDebug())
                        _log.debug("Got msg 4 before msg 5 on " + test);
                }
                break;
            }

            // charlie to alice, out-of-session
            case 5: {
                PeerTestState test = _currentTest;
                if (test == null || test.getNonce() != nonce) {
                    if (_log.shouldWarn())
                        _log.warn("Test nonce mismatch? " + nonce);
                    return;
                }
                test.setReceiveCharlieTime(now);
                test.setAlicePortFromCharlie(testPort);
                try {
                    InetAddress addr = InetAddress.getByAddress(testIP);
                    test.setAliceIPFromCharlie(addr);
                } catch (UnknownHostException uhe) {
                    if (_log.shouldWarn())
                        _log.warn("Charlie @ " + from + " said we were an invalid IP address: " + uhe.getMessage(), uhe);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                }
                if (test.getCharlieIntroKey() != null) {
                    // send msg 6
                    if (_log.shouldDebug())
                        _log.debug("Send msg 6 to charlie on " + test);
                    synchronized(this) {
                        sendTestToCharlie();
                    }
                } else {
                    // we haven't gotten message 4 yet
                    if (_log.shouldDebug())
                        _log.debug("Got msg 5 before msg 4 on " + test);
                }
                break;
            }

            // alice to charlie, out-of-session
            case 6: {
                state.setReceiveAliceTime(now);
                state.setLastSendTime(now);
                // send msg 7
                long rcvId = (nonce << 32) | nonce;
                long sendId = ~rcvId;
                InetAddress addr = state.getAliceIP();
                int alicePort = state.getAlicePort();
                byte[] aliceIP = addr.getAddress();
                iplen = aliceIP.length;
                data = new byte[13 + iplen];
                data[0] = 3;  // charlie
                data[1] = 2;  // version
                DataHelper.toLong(data, 2, 4, nonce);
                DataHelper.toLong(data, 6, 4, now / 1000);
                data[10] = (byte) iplen;
                System.arraycopy(aliceIP, 0, data, 11, iplen);
                DataHelper.toLong(data, 11 + iplen, 2, alicePort);
                if (_log.shouldDebug())
                    _log.debug("Send msg 7 to alice on " + state);
                UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(addr, alicePort,
                                                                        state.getAliceIntroKey(), false,
                                                                        sendId, rcvId, data);
                _transport.send(packet);
                break;
            }

            // charlie to alice, out-of-session
            case 7: {
                PeerTestState test = _currentTest;
                if (test == null || test.getNonce() != nonce) {
                    if (_log.shouldWarn())
                        _log.warn("Test nonce mismatch? " + nonce);
                    return;
                }
                if (test.getReceiveCharlieTime() <= 0) {
                   // ??
                }
                // this is our second charlie, yay!
                test.setReceiveCharlieTime(now);
                test.setAlicePortFromCharlie(testPort);
                try {
                    InetAddress addr = InetAddress.getByAddress(testIP);
                    test.setAliceIPFromCharlie(addr);
                } catch (UnknownHostException uhe) {
                    if (_log.shouldWarn())
                        _log.warn("Charlie @ " + from + " said we were an invalid IP address: " + uhe.getMessage(), uhe);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                }
                if (test.getReceiveBobTime() > 0)
                    testComplete();
                break;
            }

            default:
                return;
        }
    }

    /**
     *  Get an address out of a RI. SSU2 only.
     *
     *  @since 0.9.54
     */
    private RouterAddress getAddress(RouterInfo ri, boolean isIPv6) {
        List<RouterAddress> addrs = _transport.getTargetAddresses(ri);
        RouterAddress ra = null;
        for (RouterAddress addr : addrs) {
            // skip SSU 1 address w/o "s"
            if (addrs.size() > 1 && addr.getTransportStyle().equals("SSU") && addr.getOption("s") == null)
                continue;
            String host = addr.getHost();
            if (host == null)
                host = "";
            String caps = addr.getOption(UDPAddress.PROP_CAPACITY);
            if (caps == null)
                caps = "";
            if (isIPv6) {
                if (!host.contains(":") && !caps.contains(TransportImpl.CAP_IPV6))
                    continue;
            } else {
                if (!host.contains(".") && !caps.contains(TransportImpl.CAP_IPV4))
                    continue;
            }
            ra = addr;
            break;
        }
        return ra;
    }

    /**
     *  Get an intro key out of an address. SSU2 only.
     *
     *  @since 0.9.54
     */
    private static SessionKey getIntroKey(RouterAddress ra) {
        if (ra == null)
            return null;
        String siv = ra.getOption("i");
        if (siv == null)
            return null;
        byte[] ik = Base64.decode(siv);
        if (ik == null)
            return null;
        return new SessionKey(ik);
    }
    
    // Below here are methods for when we are Bob or Charlie

    /**
     * The packet's IP/port does not match the IP/port included in the message, 
     * so we must be Charlie receiving a PeerTest from Bob.
     *
     * SSU 1 only.
     *  
     * @param bob non-null if received in-session, otherwise null
     * @param inSession true if authenticated in-session
     * @param state null if new
     */
    private void receiveFromBobAsCharlie(RemoteHostId from, PeerState bob, boolean inSession,
                                         UDPPacketReader.PeerTestReader testInfo, long nonce, PeerTestState state) {
        if (!inSession || bob == null) {
            if (_log.shouldWarn())
                _log.warn("Received from bob (" + from + ") as charlie w/o session");
            return;
        }

        long now = _context.clock().now();
        int sz = testInfo.readIPSize();
        boolean isNew = false;
        if (state == null) {
            isNew = true;
            state = new PeerTestState(CHARLIE, bob, sz == 16, nonce, now);
        } else {
            if (state.getReceiveBobTime() > now - (RESEND_TIMEOUT / 2)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Too soon, not retransmitting: " + state);
                return;
            }
        }

        // TODO should only do most of this if isNew
        byte aliceIPData[] = new byte[sz];
        try {
            testInfo.readIP(aliceIPData, 0);
            boolean expectV6 = state.isIPv6();
            if ((!expectV6 && sz != 4) ||
                (expectV6 && sz != 16))
                throw new UnknownHostException("bad sz - expect v6? " + expectV6 + " act sz: " + sz);
            int alicePort = testInfo.readPort();
            if (alicePort == 0)
                throw new UnknownHostException("port 0");
            InetAddress aliceIP = InetAddress.getByAddress(aliceIPData);
            InetAddress bobIP = InetAddress.getByAddress(from.getIP());
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
         
            state.setAlice(aliceIP, alicePort, null);
            state.setAliceIntroKey(aliceIntroKey);
            state.setReceiveBobTime(now);
            
            // we send two packets below, but increment just once
            if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_CHARLIE) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Too many, not retransmitting: " + state);
                return;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from Bob: " + state);
            
            if (isNew) {
                _activeTests.put(Long.valueOf(nonce), state);
                _context.simpleTimer2().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
            }

            state.setLastSendTime(now);
            UDPPacket packet = _packetBuilder.buildPeerTestToBob(bobIP, from.getPort(), aliceIP, alicePort,
                                                                 aliceIntroKey, nonce,
                                                                 state.getBobCipherKey(), state.getBobMACKey());
            _transport.send(packet);
            
            packet = _packetBuilder.buildPeerTestToAlice(aliceIP, alicePort, aliceIntroKey,
                                                         _transport.getIntroKey(), nonce);
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from + ", ip size: " + sz + " ip val: " + Base64.encode(aliceIPData), uhe);
            _context.statManager().addRateData("udp.testBadIP", 1);
        }
    }

    /**
     * The PeerTest message came from the peer referenced in the message (or there wasn't
     * any info in the message), plus we are not acting as Charlie (so we've got to be Bob).
     *
     * SSU 1 only.
     *
     * testInfo IP/port ignored
     *
     * @param alice non-null
     * @param state null if new
     */
    private void receiveFromAliceAsBob(RemoteHostId from, PeerState alice, UDPPacketReader.PeerTestReader testInfo,
                                       long nonce, PeerTestState state) {
        // we are Bob, so pick a (potentially) Charlie and send Charlie Alice's info
        PeerState charlie;
        RouterInfo charlieInfo = null;
        int sz = from.getIP().length;
        boolean isIPv6 = sz == 16;
        if (state == null) { // pick a new charlie
            //if (from.getIP().length != 4) {
            //    if (_log.shouldLog(Log.WARN))
            //        _log.warn("PeerTest over IPv6 from Alice as Bob? " + from);
            //    return;
            //}
            charlie = _transport.pickTestPeer(CHARLIE, alice.getVersion(), isIPv6, from);
        } else {
            charlie = _transport.getPeerState(new RemoteHostId(state.getCharlieIP().getAddress(), state.getCharliePort()));
        }
        if (charlie == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to pick a charlie (no peer), IPv6? " + isIPv6);
            return;
        }
        charlieInfo = _context.netDb().lookupRouterInfoLocally(charlie.getRemotePeer());
        if (charlieInfo == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to pick a charlie (no RI), IPv6? " + isIPv6);
            return;
        }
        
        // TODO should only do most of this if isNew
        InetAddress aliceIP = null;
        SessionKey aliceIntroKey = null;
        try {
            aliceIP = InetAddress.getByAddress(from.getIP());
            aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);

            RouterAddress raddr = _transport.getTargetAddress(charlieInfo);
            if (raddr == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to pick a charlie (no addr), IPv6? " + isIPv6);
                return;
            }
            UDPAddress addr = new UDPAddress(raddr);
            byte[] ikey = addr.getIntroKey();
            if (ikey == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to pick a charlie (no ikey), IPv6? " + isIPv6);
                return;
            }
            SessionKey charlieIntroKey = new SessionKey(ikey);
            
            //UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, charlieIntroKey, nonce);
            //_transport.send(packet);

            long now = _context.clock().now();
            boolean isNew = false;
            if (state == null) {
                isNew = true;
                state = new PeerTestState(BOB, null, isIPv6, nonce, now);
            } else {
                if (state.getReceiveAliceTime() > now - (RESEND_TIMEOUT / 2)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Too soon, not retransmitting: " + state);
                    return;
                }
            }
            state.setAlice(aliceIP, from.getPort(), null);
            state.setAliceIntroKey(aliceIntroKey);
            state.setAliceKeys(alice.getCurrentCipherKey(), alice.getCurrentMACKey());
            state.setCharlie(charlie.getRemoteIPAddress(), charlie.getRemotePort(), null);
            state.setCharlieIntroKey(charlieIntroKey);
            state.setReceiveAliceTime(now);
            
            if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_BOB) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Too many, not retransmitting: " + state);
                return;
            }
            
            if (isNew) {
                _activeTests.put(Long.valueOf(nonce), state);
                _context.simpleTimer2().addEvent(new RemoveTest(nonce), MAX_CHARLIE_LIFETIME);
            }
            
            state.setLastSendTime(now);
            UDPPacket packet = _packetBuilder.buildPeerTestToCharlie(aliceIP, from.getPort(), aliceIntroKey, nonce, 
                                                                     charlie.getRemoteIPAddress(), 
                                                                     charlie.getRemotePort(), 
                                                                     charlie.getCurrentCipherKey(), 
                                                                     charlie.getCurrentMACKey());
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from Alice: " + state);
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
            _context.statManager().addRateData("udp.testBadIP", 1);
        }
    }
    
    /**
     * The PeerTest message came from one of the Charlies picked for an existing test, so send Alice the
     * packet verifying participation.
     *
     * testInfo IP/port ignored
     *
     * @param fromPeer non-null if an associated session was found, otherwise null
     * @param inSession true if authenticated in-session
     * @param state non-null
     */
    private void receiveFromCharlieAsBob(RemoteHostId from, PeerState charlie, boolean inSession, PeerTestState state) {
        if (!inSession || charlie == null) {
            if (_log.shouldWarn())
                _log.warn("Received from charlie (" + from + ") as bob w/o session");
            return;
        }

        long now = _context.clock().now();
        if (state.getReceiveCharlieTime() > now - (RESEND_TIMEOUT / 2)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too soon, not retransmitting: " + state);
            return;
        }

        if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_BOB) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too many, not retransmitting: " + state);
            return;
        }
        state.setReceiveCharlieTime(now);
        state.setLastSendTime(now);
        
        // In-session as of 0.9.52
        UDPPacket packet = _packetBuilder.buildPeerTestToAlice(state.getAliceIP(), state.getAlicePort(),
                                                               state.getAliceCipherKey(), state.getAliceMACKey(),
                                                               state.getCharlieIntroKey(), state.getNonce());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive from Charlie, sending Alice back the OK: " + state);

        _transport.send(packet);
    }
    
    /** 
     * We are charlie, so send Alice her PeerTest message  
     *
     * SSU 1 only.
     *
     * testInfo IP/port ignored
     * @param state non-null
     */
    private void receiveFromAliceAsCharlie(RemoteHostId from, UDPPacketReader.PeerTestReader testInfo,
                                           long nonce, PeerTestState state) {
        long now = _context.clock().now();
        if (state.getReceiveAliceTime() > now - (RESEND_TIMEOUT / 2)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too soon, not retransmitting: " + state);
            return;
        }

        if (state.incrementPacketsRelayed() > MAX_RELAYED_PER_TEST_CHARLIE) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too many, not retransmitting: " + state);
            return;
        }
        state.setReceiveAliceTime(now);
        state.setLastSendTime(now);

        try {
            InetAddress aliceIP = InetAddress.getByAddress(from.getIP());
            SessionKey aliceIntroKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
            testInfo.readIntroKey(aliceIntroKey.getData(), 0);
            UDPPacket packet = _packetBuilder.buildPeerTestToAlice(aliceIP, from.getPort(), aliceIntroKey, _transport.getIntroKey(), nonce);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive from Alice: " + state);
            
            _transport.send(packet);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build the aliceIP from " + from, uhe);
            _context.statManager().addRateData("udp.testBadIP", 1);
        }
    }
    
    /** 
     * forget about charlie's nonce after a short while.
     */
    private class RemoveTest implements SimpleTimer.TimedEvent {
        private final long _nonce;

        public RemoveTest(long nonce) {
            _nonce = nonce;
        }

        public void timeReached() {
                _activeTests.remove(Long.valueOf(_nonce));
        }
    }

    /**
     *  @since 0.9.54
     */
    private class PTCallback implements SSU2Payload.PayloadCallback {
        private final RemoteHostId _from;
        public long _timeReceived;
        public byte[] _aliceIP;
        public int _alicePort;

        public PTCallback(RemoteHostId from) {
            _from = from;
        }

        public void gotDateTime(long time) {
            _timeReceived = time;
        }

        public void gotOptions(byte[] options, boolean isHandshake) {}

        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotAddress(byte[] ip, int port) {
            _aliceIP = ip;
            _alicePort = port;
        }

        public void gotRelayTagRequest() {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotRelayTag(long tag) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotRelayRequest(byte[] data) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotRelayResponse(int status, byte[] data) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotRelayIntro(Hash aliceHash, byte[] data) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
            receiveTest(_from, null, msg, status, h, data);
        }

        public void gotToken(long token, long expires) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotI2NP(I2NPMessage msg) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotFragment(byte[] data, int off, int len, long messageId,int frag, boolean isLast) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotACK(long ackThru, int acks, byte[] ranges) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotTermination(int reason, long count) {
            throw new IllegalStateException("Bad block in PT");
        }
    }
}
