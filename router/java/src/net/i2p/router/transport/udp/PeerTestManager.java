package net.i2p.router.transport.udp;

import java.io.IOException;
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
import net.i2p.util.SimpleTimer2;
import net.i2p.util.VersionComparator;

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
    /** longest we will keep track of test as Bob to forward response from Charlie */
    private static final int MAX_BOB_LIFETIME = 10*1000;

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

    // special markers for SSU2 when Charlie is firewalled
    private static final InetAddress PENDING_IP;
    static {
        InetAddress p = null;
        try { p = InetAddress.getByName("0.0.0.1"); } catch (UnknownHostException uhe) {}
        PENDING_IP = p;
    }
    private static final int PENDING_PORT = 99999;

    // Preliminary
    private static final boolean ENABLE_SSU2_SYMNAT_TEST = true;


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
     *  @return true if we successfully started a test
     */
    public synchronized boolean runTest(PeerState bob) {
        if (_currentTest != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We are already running a test: " + _currentTest + ", aborting test with bob = " + bob);
            return false;
        }
        InetAddress bobIP = bob.getRemoteIPAddress();
        if (_transport.isTooClose(bobIP.getAddress())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not running test with Bob too close to us " + bobIP);
            return false;
        }
        PeerTestState test = new PeerTestState(ALICE, bob, bobIP instanceof Inet6Address,
                                               _context.random().nextLong(MAX_NONCE),
                                               _context.clock().now());

            PeerState2 b2 = (PeerState2) bob;
            // We test our current address, NOT the IP we have with Bob, which may have changed since,
            // especially with IPv6 transient addresses,
            // but there also could be a connection migration in progress.
            boolean ipv6 = b2.isIPv6();
            byte[] ourIP = b2.getOurIP();
            int ourPort = b2.getOurPort();
            RouterAddress ra = _transport.getCurrentExternalAddress(ipv6);
            if (ra != null) {
                byte[] testIP = ra.getIP();
                if (testIP != null) {
                    // do a comparison just for logging, and then switch
                    int testPort = ra.getPort();
                    if (ourPort != testPort || !DataHelper.eq(ourIP, testIP)) {
                        if (_log.shouldWarn())
                            _log.warn("Test IP mismatch: " + Addresses.toString(testIP, testPort) +
                                      ", ours with Bob: " + Addresses.toString(ourIP, ourPort) + " on " + test);
                        ourIP = testIP;
                        ourPort = testPort;
                        // this should still work
                    }
                }
            }

            try {
                InetAddress addr = InetAddress.getByAddress(ourIP);
                test.setAlice(addr, ourPort, _context.routerHash());
            } catch (UnknownHostException uhe) {
                if (_log.shouldWarn())
                    _log.warn("Unable to get our IP", uhe);
                return false;
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
        
        new ContinueTest(test.getNonce());
        return true;
    }
    
    /**
     * SSU 1 or 2. We are Alice.
     */
    private class ContinueTest extends SimpleTimer2.TimedEvent {
        private final long _nonce;

        /** schedules itself */
        public ContinueTest(long nonce) {
            super(_context.simpleTimer2());
            _nonce = nonce;
            schedule(RESEND_TIMEOUT);
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
                long now = _context.clock().now();
                long timeSinceSend = now - state.getLastSendTime();
                if (timeSinceSend >= RESEND_TIMEOUT) {
                    int sent = state.incrementPacketsRelayed();
                    if (sent > MAX_RELAYED_PER_TEST_ALICE) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Sent too many packets: " + state);
                        if (!_currentTestComplete)
                            testComplete();
                        return;
                    }
                    long bobTime = state.getReceiveBobTime();
                    long charlieTime = state.getReceiveCharlieTime();
                    if (bobTime <= 0 && charlieTime <= 0) {
                        // no message from Bob or Charlie yet, send it again
                        sendTestToBob();
                    } else if (charlieTime <= 0) {
                        // received from Bob, but no reply from Charlie.  send it to 
                        // Bob again so he pokes Charlie
                        // We don't resend to Bob for SSU2; Charlie will retransmit.
                        if (ENABLE_SSU2_SYMNAT_TEST) {
                            // if version 2 and it's been long enough, and Charlie isn't firewalled, send msg 6 anyway
                            // This allows us to detect Symmetric NAT.
                            // We don't have his IP/port if he's firewalled, and we wouldn't trust his answer
                            // anyway as he could be symmetric natted.
                            // After this, we will ignore any msg 5 received
                            if (now - bobTime > 5000 && state.getCharliePort() != PENDING_PORT) {
                                if (_log.shouldWarn())
                                    _log.warn("Continuing test w/o msg 5: " + state);
                                sendTestToCharlie();
                            }
                        }
                    } else if (bobTime <= 0) {
                        // received from Charlie, but no reply from Bob.  Send it to 
                        // Bob again so he retransmits his reply.
                        // Bob handles dups / retx as of 0.9.57
                        //if (state.getBob().getVersion() == 1)
                            sendTestToBob();
                        // TODO if version 1 and long enough, send msg 6 anyway
                        // For version 2, we can't send msg 6 without knowing charlie's intro key
                    } else {
                        // received from both Bob and Charlie, but we haven't received a
                        // second message from Charlie yet
                        if (state.getCharliePort() != PENDING_PORT)
                            sendTestToCharlie();
                        // else msg 5 wasn't from a valid ip/port ???
                    }
                    if (bobTime > 0 && charlieTime <= 0) {
                        if (state.getBeginTime() + MAX_CHARLIE_LIFETIME < now) {
                            if (!_currentTestComplete)
                                testComplete();
                            return;
                        }
                        // earlier because charlie will go away at 15 sec
                        // retx at 4, 6, 9, 13 elapsed time
                        reschedule(sent*1000);
                    } else {
                        // retx at 4, 10, 17, 25 elapsed time
                        reschedule(RESEND_TIMEOUT + (sent*1000));
                    }
                } else {
                    reschedule(RESEND_TIMEOUT - timeSinceSend);
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
            PeerState bob = test.getBob();

                PeerState2 bob2 = (PeerState2) bob;
                // only create this once
                byte[] data = test.getTestData();
                if (data == null) {
                    SigningPrivateKey spk = _context.keyManager().getSigningPrivateKey();
                    data = SSU2Util.createPeerTestData(_context, bob2.getRemotePeer(), null,
                                                       ALICE, test.getNonce(),
                                                       test.getAliceIP().getAddress(), test.getAlicePort(), spk);
                    if (data == null) {
                        if (_log.shouldWarn())
                            _log.warn("sig fail");
                         testComplete();
                         return;
                    }
                    test.setTestData(data);
                }
                try {
                    packet = _packetBuilder2.buildPeerTestFromAlice(data, bob2);
                } catch (IOException ioe) {
                    fail();
                    return;
                }

            _transport.send(packet);
            long now = _context.clock().now();
            test.setLastSendTime(now);
            bob.setLastSendTime(now);
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
            long now = _context.clock().now();
            test.setLastSendTime(now);
            test.setSendCharlieTime(now);

                long nonce = test.getNonce();
                long rcvId = (nonce << 32) | nonce;
                long sendId = ~rcvId;
                InetAddress addr = test.getAliceIP();
                int alicePort = test.getAlicePort();
                byte[] aliceIP = addr.getAddress();
                int iplen = aliceIP.length;
                byte[] data = new byte[12 + iplen];
                data[0] = 2;  // version
                DataHelper.toLong(data, 1, 4, nonce);
                DataHelper.toLong(data, 5, 4, _context.clock().now() / 1000);
                data[9] = (byte) (iplen + 2);
                DataHelper.toLong(data, 10, 2, alicePort);
                System.arraycopy(aliceIP, 0, data, 12, iplen);
                UDPPacket packet = _packetBuilder2.buildPeerTestFromAlice(test.getCharlieIP(), test.getCharliePort(),
                                                                test.getCharlieIntroKey(),
                                                                sendId, rcvId, data);

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
     *  Reset all state and call testComplete(). We are Alice.
     *
     *  call from a synchronized method
     *
     *  @since 0.9.57
     */
    private void fail() {
        // so testComplete() will return UNKNOWN
        PeerTestState test = _currentTest;
        if (test == null)
            return;
        test.setAlicePortFromCharlie(0);
        test.setReceiveCharlieTime(0);
        test.setReceiveBobTime(0);
        testComplete();
    }    

    /**
     * Evaluate the info we have and act accordingly, since the test has either timed out or
     * we have successfully received the second PeerTest from a Charlie.
     *
     * call from a synchronized method
     *
     * Old SSU 1 test result state machine:
     * ref: SSU 1 doc http://i2p-projekt.i2p/en/docs/transport/ssu
     *
     *<pre>
     * If Alice does not get msg 5:  FIREWALLED
     *
     * If Alice gets msg 5:
     * .. If Alice does not get msg 4:  UNKNOWN (says the SSU 1 spec,
     *                                             but Java I2P proceeds for SSU 1)
     * .. If Alice does not get msg 7:  UNKNOWN
     * .. If Alice gets msgs 4/5/7 and IP/port match:  OK
     * .. If Alice gets msgs 4/5/7 and IP/port do not match:  SYMNAT
     *</pre>
     *
     * New SSU 1/2 test result state machine:
     *
     *<pre>
     * If Alice does not get msg 5:
     * .. If Alice does not get msg 4:  UNKNOWN
     * .. If Alice does not get msg 7:  UNKNOWN
     * .. If Alice gets msgs 4/7 and IP/port match:  FIREWALLED
     * .. If Alice gets msgs 4/7 and IP matches, port does not match:  SYMNAT, but needs confirmation with 2nd test
     * .. If Alice gets msgs 4/7 and IP does not match, port matches:  FIREWALLED, address change?
     * .. If Alice gets msgs 4/7 and both IP and port do not match:  SYMNAT, address change?
     *
     * If Alice gets msg 5:
     * .. If Alice does not get msg 4:  OK unless currently SYMNAT, else UNKNOWN
     *                                       (in SSU2 have to stop here anyway)
     * .. If Alice does not get msg 7:  OK unless currently SYMNAT, else UNKNOWN
     * .. If Alice gets msgs 4/5/7 and IP/port match:  OK
     * .. If Alice gets msgs 4/5/7 and IP matches, port does not match:  OK, charlie is probably sym. natted
     * .. If Alice gets msgs 4/5/7 and IP does not match, port matches:  OK, address change?
     * .. If Alice gets msgs 4/5/7 and both IP and port do not match:  OK, address change?
     *</pre>
     *
     * Simplified version:
     *
     *<pre>
     * 4 5 7  Result             Notes
     * -----  ------             -----
     * n n n  UNKNOWN
     * y n n  FIREWALLED           (unless currently SYMNAT)
     * n y n  OK                   (unless currently SYMNAT, which is unlikely)
     * y y n  OK                   (unless currently SYMNAT, which is unlikely)
     * n n y  n/a                  (can't send msg 6)
     * y n y  FIREWALLED or SYMNAT (requires sending msg 6 w/o rcv msg 5)
     * n y y  n/a                  (can't send msg 6)
     * y y y  OK
     *</pre>
     *
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
        int apfc = test.getAlicePortFromCharlie();
        if (apfc > 0) {
            // we received a second message (7) from charlie
            // With ENABLE_SSU2_SYMNAT_TEST, we may not have gotten the first message (5)
            InetAddress aIP = test.getAliceIP();
            InetAddress aIPfc = test.getAliceIPFromCharlie();
            if (test.getAlicePort() == apfc &&
                aIP != null &&
                aIP.equals(aIPfc)) {
                // everything matches
                if (test.getReceiveCharlieTime() <= 0) {
                    // SSU2 ENABLE_SSU2_SYMNAT_TEST only, msg 5 not received, msg 7 received
                    status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_FIREWALLED : Status.IPV4_FIREWALLED_IPV6_UNKNOWN;
                    if (_log.shouldWarn())
                        _log.warn("Test complete w/o msg 5, " + status + " on " + test);
                } else {
                    // Got msgs 5 and 7, everything good
                    status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_OK : Status.IPV4_OK_IPV6_UNKNOWN;
                }
            } else {
                // IP/port mismatch
                if (test.getAlicePort() == apfc) {
                    // Port matched, only IP was different
                    // Dot it go
                    if (_transport.isSymNatted()) {
                        status = Status.UNKNOWN;
                    } else if (test.getReceiveCharlieTime() <= 0) {
                        // SSU2 ENABLE_SSU2_SYMNAT_TEST only, msg 5 not received, msg 7 received
                        status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_FIREWALLED : Status.IPV4_FIREWALLED_IPV6_UNKNOWN;
                    } else {
                        status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_OK : Status.IPV4_OK_IPV6_UNKNOWN;
                    }
                } else {
                    // SYMMETRIC NAT!
                    // SSU2 ENABLE_SSU2_SYMNAT_TEST only, msg 5 may or may not have been received
                    // For SSU1, or SSU2 with SNAT_TEST disabled, it's very difficult to get here,
                    // as we don't send msg 6 w/o receiving msg 5, and if we're symmetric natted, we
                    // won't get msg 5 unless the port happened to be open with Charlie due to
                    // previous activity.
                    // We don't have a SNAT state for IPv6, so set FIREWALLED.
                    status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_FIREWALLED : Status.IPV4_SNAT_IPV6_UNKNOWN;
                    if (_log.shouldWarn())
                        _log.warn("Test complete, SYMMETRIC NAT! " + status);
                }
            }
        } else if (test.getReceiveCharlieTime() > 0) {
            // we received only one message (5) from charlie
            // change in 0.9.57; previously returned UNKNOWN always
            if (_transport.isSymNatted()) {
                status = Status.UNKNOWN;
            } else {
                // assume good
                status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_OK : Status.IPV4_OK_IPV6_UNKNOWN;
            }
        } else if (test.getReceiveBobTime() > 0) {
            // We received a message from bob (4) but no messages from charlie
            // i2pd bobs pick firewalled charlies, if we don't hear from them we call it unknown,
            // otherwise we get a lot of false positives, almost always on IPv6.
            // This appears to be a i2pd bug on the charlie side?
            if (isIPv6 && PENDING_IP.equals(test.getCharlieIP())) {
                if (_log.shouldWarn())
                    _log.warn("Test complete, no response from firewalled Charlie, will retest");
                status = Status.UNKNOWN;
            } else {
                status = isIPv6 ? Status.IPV4_UNKNOWN_IPV6_FIREWALLED : Status.IPV4_FIREWALLED_IPV6_UNKNOWN;
            }
        } else {
            // we never received anything from bob or charlie,
            // ignoring us, or unable to get a Charlie to respond
            status = Status.UNKNOWN;
            // TODO disconnect from Bob if version 2?
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
            _log.info("Test results IPv" + (isIPv6 ? '6' : '4') + " status " + status);
        _transport.setReachabilityStatus(status, isIPv6);
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
            SSU2Payload.processPayload(_context, cb, data, off + LONG_HEADER_SIZE, payloadLen, false, null);
        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("Bad PeerTest packet:\n" + HexDump.dump(data, off, len), e);
        } finally {
            chacha.destroy();
        }
    }

    /**
     * Entry point for all in-session incoming packets.
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
        if (status == 0 && (msg == 2 || msg == 4) && !_context.banlist().isBanlisted(h))
            receiveTest(from, fromPeer, msg, h, data, 0);
        else
            receiveTest(from, fromPeer, msg, status, h, data, null, 0);
    }

    /**
     * Status 0 only, Msg 2 and 4 only, SSU 2 only.
     * Bob should have sent us the RI, but maybe it's in the block
     * after this, or maybe it's in a different packet.
     * Check for RI, if not found, return true to retry, unless retryCount is at the limit.
     * Creates the timer if retryCount == 0.
     *
     * We are Alice for msg 4, Charlie for msg 2.
     *
     * @return true if RI found, false to delay and retry.
     * @since 0.9.55
     */
    private boolean receiveTest(RemoteHostId from, PeerState2 fromPeer, int msg, Hash h, byte[] data, int retryCount) {
        if (retryCount < 5) {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
            if (ri == null) {
                if (_log.shouldInfo())
                    _log.info("Delay after " + retryCount + " retries, no RI for " + h.toBase64());
                if (retryCount == 0)
                    new DelayTest(from, fromPeer, msg, h, data);
                return false;
            }
        }
        receiveTest(from, fromPeer, msg, 0, h, data, null, 0);
        return true;
    }

    /** 
     * Wait for RI.
     * @since 0.9.55
     */
    private class DelayTest extends SimpleTimer2.TimedEvent {
        private final RemoteHostId from;
        private final PeerState2 fromPeer;
        private final int msg;
        private final Hash hash;
        private final byte[] data;
        private volatile int count;
        private static final long DELAY = 50;

        /** schedules itself */
        public DelayTest(RemoteHostId f, PeerState2 fp, int m, Hash h, byte[] d) {
            super(_context.simpleTimer2());
            from = f;
            fromPeer = fp;
            msg = m;
            hash = h;
            data = d;
            schedule(DELAY);
        }

        public void timeReached() {
            boolean ok = receiveTest(from, fromPeer, msg, hash, data, ++count);
            if (!ok)
                reschedule(DELAY << count);
        }
    }

    /**
     * Called from above for in-session 1-4 or the PTCallback via processPayload() for out-of-session 5-7
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
     * @param addrBlockIP only used for msgs 5-7, otherwise null
     * @param addrBlockPort only used for msgs 5-7, otherwise 0
     * @since 0.9.55
     */
    private void receiveTest(RemoteHostId from, PeerState2 fromPeer, int msg, int status, Hash h, byte[] data,
                             byte[] addrBlockIP, int addrBlockPort) {
        if (data[0] != 2) {
            if (_log.shouldWarn())
                _log.warn("Bad version " + (data[0] & 0xff) + " from " + from + ' ' + fromPeer);
            return;
        }
        long nonce = DataHelper.fromLong(data, 1, 4);
        long time = DataHelper.fromLong(data, 5, 4) * 1000;
        int iplen = data[9] & 0xff;
        if (iplen != 0 && iplen != 6 && iplen != 18) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad IP length " + iplen);
            return;
        }
        boolean isIPv6 = iplen == 18;
        int testPort;
        byte[] testIP;
        if (iplen != 0) {
            testPort = (int) DataHelper.fromLong(data, 10, 2);
            testIP = new byte[iplen - 2];
            System.arraycopy(data, 12, testIP, 0, iplen - 2);
        } else {
            testPort = 0;
            testIP = null;
            if (status == 0)
                status = 999;
        }
        Long lNonce = Long.valueOf(nonce);
        PeerTestState state;
        if (msg == 4 || msg == 5 || msg == 7)
            state = _currentTest;
        else
            state = _activeTests.get(lNonce);

        if (_log.shouldDebug())
            _log.debug("Got peer test msg " + msg +
                       " status: " + status +
                       " hash: " + (h != null ? h.toBase64() : "null") +
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

        // common checks

        long now = _context.clock().now();
        if (msg >= 1 && msg <= 4) {
            if (fromPeer == null) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg " + msg + " out-of-session from " + from);
                return;
            }
            fromPeer.setLastReceiveTime(now);
        } else {
            if (fromPeer != null) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg " + msg + " in-session from " + fromPeer);
                return;
            }
        }
        if (msg < 3) {
            if (state != null) {
                byte[] retx = state.getTestData();
                if (retx != null) {
                    if (msg == 1 && state.getSendAliceTime() > 0) {
                        if (_log.shouldDebug())
                            _log.debug("Retx msg 4 to alice on " + state);
                        // we already sent to alice, send it again
                        PeerState2 alice = state.getAlice();
                        try {
                             UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(state.getStatus(), state.getCharlieHash(), data, alice);
                            _transport.send(packet);
                            alice.setLastSendTime(now);
                            state.setSendAliceTime(now);
                            state.setReceiveAliceTime(now);
                        } catch (IOException ioe) {
                            _activeTests.remove(lNonce);
                        }
                        return;
                    } else if (msg == 2) {
                        if (_log.shouldDebug())
                            _log.debug("Retx msg 3 to bob on " + state);
                        PeerState2 bob = (PeerState2) state.getBob();
                        try {
                            UDPPacket packet = _packetBuilder2.buildPeerTestToBob(state.getStatus(), data, bob);
                            _transport.send(packet);
                            bob.setLastSendTime(now);
                            state.setReceiveBobTime(now);
                            // should we retx msg 5 also?
                        } catch (IOException ioe) {
                            _activeTests.remove(lNonce);
                        }
                        return;
                    } else {
                        // msg 1 but haven't heard from a good charlie yet
                        // TODO retransmit to the old charlie, or if it's been too long, pick a new charlie
                    }
                }
                if (_log.shouldDebug())
                    _log.debug("Dup msg " + msg + " from " + fromPeer + " on " + state);
                if (msg == 1)
                    state.setReceiveAliceTime(now);
                else
                    state.setReceiveBobTime(now);
                return;
            }
            if (_activeTests.size() >= MAX_ACTIVE_TESTS) {
                if (_log.shouldWarn())
                    _log.warn("Too many active tests, droppping from " + Addresses.toString(fromIP, fromPort));
                UDPPacket packet;
                try {
                    if (msg == 1)
                        packet = _packetBuilder2.buildPeerTestToAlice(SSU2Util.TEST_REJECT_BOB_LIMIT,
                                                                      Hash.FAKE_HASH, data, fromPeer);
                    else
                        packet = _packetBuilder2.buildPeerTestToBob(SSU2Util.TEST_REJECT_CHARLIE_LIMIT,
                                                                    data, fromPeer);
                    _transport.send(packet);
                } catch (IOException ioe) {}
                return;
            }
        } else {
            if (state == null) {
                if (_log.shouldWarn())
                    _log.warn("No state found for msg " + msg + " from " + fromPeer);
                return;
            }
        }
        long skew = time - now;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
            if (_log.shouldWarn())
                _log.warn("Too skewed for msg " + msg + " from " + fromPeer);
            return;
        }

        switch (msg) {
            // alice to bob, in-session
            // If we immediately reject with a TEST_REJECT_BOB code, we do not
            // save the test state; so if Alice retransmits, we'll do it all again.
            case 1: {
                if (status != 0) {
                    if (_log.shouldWarn())
                        _log.warn("Msg 1 status " + status);
                    return;
                }
                // IP/port checks
                if (testIP == null ||
                    isIPv6 != fromPeer.isIPv6() ||
                    !TransportUtil.isValidPort(testPort) ||
                    !_transport.isValid(testIP) ||
                    _transport.isTooClose(testIP) ||
                    // exact match for IPv4, /64 for IPv6
                    !DataHelper.eq(fromPeer.getRemoteIP(), 0, testIP, 0, isIPv6 ? 8 : 4)) {
                    if (_log.shouldWarn())
                        _log.warn("Invalid PeerTest address: " + Addresses.toString(testIP, testPort));
                    sendRejectToAlice(SSU2Util.TEST_REJECT_BOB_ADDRESS, data, fromPeer);
                    fromPeer.setLastSendTime(now);
                    return;
                }
                if (_throttle.shouldThrottle(fromIP)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("PeerTest throttle from " + Addresses.toString(fromIP, fromPort));
                    sendRejectToAlice(SSU2Util.TEST_REJECT_BOB_LIMIT, data, fromPeer);
                    fromPeer.setLastSendTime(now);
                    return;
                }
                Hash alice = fromPeer.getRemotePeer();
                RouterInfo aliceRI = _context.netDb().lookupRouterInfoLocally(alice);
                if (aliceRI == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No alice RI");
                    // send reject
                    sendRejectToAlice(SSU2Util.TEST_REJECT_BOB_UNSPEC, data, fromPeer);
                    fromPeer.setLastSendTime(now);
                    return;
                }
                // validate signed data
                // not strictly necessary but needed for debugging
                SigningPublicKey spk = aliceRI.getIdentity().getSigningPublicKey();
                if (!SSU2Util.validateSig(_context, SSU2Util.PEER_TEST_PROLOGUE,
                                          _context.routerHash(), null, data, spk)) {
                    if (_log.shouldWarn())
                        _log.warn("Signature failed msg 1\n" + aliceRI);
                    // send reject
                    sendRejectToAlice(SSU2Util.TEST_REJECT_BOB_SIGFAIL, data, fromPeer);
                    fromPeer.setLastSendTime(now);
                    return;
                }
                PeerState charlie = _transport.pickTestPeer(CHARLIE, 2, isIPv6, from);
                if (charlie == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to pick a charlie (no peer), IPv6? " + isIPv6);
                    // send reject
                    sendRejectToAlice(SSU2Util.TEST_REJECT_BOB_NO_CHARLIE, data, fromPeer);
                    fromPeer.setLastSendTime(now);
                    return;
                }
                InetAddress aliceIP = fromPeer.getRemoteIPAddress();
                int alicePort = fromPeer.getRemotePort();
                state = new PeerTestState(BOB, null, isIPv6, nonce, now);
                state.setAlice(fromPeer);
                // This is the IP/port we're connected to alice on.
                // not necessarily matching the test IP/port, but it's just for logging.
                // If we need the test ip/port for anything or want to log it, we could change it.
                state.setAlice(aliceIP, alicePort, alice);
                state.setCharlie(charlie.getRemoteIPAddress(), charlie.getRemotePort(), charlie.getRemotePeer());
                state.setReceiveAliceTime(now);
                state.setLastSendTime(now);
                // save alice-signed test data in case we need to send to another charlie
                state.setTestData(data);
                _activeTests.put(lNonce, state);
                // TODO we need a retx or pick-new-charlie timer
                new RemoveTest(lNonce, MAX_BOB_LIFETIME);
                // send alice RI to charlie
                if (_log.shouldDebug())
                    _log.debug("Send Alice RI and msg 2 to charlie on " + state);
                // forward to charlie, don't bother to validate signed data
                try {
                    sendRIandPT(aliceRI, -1, alice, data, (PeerState2) charlie, now);
                } catch (IOException ioe) {
                    sendRejectToAlice(SSU2Util.TEST_REJECT_BOB_UNSPEC, data, fromPeer);
                    _activeTests.remove(lNonce);
                }
                break;
            }

            // bob to charlie, in-session
            // If we immediately reject with a TEST_REJECT_CHARLIE code, we do not
            // save the test state; so if Alice or Bob retransmits, we'll do it all again.
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
                if (!_transport.canTestAsCharlie(isIPv6)) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_ADDRESS;
                } else if (aps != null && aps.isIPv6() == isIPv6) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_CONNECTED;
                } else if (_transport.getEstablisher().getInboundState(from) != null ||
                           _transport.getEstablisher().getOutboundState(from) != null) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_CONNECTED;
                } else if (_context.banlist().isBanlisted(h) ||
                           _context.blocklist().isBlocklisted(testIP)) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_BANNED;
                } else if (!TransportUtil.isValidPort(testPort) ||
                          !_transport.isValid(testIP) ||
                         _transport.isTooClose(testIP)) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_ADDRESS;
                } else if (_throttle.shouldThrottle(fromIP) ||
                           _throttle.shouldThrottle(testIP)) {
                    rcode = SSU2Util.TEST_REJECT_CHARLIE_LIMIT;
                } else {
                    // bob should have sent it to us. Don't bother to lookup
                    // remotely if he didn't, or it was out-of-order or lost.
                    aliceRI = _context.netDb().lookupRouterInfoLocally(h);
                    if (aliceRI != null) {
                        // validate signed data
                        SigningPublicKey spk = aliceRI.getIdentity().getSigningPublicKey();
                        if (SSU2Util.validateSig(_context, SSU2Util.PEER_TEST_PROLOGUE,
                                                 fromPeer.getRemotePeer(), null, data, spk)) {
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
                            _log.warn("Alice RI not found " + h + " for peer test from " + fromPeer);
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
                    new CharlieTimer(lNonce);
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
                try {
                    UDPPacket packet = _packetBuilder2.buildPeerTestToBob(rcode, data, fromPeer);
                    if (_log.shouldDebug())
                        _log.debug("Send msg 3 response " + rcode + " nonce " + lNonce + " to " + fromPeer);
                    _transport.send(packet);
                    fromPeer.setLastSendTime(now);
                } catch (IOException ioe) {
                    if (rcode == SSU2Util.TEST_ACCEPT)
                        _activeTests.remove(lNonce);
                    return;
                }
                if (rcode == SSU2Util.TEST_ACCEPT) {
                    // send msg 5
                    if (_log.shouldDebug())
                        _log.debug("Send msg 5 to " + Addresses.toString(testIP, testPort) + " on " + state);
                    long sendId = (nonce << 32) | nonce;
                    long rcvId = ~sendId;
                    // send the same data we sent to Bob
                    UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(aliceIP, testPort,
                                                                  aliceIntroKey, true,
                                                                  sendId, rcvId, data);
                    _transport.send(packet);
                    state.incrementPacketsRelayed();
                    // save charlie-signed test data in case we need to retransmit to alice or bob
                    state.setStatus(rcode);
                    state.setTestData(data);
                }
                break;
            }

            // charlie to bob, in-session
            case 3: {
                state.setReceiveCharlieTime(now);
                if (status != SSU2Util.TEST_ACCEPT &&
                    now - state.getBeginTime() < MAX_BOB_LIFETIME /  2) {
                    List<Hash> prev = state.getPreviousCharlies();
                    if (prev.size() < 7) {
                        PeerState charlie = _transport.pickTestPeer(CHARLIE, 2, isIPv6, from);
                        if (charlie != null && charlie != fromPeer && !prev.contains(charlie.getRemotePeer())) {
                            Hash alice = state.getAlice().getRemotePeer();
                            RouterInfo aliceRI = _context.netDb().lookupRouterInfoLocally(alice);
                            if (aliceRI != null) {
                               try {
                                    state.setCharlie(charlie.getRemoteIPAddress(), charlie.getRemotePort(), charlie.getRemotePeer());
                                    state.setLastSendTime(now);
                                    sendRIandPT(aliceRI, -1, alice, state.getTestData(), (PeerState2) charlie, now);
                                    if (_log.shouldInfo())
                                        _log.info("Charlie response " + status + " picked a new one " + charlie + " on " + state);
                                    break;
                                } catch (IOException ioe) {
                                    // give up
                                }
                            }
                        }
                    }
                }
                if (status != SSU2Util.TEST_ACCEPT && _log.shouldWarn())
                    _log.warn("Charlie response " + status + " no more to choose from on " + state);
                state.setLastSendTime(now);
                PeerState2 alice = state.getAlice();
                Hash charlie = fromPeer.getRemotePeer();
                RouterInfo charlieRI = (status == SSU2Util.TEST_ACCEPT) ? _context.netDb().lookupRouterInfoLocally(charlie) : null;
                if (charlieRI != null) {
                    // send charlie RI to alice, only if ACCEPT.
                    // Alice would need it to verify sig, but not worth the bandwidth
                    if (_log.shouldDebug())
                        _log.debug("Send Charlie RI to alice on " + state);
                    if (true) {
                        // Debug - validate signed data
                        // we forward it to alice even on failure
                        SigningPublicKey spk = charlieRI.getIdentity().getSigningPublicKey();
                        if (!SSU2Util.validateSig(_context, SSU2Util.PEER_TEST_PROLOGUE,
                                                  _context.routerHash(), alice.getRemotePeer(), data, spk)) {
                            if (_log.shouldWarn())
                                _log.warn("Signature failed msg 3\n" + charlieRI);
                        }
                    }
                } else  {
                    // oh well, maybe alice has it
                    if (status == SSU2Util.TEST_ACCEPT && _log.shouldWarn())
                        _log.warn("No charlie RI");
                }
                // forward to alice, don't bother to validate signed data
                // FIXME this will probably get there before the RI
                if (_log.shouldDebug())
                    _log.debug("Send msg 4 status " + status + " to alice on " + state);
                try {
                    sendRIandPT(charlieRI, status, charlie, data, alice, now);
                    // overwrite alice-signed test data with charlie-signed data in case we need to retransmit
                    state.setStatus(status);
                    state.setSendAliceTime(now);
                    state.setTestData(data);
                    // we should be done, but stick around for possible retx to alice
                } catch (IOException ioe) {
                    _activeTests.remove(lNonce);
                }
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
                    // TODO validate sig anyway, mark charlie unreachable if status is 69 (banned)
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
                                            charliePort = ra.getPort();
                                            if (!TransportUtil.isValidPort(charliePort)) {
                                                if (_log.shouldWarn())
                                                    _log.warn("Charlie port " + charliePort + " bad: " + test + '\n' + ra);
                                                charliePort = 0;
                                            }
                                        } catch (UnknownHostException uhe) {
                                           if (_log.shouldWarn())
                                                _log.warn("Charlie IP not found: " + test + '\n' + ra, uhe);
                                        }
                                    }
                                } else {
                                    // i2pd Bob picks firewalled Charlie, allow it
                                    // but only if B cap is published. i2pd through 0.9.61 would pick address without B cap
                                    // and i2pd charlie would agree without B cap
                                    String caps = ra.getOption(UDPAddress.PROP_CAPACITY);
                                    if (caps != null && caps.indexOf(UDPAddress.CAPACITY_TESTING) >= 0) {
                                        if (_log.shouldWarn())
                                            _log.warn("Charlie IP not found: " + test + '\n' + ra);
                                        charlieIP = PENDING_IP;
                                        charliePort = PENDING_PORT;
                                    } else {
                                        // fail
                                        if (_log.shouldWarn())
                                            _log.warn("Bob picked Charlie without B cap: " + test + '\n' + ra);
                                    }
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
                    fail();
                    return;
                }
                InetAddress oldIP = test.getCharlieIP();
                if (oldIP == null) {
                    // msg 4 before msg 5
                    test.setCharlie(charlieIP, charliePort, h);
                } else if (charlieIP == PENDING_IP) {
                    // dup msg 4 ??
                } else {
                    // msg 4 after msg 5, charlie is not firewalled
                    int oldPort = test.getCharliePort();
                    if (!charlieIP.equals(oldIP)) {
                        if (_log.shouldWarn())
                            _log.warn("Charlie IP mismatch, msg 4: " + Addresses.toString(charlieIP.getAddress(), charliePort) +
                                      ", msg 5: " + Addresses.toString(oldIP.getAddress(), oldPort) + " on " + test);
                        // stop here, assume good unless snatted
                        if (!_transport.isSymNatted()) {
                            test.setAliceIPFromCharlie(test.getAliceIP());
                            test.setAlicePortFromCharlie(test.getAlicePort());
                        }
                        testComplete();
                        return;
                    } else if (charliePort != oldPort) {
                        if (_log.shouldWarn())
                            _log.warn("Charlie port mismatch, msg 4: " + Addresses.toString(charlieIP.getAddress(), charliePort) +
                                      ", msg 5: " + Addresses.toString(oldIP.getAddress(), oldPort) + " on " + test);
                        if (TransportUtil.isValidPort(charliePort)) {
                            // Charlie is symmetric natted or confused about his port, update port and keep going
                            test.setCharlie(charlieIP, charliePort, h);
                        } else {
                            // Don't like charlie's port, stop here, assume good unless symmetric natted
                            if (!_transport.isSymNatted()) {
                                test.setAliceIPFromCharlie(test.getAliceIP());
                                test.setAlicePortFromCharlie(test.getAlicePort());
                            }
                            testComplete();
                            return;
                        }
                    }
                }
                test.setCharlieIntroKey(charlieIntroKey);
                if (test.getReceiveCharlieTime() > 0) {
                    // send msg 6
                    // logged in sendTestToCharlie()
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
                if (test.getSendCharlieTime() > 0) {
                    // After sending msg 6, we will ignore any msg 5 received
                    // we ignore completely, including any ip/port mismatch
                    // Do not call setCharlieReceiveTime()
                    if (_log.shouldDebug())
                        _log.debug("Ignoring msg 5 after sending msg 6, from Charlie " + from + " on " + test);
                    return;
                }
                long prev = test.getReceiveCharlieTime();
                test.setReceiveCharlieTime(now);
                if (prev > 0) {
                    // we ignore completely, including any ip/port mismatch
                    if (_log.shouldDebug())
                        _log.debug("Dup msg 5 from Charlie " + from + " on " + test);
                    return;
                }
                InetAddress charlieIP = test.getCharlieIP();
                if (charlieIP == null) {
                    // msg 5 before msg 4
                    try {
                        test.setCharlie(InetAddress.getByAddress(fromIP), fromPort, null);
                    } catch (UnknownHostException uhe) {}
                } else if (charlieIP == PENDING_IP) {
                    // msg 5 after msg 4, charlie is firewalled
                    // set charlie's real IP/port
                    try {
                        test.setCharlie(InetAddress.getByAddress(fromIP), fromPort, test.getCharlieHash());
                    } catch (UnknownHostException uhe) {}
                    // TODO, if charlie is symmetric natted, we won't know it when handling msg 7
                } else {
                    // msg 5 after msg 4, charlie is not firewalled
                    byte[] oldIP = charlieIP.getAddress();
                    int oldPort = test.getCharliePort();
                    if (!DataHelper.eq(fromIP, oldIP)) {
                        if (_log.shouldWarn())
                            _log.warn("Charlie IP mismatch, msg 4: " + Addresses.toString(oldIP, oldPort) +
                                      ", msg 5: " + Addresses.toString(fromIP, fromPort) + " on " + test);
                        // stop here, assume good unless symmetric natted,
                        // and note that charlie is probably not reachable
                        if (!_transport.isSymNatted()) {
                            test.setAliceIPFromCharlie(test.getAliceIP());
                            test.setAlicePortFromCharlie(test.getAlicePort());
                            _transport.markUnreachable(test.getCharlieHash());
                        }
                        testComplete();
                        return;
                    } else if (fromPort != oldPort) {
                        if (_log.shouldWarn())
                            _log.warn("Charlie port mismatch, msg 4: " + Addresses.toString(oldIP, oldPort) +
                                      ", msg 5: " + Addresses.toString(fromIP, fromPort) + " on " + test);
                        // Charlie is snymmetric natted or confused about his port, update port and keep going
                        // TransportUtil.isValidPort(fromPort) already checked at the top
                        test.setCharlie(charlieIP, fromPort, h);
                    }
                }
                // Do NOT set this here, only for msg 7, this is how testComplete() knows we got msg 7
                //test.setAlicePortFromCharlie(testPort);
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
                    // logged in sendTestToCharlie()
                    synchronized(this) {
                        sendTestToCharlie();
                    }
                } else {
                    // we haven't gotten message 4 yet
                    // We don't know Charlie's hash or intro key, we can't send msg 6 until we do
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
                long sendId = (nonce << 32) | nonce;
                long rcvId = ~sendId;
                InetAddress addr = state.getAliceIP();
                int alicePort = state.getAlicePort();
                byte[] aliceIP = addr.getAddress();
                iplen = aliceIP.length;
                data = new byte[12 + iplen];
                data[0] = 2;  // version
                DataHelper.toLong(data, 1, 4, nonce);
                DataHelper.toLong(data, 5, 4, now / 1000);
                data[9] = (byte) (iplen + 2);
                DataHelper.toLong(data, 10, 2, alicePort);
                System.arraycopy(aliceIP, 0, data, 12, iplen);
                // We send this to the source of msg 6, which may be different than aliceIP/alicePort
                if (!DataHelper.eq(aliceIP, fromIP)) {
                    try {
                        addr = InetAddress.getByAddress(fromIP);
                    } catch (UnknownHostException uhe) {
                        return;
                    }
                }
                if (_log.shouldDebug())
                    _log.debug("Send msg 7 to alice at " + Addresses.toString(fromIP, fromPort) + " on " + state);
                UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(addr, fromPort,
                                                                        state.getAliceIntroKey(), false,
                                                                        sendId, rcvId, data);
                _transport.send(packet);
                state.incrementPacketsRelayed();
                // we should be done, but stick around in case we get a retransmitted msg 6
                //_activeTests.remove(lNonce);
                if (addrBlockIP != null) {
                    if (_transport.isValid(addrBlockIP) &&
                        TransportUtil.isValidPort(addrBlockPort)) {
                        RouterAddress ra = _transport.getCurrentExternalAddress(isIPv6);
                        if (ra != null) {
                            if (addrBlockPort != ra.getPort() || !DataHelper.eq(addrBlockIP, ra.getIP())) {
                                if (_log.shouldWarn())
                                    _log.warn("Alice said we had a different IP/port: " +
                                              Addresses.toString(addrBlockIP, addrBlockPort) + " on " + state);
                            }
                        }
                        // We already call externalAddressReceived() for every outbound connection from EstablishmentManager
                        // and we don't do SNAT detection there
                        // _transport.externalAddressReceived(state.getAliceHash(), addrBlockIP, addrBlockPort)
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Alice said we had an invalid IP/port: " +
                                      Addresses.toString(addrBlockIP, addrBlockPort) + " on " + state);
                        // TODO ban alice or put on a list?
                    }
                }
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
                if (test.getReceiveBobTime() <= 0) {
                    // can't happen, we can't send msg 6 w/o msg 4
                    if (_log.shouldWarn())
                        _log.warn("Got msg 7 w/o msg 4??? on " + test);
                    testComplete();
                    return;
                }
                if (test.getReceiveCharlieTime() <= 0) {
                    // ENABLE_SSU2_SYMNAT_TEST only, msg 5 not received
                    if (_log.shouldWarn())
                        _log.warn("Got msg 7 w/o msg 5 from Charlie " + from + " on " + test);
                    // Do additional mismatch checks here, since we don't have msg 5.
                    InetAddress charlieIP = test.getCharlieIP();
                    // should always be non-null
                    if (charlieIP != null) {
                        // compare msg 4 IP/port to msg 7 IP/port
                        byte[] oldIP = charlieIP.getAddress();
                        int oldPort = test.getCharliePort();
                        if (fromPort != oldPort || !DataHelper.eq(fromIP, oldIP)) {
                            // If Charlie is confused or symmetric natted, we don't want to become symmetric natted ourselves.
                            if (_log.shouldWarn())
                                _log.warn("Charlie IP/port mismatch, msg 4: " + Addresses.toString(oldIP, oldPort) +
                                          ", msg 7: " + Addresses.toString(fromIP, fromPort) + " on " + test);
                            fail();
                            return;
                        }
                    }
                    // OK, here we go with the snat test
                }
                // this is our second charlie, yay!
                // Do NOT set this here, this is only for msg 5
                //test.setReceiveCharlieTime(now);
                // i2pd did not send address block in msg 7 until 0.9.57
                // Do basic validation of address block IP/port.
                boolean bad = false;
                if (addrBlockIP != null) {
                    if (_transport.isValid(addrBlockIP)) {
                        try {
                            InetAddress addr = InetAddress.getByAddress(addrBlockIP);
                            test.setAliceIPFromCharlie(addr);
                        } catch (UnknownHostException uhe) {}
                    } else {
                        bad = true;
                    }
                } else {
                    // assume good
                    test.setAliceIPFromCharlie(test.getAliceIP());
                }
                if (!bad && addrBlockPort != 0) {
                    if (addrBlockPort >= 1024) {
                        // use the IP/port from the address block
                        test.setAlicePortFromCharlie(addrBlockPort);
                    } else {
                        bad = true;
                    }
                } else if (!_transport.isSymNatted()) {
                    // assume good if we aren't symmetric natted
                    test.setAlicePortFromCharlie(test.getAlicePort());
                }
                if (bad) {
                    if (_log.shouldWarn())
                        _log.warn("Charlie said we had an invalid IP/port: " +
                                  Addresses.toString(addrBlockIP, addrBlockPort) + " on " + test);
                    _context.statManager().addRateData("udp.testBadIP", 1);
                    // TODO ban charlie or put on a list?
                    // complete test without setting AliceIPFromCharlie or AlicePortFromCHarlie,
                    // the result will be OK
                } else {
                    // More sanity checks here.
                    // we compare to the test address,
                    // however our address may have changed during the test
                    Hash charlieHash = test.getCharlieHash();
                    boolean portok = addrBlockPort == test.getAlicePort();
                    boolean IPok = DataHelper.eq(addrBlockIP, test.getAliceIP().getAddress());
                    if (!portok || !IPok) {
                        if (_log.shouldWarn())
                            _log.warn("Charlie said we had a different IP/port: " +
                                      Addresses.toString(addrBlockIP, addrBlockPort) + " on " + test);
                        if (test.getReceiveCharlieTime() > 0) {
                            // if we did get msg 5, it's almost impossible for us to be symmetric natted.
                            // It's much more likely that Charlie is symmetric natted.
                            // However, our temporary IPv6 IP could have changed.
                            // testComplete() will deal with it
                            if (IPok) {
                                // Port different. Charlie probably symmetric natted.
                                // The result will be OK
                                // Note that charlie is probably not reachable
                                if (charlieHash != null)
                                    _transport.markUnreachable(charlieHash);
                                // Reset port so testComplete() will return success.
                                test.setAlicePortFromCharlie(test.getAlicePort());
                                // set bad so we don't call externalAddressReceived()
                                bad = true;
                            } else if (portok) {
                                // Our IP changed?
                                // The result will be SNAT
                                // we will call externalAddressReceived()
                            } else {
                                // Both IP and port changed, don't trust it
                                // The result will be OK
                                // Note that charlie is probably not reachable
                                if (charlieHash != null)
                                    _transport.markUnreachable(charlieHash);
                                // Reset IP and port so testComplete() will return success.
                                test.setAliceIPFromCharlie(test.getAliceIP());
                                test.setAlicePortFromCharlie(test.getAlicePort());
                                // set bad so we don't call externalAddressReceived()
                                bad = true;
                            }
                        } else {
                            // ENABLE_SSU2_SYMNAT_TEST only, msg 5 not received
                            // If we did not get msg 5, hard to say which of us is to blame.
                            // the result will be SNAT
                            // we will call externalAddressReceived()
                            // Let UDPTransport figure it out.
                        }
                    }
                    // We already call externalAddressReceived() for every outbound connection from EstablishmentManager
                    // but we can use this also to update our address faster
                    if (!bad && charlieHash != null)
                        _transport.externalAddressReceived(charlieHash, addrBlockIP, addrBlockPort);
                }
                testComplete();
                break;
            }

            default:
                return;
        }
    }

    /**
     *  Send reject to Alice. We are Bob. SSU2 only.
     *
     *  @since 0.9.57
     */
    private void sendRejectToAlice(int reason, byte[] data, PeerState2 alice) {
        try {
            UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(reason, Hash.FAKE_HASH, data, alice);
            _transport.send(packet);
        } catch (IOException ioe) {}
    }

    /**
     *  Get an address out of a RI. SSU2 only.
     *
     *  @return address or null
     *  @since 0.9.54
     */
    private RouterAddress getAddress(RouterInfo ri, boolean isIPv6) {
        List<RouterAddress> addrs = _transport.getTargetAddresses(ri);
        return getAddress(addrs, isIPv6);
    }

    /**
     *  Get an address out of a list of addresses. SSU2 only.
     *
     *  @return address or null
     *  @since 0.9.55
     */
    static RouterAddress getAddress(List<RouterAddress> addrs, boolean isIPv6) {
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
            // skip bogus addresses
            byte[] ip = addr.getIP();
            if (ip != null && !TransportUtil.isPubliclyRoutable(ip, true))
                continue;
            ra = addr;
            break;
        }
        return ra;
    }

    /**
     *  Get an intro key out of an address. SSU2 only.
     *
     *  @since 0.9.54, pkg private since 0.9.55 for IntroManager
     */
    static SessionKey getIntroKey(RouterAddress ra) {
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
     * SSU 1 Bob/Charlie and SSU 2 Bob
     * forget about charlie's nonce after a short while.
     */
    private class RemoveTest extends SimpleTimer2.TimedEvent {
        private final Long _nonce;

        /** schedules itself */
        public RemoveTest(Long nonce, long delay) {
            super(_context.simpleTimer2());
            _nonce = nonce;
            schedule(delay);
        }

        public void timeReached() {
            _activeTests.remove(_nonce);
            // TODO send code as bob if no response from charlie
        }
    }

    /** 
     * SSU 2 Charlie only.
     * Retransmit msg 5 if necessary, and then
     * forget about charlie's nonce after a short while.
     *
     * @since 0.9.57
     */
    private class CharlieTimer extends SimpleTimer2.TimedEvent {
        private final Long _nonce;

        /** schedules itself */
        public CharlieTimer(Long nonce) {
            super(_context.simpleTimer2());
            _nonce = nonce;
            schedule(RESEND_TIMEOUT);
        }

        public void timeReached() {
            PeerTestState state = _activeTests.get(_nonce);
            if (state == null)
                return;
            long now = _context.clock().now();
            long remaining = state.getBeginTime() + MAX_CHARLIE_LIFETIME - now;
            if (remaining <= 0) {
                if (_log.shouldDebug())
                    _log.debug("Expired as charlie on " + state);
                _activeTests.remove(_nonce);
                return;
            }
            if (state.getReceiveAliceTime() > 0) {
                // got msg 6, no more need to retx msg 5
                reschedule(remaining);
                return;
            }

            // retransmit at 4/8/12 sec, no backoff
            if (_log.shouldDebug())
                _log.debug("Retx msg 5 to alice on " + state);
            long nonce = _nonce.longValue();
            long sendId = (nonce << 32) | nonce;
            long rcvId = ~sendId;
            // send the same data we sent to Bob
            UDPPacket packet = _packetBuilder2.buildPeerTestToAlice(state.getAliceIP(), state.getAlicePort(),
                                                                    state.getAliceIntroKey(), true,
                                                                    sendId, rcvId, state.getTestData());
            _transport.send(packet);
            state.incrementPacketsRelayed();
            state.setLastSendTime(now);
            reschedule(Math.min(RESEND_TIMEOUT, remaining));
        }
    }

    /** 
     * Send RI and Peer Test. SSU2 only. We are Bob.
     *
     * Msg 2 Bob to Charlie with Alice's RI
     * Msg 4 Bob to Alice with Charlie's RI
     *
     * @param ri may be null, but hopefully not
     * @param status -1 for msg 2, nonnegative for msg 4
     * @param hash alice for msg 2, charlie for msg 4
     * @param data signed peer test data
     * @param to charlie for msg 2, alice for msg 4
     * @since 0.9.57
     * @throws IOException if to peer is dead
     */
    private void sendRIandPT(RouterInfo ri, int status, Hash hash, byte[] data, PeerState2 to, long now) throws IOException {
        boolean delay = false;
        SSU2Payload.RIBlock riblock = null;
        if (ri != null) {
            // See if the RI will compress enough to fit in the peer test packet,
            // as this makes everything go smoother and faster.
            // Overhead total is 183 via IPv4, 203 via IPv6 (w/ IPv4 addr in data)
            // Overhead total is 195 via IPv4, 215 via IPv6 (w/ IPv6 addr in data)
            int avail = to.getMTU() -
                        ((to.isIPv6() ? PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_DATA_PACKET_OVERHEAD) +
                         SSU2Payload.BLOCK_HEADER_SIZE +     // peer test block header
                         3 +                                 // peer test block msgnum/code/flag
                         Hash.HASH_LENGTH +                  // peer test block hash
                         data.length +                       // peer test block signed data
                         SSU2Payload.BLOCK_HEADER_SIZE +     // RI block header
                         2                                   // RI block flag/frag bytes
                        );
            byte[] info = ri.toByteArray();
            byte[] gzipped = DataHelper.compress(info, 0, info.length, DataHelper.MAX_COMPRESSION);
            if (_log.shouldDebug())
                _log.debug("RI: " + info.length + " bytes uncompressed, " + gzipped.length +
                           " compressed, MTU " + to.getMTU() + ", available " + avail);
            boolean gzip = gzipped.length < info.length;
            if (gzip)
                info = gzipped;
            if (info.length <= avail) {
                riblock = new SSU2Payload.RIBlock(info,  0, info.length, false, gzip, 0, 1);
            } else {
                DatabaseStoreMessage dbsm = new DatabaseStoreMessage(_context);
                dbsm.setEntry(ri);
                dbsm.setMessageExpiration(now + 10*1000);
                _transport.send(dbsm, to);
                delay = true;
            }
        }
        UDPPacket packet;
        if (status < 0)
            packet = _packetBuilder2.buildPeerTestToCharlie(hash, data, riblock, to);
        else
            packet = _packetBuilder2.buildPeerTestToAlice(status, hash, data, riblock, to);
        // delay because dbsm is queued, we want it to get there first
        if (delay)
            new DelaySend(packet, 100);
        else
           _transport.send(packet);
        to.setLastSendTime(now);
    }

    /** 
     * Simple fix for RI getting there before PeerTest.
     * SSU2 only. We are Bob, for delaying msg sent after RI to Alice or Charlie.
     * @since 0.9.57
     */
    private class DelaySend extends SimpleTimer2.TimedEvent {
        private final UDPPacket pkt;

        public DelaySend(UDPPacket packet, long delay) {
            super(_context.simpleTimer2());
            pkt = packet;
            schedule(delay);
        }

        public void timeReached() {
           _transport.send(pkt);
        }
    }

    /**
     *  This is only for out-of-session messages 5-7,
     *  where most blocks are not allowed.
     *
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
            try {
                Hash h = ri.getHash();
                if (h.equals(_context.routerHash()))
                    return;
                _context.netDb().store(h, ri);
                // ignore flood request
            } catch (IllegalArgumentException iae) {
                if (_log.shouldWarn())
                    _log.warn("RI store fail: " + ri, iae);
            }
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
            receiveTest(_from, null, msg, status, h, data, _aliceIP, _alicePort);
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

        public void gotPathChallenge(RemoteHostId from, byte[] data) {
            throw new IllegalStateException("Bad block in PT");
        }

        public void gotPathResponse(RemoteHostId from, byte[] data) {
            throw new IllegalStateException("Bad block in PT");
        }
    }
}
