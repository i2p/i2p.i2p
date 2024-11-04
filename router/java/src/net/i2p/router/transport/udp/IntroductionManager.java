package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Addresses;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.VersionComparator;

/**
 *  Keep track of inbound and outbound introductions.
 *
 *  IPv6 info: Alice-Bob communication may be via IPv4 or IPv6.
 *  Bob-Charlie communication must be via established IPv4 session as that's the only way
 *  that Bob knows Charlie's IPv4 address to give it to Alice.
 *  Alice-Charlie communication is via IPv4.
 *  If Alice-Bob is over IPv6, Alice must include her IPv4 address in
 *  the RelayRequest message.
 *
 *  From udp.html on the website:

<p>Indirect session establishment by means of a third party introduction
is necessary for efficient NAT traversal.  Charlie, a router behind a
NAT or firewall which does not allow unsolicited inbound UDP packets,
first contacts a few peers, choosing some to serve as introducers.  Each
of these peers (Bob, Bill, Betty, etc) provide Charlie with an introduction
tag - a 4 byte random number - which he then makes available to the public
as methods of contacting him.  Alice, a router who has Charlie's published
contact methods, first sends a RelayRequest packet to one or more of the 
introducers, asking each to introduce her to Charlie (offering the 
introduction tag to identify Charlie).  Bob then forwards a RelayIntro
packet to Charlie including Alice's public IP and port number, then sends
Alice back a RelayResponse packet containing Charlie's public IP and port
number.  When Charlie receives the RelayIntro packet, he sends off a small
random packet to Alice's IP and port (poking a hole in his NAT/firewall),
and when Alice receives Bob's RelayResponse packet, she begins a new 
full direction session establishment with the specified IP and port.</p>
<p>
Alice first connects to introducer Bob, who relays the request to Charlie.
</p>
<pre>
        Alice                         Bob                  Charlie
    RelayRequest ----------------------&gt;
         &lt;-------------- RelayResponse    RelayIntro -----------&gt;
         &lt;-------------------------------------------- HolePunch (data ignored)
    SessionRequest --------------------------------------------&gt;
         &lt;-------------------------------------------- SessionCreated
    SessionConfirmed ------------------------------------------&gt;
         &lt;-------------------------------------------- DeliveryStatusMessage
         &lt;-------------------------------------------- DatabaseStoreMessage
    DatabaseStoreMessage --------------------------------------&gt;
    Data &lt;--------------------------------------------------&gt; Data
</pre>

<p>
After the hole punch, the session is established between Alice and Charlie as in a direct establishment.
</p>
 */
class IntroductionManager {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder2 _builder2;
    /** map of relay tag to Charlie PeerState that should receive the introduction (we are Bob) */
    private final Map<Long, PeerState> _outbound;
    /** map of relay tag to Bob PeerState who have given us introduction tags (we are Charlie) */
    private final Map<Long, PeerState> _inbound;
    /** map of relay nonce to alice PeerState who requested it */
    private final ConcurrentHashMap<Long, PeerState2> _nonceToAlice;
    private final Map<Long, Object> _recentRelaysAsBob;
    private static final Object DUMMY = new Object();

    /**
     * Limit since we ping to keep the conn open
     * @since 0.8.11
     */
    private static final int MAX_INBOUND = 20;

    /**
     * This is enforced in EstablishmentManager
     * @since 0.8.11
     */
    public static final int MAX_OUTBOUND = 100;

    /** Max one per target in this time */
    private static final long PUNCH_CLEAN_TIME = 5*1000;
    /** Max for all targets per PUNCH_CLEAN_TIME */
    private static final int MAX_PUNCHES = 20;
    private static final long INTRODUCER_EXPIRATION = 80*60*1000L;
    private static final String MIN_IPV6_INTRODUCER_VERSION = "0.9.50";
    private static final long MAX_SKEW = 2*60*1000;

    public IntroductionManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(IntroductionManager.class);
        _transport = transport;
        _builder2 = transport.getBuilder2();
        _outbound = new ConcurrentHashMap<Long, PeerState>(MAX_OUTBOUND);
        _inbound = new ConcurrentHashMap<Long, PeerState>(MAX_INBOUND);
        _nonceToAlice = new ConcurrentHashMap<Long, PeerState2>(MAX_INBOUND);
        _recentRelaysAsBob = new LHMCache<Long, Object>(8);
        ctx.statManager().createRateStat("udp.receiveRelayIntro", "How often we get a relayed request for us to talk to someone?", "udp", UDPTransport.RATES);
        ctx.statManager().createRateStat("udp.receiveRelayRequest", "How often we receive a good request to relay to someone else?", "udp", UDPTransport.RATES);
        ctx.statManager().createRateStat("udp.receiveRelayRequestBadTag", "Received relay requests with bad/expired tag", "udp", UDPTransport.RATES);
        ctx.statManager().createRateStat("udp.relayBadIP", "Received IP or port was bad", "udp", UDPTransport.RATES);
    }
    
    public void reset() {
        _inbound.clear();
        _outbound.clear();
    }
    
    public void add(PeerState peer) {
        if (peer == null) return;
        // let's not use an introducer on a privileged port, sounds like trouble
        if (!TransportUtil.isValidPort(peer.getRemotePort()))
            return;
        long id = peer.getWeRelayToThemAs();
        boolean added = id > 0;
        if (added)
            _outbound.put(Long.valueOf(id), peer);
        long id2 = peer.getTheyRelayToUsAs();
        if (id2 > 0 && _inbound.size() < MAX_INBOUND) {
            added = true;
            _inbound.put(Long.valueOf(id2), peer);
        }
        //if (added &&_log.shouldLog(Log.DEBUG))
        //    _log.debug("adding peer " + peer);
    }
    
    public void remove(PeerState peer) {
        if (peer == null) return;
        long id = peer.getWeRelayToThemAs(); 
        if (id > 0) 
            _outbound.remove(Long.valueOf(id));
        long id2 = peer.getTheyRelayToUsAs();
        if (id2 > 0) {
            _inbound.remove(Long.valueOf(id2));
        }
        //if ((id > 0 || id2 > 0) &&_log.shouldLog(Log.DEBUG))
        //    _log.debug("removing peer " + peer);
    }
    
    /**
     *  Is this inbound tag currently valid,
     *  i.e. is the peer still connected?
     *
     *  @since 0.9.50
     */
    public boolean isInboundTagValid(long tag) {
        return _inbound.containsKey(Long.valueOf(tag));
    }

    private PeerState get(long id) {
        return _outbound.get(Long.valueOf(id));
    }
    
    /**
     * Grab a bunch of peers who are willing to be introducers for us that
     * are locally known (duh) and have published their own SSU address (duh^2).
     * The picked peers have their info tacked on to the ssuOptions parameter for
     * use in the SSU RouterAddress.
     *
     * Try to use "good" peers (i.e. reachable, active)
     *
     * Also, ping all idle peers that were introducers in the last 2 hours,
     * to keep the connection up, since the netDb can have quite stale information,
     * and we want to keep our introducers valid.
     *
     * @param current current router address, may be null
     * @param ipv6 what type is the current address we need introducers for?
     * @param ssuOptions out parameter, options are added
     * @return number of introducers added
     */
    public int pickInbound(RouterAddress current, boolean ipv6, Properties ssuOptions, int howMany) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Picking inbound out of " + _inbound.size());
        if (_inbound.isEmpty()) return 0;
        List<PeerState> peers = new ArrayList<PeerState>(_inbound.values());
        int sz = peers.size();
        Collections.sort(peers, new PeerStateComparator());
        int found = 0;
        long now = _context.clock().now();
        long inactivityCutoff = now - (UDPTransport.EXPIRE_TIMEOUT / 2);    // 15 min
        // if not too many to choose from, be less picky
        if (sz <= howMany + 2)
            inactivityCutoff -= UDPTransport.EXPIRE_TIMEOUT / 4;
        List<Introducer> introducers = new ArrayList<Introducer>(howMany);
        String exp = Long.toString((now + INTRODUCER_EXPIRATION) / 1000);

        int ssu2count = 0;
        // reuse old ones if ok
        if (current != null) {
            UDPAddress ua = new UDPAddress(current);
            for (int i = 0; i < ua.getIntroducerCount(); i++) {
                long lexp = ua.getIntroducerExpiration(i);
                if (lexp > 0 && lexp < now + UDPTransport.INTRODUCER_EXPIRATION_MARGIN)
                    continue;
                long tag = ua.getIntroducerTag(i);
                if (!isInboundTagValid(tag))
                    continue;
                String sexp = Long.toString(ua.getIntroducerExpiration(i) / 1000);
                Introducer intro = new Introducer(ua.getIntroducerHash(i), tag, sexp);
                ssu2count++;
                if (_log.shouldInfo())
                    _log.info("Reusing introducer: " + ua.getIntroducerHash(i));
                introducers.add(intro);
                found++;
            }
        }

        outerloop:
        for (int i = 0; i < sz && found < howMany; i++) {
            PeerState cur = peers.get(i);
            if (cur.isIPv6() != ipv6)
                continue;
            Hash hash = cur.getRemotePeer();
            // dup check of reused SSU2 introducers
            String b64 = hash.toBase64();
            for (Introducer intro : introducers) {
                if (b64.equals(intro.shash))
                    continue outerloop;
            }
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
            if (ri == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peer has no local routerInfo: " + cur);
                // ask him for it so we have it for next time
                DatabaseLookupMessage dlm = new DatabaseLookupMessage(_context);
                dlm.setSearchKey(hash);
                dlm.setSearchType(DatabaseLookupMessage.Type.RI);
                dlm.setMessageExpiration(now + 10*1000);
                dlm.setFrom(_context.routerHash());
                _transport.send(dlm, cur);
                cur.setLastSendTime(now);
                continue;
            }
            // FIXME we can include all his addresses including IPv6 even if we don't support IPv6 (isValid() is false)
            // but requires RelayRequest support, see below
            List<RouterAddress> ras = _transport.getTargetAddresses(ri);
            if (ras.isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peer has no SSU address: " + ri);
                continue;
            }
            if ( /* _context.profileOrganizer().isFailing(cur.getRemotePeer()) || */
                _context.banlist().isBanlisted(hash) ||
                _transport.wasUnreachable(hash)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer is failing, blocklisted or was unreachable: " + cur);
                continue;
            }
            // Try to pick active peers,
            // but give it min of 20 minutes
            if (cur.getLastReceiveTime() < inactivityCutoff &&
                cur.getLastSendTime() < inactivityCutoff &&
                cur.getIntroducerTime() + (INTRODUCER_EXPIRATION / 4) < now) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer is idle too long: " + cur);
                continue;
            }
            int oldFound = found;
            for (RouterAddress ra : ras) {
                byte[] ip = ra.getIP();
                if (ip == null)
                    continue;
                // we must canonicalize IPv6 addresses
                String host = ip.length == 4 ? ra.getHost() : Addresses.toString(ip);
                if (host == null)
                    continue;
                int port = ra.getPort();
                if (!isValid(ip, port, true))
                    continue;
                // IPv6/IPv4 and vice versa allowed as of 0.9.50
                if (((!ipv6 && ip.length == 16) || (ipv6 && ip.length == 4)) &&
                    VersionComparator.comp(ri.getVersion(), MIN_IPV6_INTRODUCER_VERSION) < 0) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("IPv6 intro. for IPv4 or IPv4 intro for IPv6 but he doesn't support it: " + cur);
                    continue;
                }
                cur.setIntroducerTime();
                Introducer intro = new Introducer(hash, cur.getTheyRelayToUsAs(), exp);
                ssu2count++;
                introducers.add(intro);
                found++;
                break;
            }
            if (oldFound != found && _log.shouldLog(Log.INFO))
                _log.info("Picking introducer: " + cur);
        }

        // we sort them so a change in order only won't happen, and won't cause a republish
        Collections.sort(introducers);
        for (int i = 0; i < found; i++) {
            Introducer in = introducers.get(i);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_HASH_PREFIX + i, in.shash);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + i, in.stag);
            String sexp = in.sexp;
            // look for existing expiration in current published
            // and reuse if still recent enough, so deepEquals() won't fail in UDPT.rEA
            if (current != null) {
                for (int j = 0; j < UDPTransport.PUBLIC_RELAY_COUNT; j++) {
                    String oexp = null;
                    if (in.shash.equals(current.getOption(UDPAddress.PROP_INTRO_HASH_PREFIX + j)) &&
                        in.stag.equals(current.getOption(UDPAddress.PROP_INTRO_TAG_PREFIX + j))) {
                        // found old one
                        oexp = current.getOption(UDPAddress.PROP_INTRO_EXP_PREFIX + j);
                    }
                    if (oexp != null) {
                        try {
                            long oex = Long.parseLong(oexp) * 1000;
                            if (oex > now + UDPTransport.INTRODUCER_EXPIRATION_MARGIN) {
                                // still good, use old expiration time
                                sexp = oexp;
                            }
                        } catch (NumberFormatException nfe) {}
                    }
                    break;
                }
            }
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_EXP_PREFIX + i, sexp);
        }

        // FIXME failsafe if found == 0, relax inactivityCutoff and try again?

        pingIntroducers();
        return found;
    }

    /**
     *  For picking introducers.
     *  Lowest uptime first, to reduce idle timeout and disconnect,
     *  and ensure variety.
     *
     *  @since 0.9.55
     */
    private static class PeerStateComparator implements Comparator<PeerState> {

        public int compare(PeerState l, PeerState r) {
            long d = r.getKeyEstablishedTime() - l.getKeyEstablishedTime();
            if (d < 0)
                return -1;
            if (d > 0)
                return 1;
            return 0;
        }
    }

    /**
     *  So we can sort them
     *  @since 0.9.18
     */
    private static class Introducer implements Comparable<Introducer> {
        public final String stag, sexp, shash;

        /**
         * SSU 2
         * @since 0.9.55
         */
        public Introducer(Hash h, long tag, String exp) {
            stag = String.valueOf(tag);
            sexp = exp;
            shash = h.toBase64();
        }

        @Override
        public int compareTo(Introducer i) {
            return stag.compareTo(i.stag);
        }
        
        @Override
        public boolean equals(Object o) {
        	if (o == null) {
        		return false;
        	}
        	if (!(o instanceof Introducer)) {
        		return false;
        	}
        	
        	Introducer i = (Introducer) o;
        	return this.compareTo(i) == 0;
        }
        
        @Override
        public int hashCode() {
            return stag.hashCode();
        }
    }

    /**
     *  Was part of pickInbound(), moved out so we can call it more often
     *  @since 0.8.11
     */
    public void pingIntroducers() {
        // Try to keep the connection up for two hours after we made anybody an introducer
        long now = _context.clock().now();
        long pingCutoff = now - (105 * 60 * 1000);
        long inactivityCutoff = now - (UDPTransport.MIN_EXPIRE_TIMEOUT / 2);
        for (Iterator<PeerState> iter = _inbound.values().iterator(); iter.hasNext(); ) {
            PeerState cur = iter.next();
            if (cur.getIntroducerTime() > pingCutoff &&
                cur.getLastSendOrPingTime() < inactivityCutoff) {
                if (_log.shouldDebug())
                    _log.debug("Pinging introducer: " + cur);
                cur.setLastPingTime(now);
                UDPPacket ping;
                try {
                    ping = _builder2.buildPing((PeerState2) cur);
                    _transport.send(ping);
                } catch (IOException ioe) {
                    iter.remove();
                }
            }
        }
    }
    
    /**
     * Not as elaborate as pickInbound() above.
     * Just a quick check to see how many volunteers we know,
     * which the Transport uses to see if we need more.
     *
     * @param ipv6 what type of address are they introducing us for
     * @return number of peers that have volunteered to introduce us
     */
    int introducerCount(boolean ipv6) {
        int rv = 0;
        for (PeerState ps : _inbound.values()) {
            if (ps.isIPv6() == ipv6)
                rv++;
        }
        return rv;
    }
    
    /**
     *  Combined IPv4 and IPv6
     *
     *  @return number of peers we have volunteered to introduce
     *  @since 0.9.3
     */
    int introducedCount() {
            return _outbound.size();
    }

    /**
     *  We are Bob and we got this from Alice.
     *  Send Alice's RI and a RelayIntro to Charlie, or reject with a RelayResponse to Alice.
     *  We should already have a session with Charlie and definitely with Alice.
     *
     *  SSU 2 only.
     *
     *  @since 0.9.55
     */
    void receiveRelayRequest(PeerState2 alice, byte[] data) {
        long time = DataHelper.fromLong(data, 8, 4) * 1000;
        long now = _context.clock().now();
        alice.setLastReceiveTime(now);
        long skew = time - now;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
            if (_log.shouldWarn())
                _log.warn("Too skewed for relay req from " + alice);
            return;
        }
        int ver = data[12] & 0xff;
        if (ver != 2) {
            if (_log.shouldWarn())
                _log.warn("Bad relay req version " + ver + " from " + alice);
            return;
        }
        long nonce = DataHelper.fromLong(data, 0, 4);
        long tag = DataHelper.fromLong(data, 4, 4);
        PeerState charlie = _outbound.get(Long.valueOf(tag));
        RouterInfo aliceRI = null;
        int rcode;
        if (charlie == null) {
            if (_log.shouldDebug())
                _log.debug("Relay tag not found " + tag + " from " + alice);
            rcode = SSU2Util.RELAY_REJECT_BOB_NO_TAG;
        } else if (charlie.getVersion() != 2) {
            if (_log.shouldWarn())
                _log.warn("Receive SSU2 relay request from " + alice  + " for SSU1 " + charlie);
            // add a code for this?
            rcode = SSU2Util.RELAY_REJECT_BOB_NO_TAG;
        } else {
            Long lnonce = Long.valueOf(nonce);
            boolean isDup;
            synchronized(_recentRelaysAsBob) {
                isDup = _recentRelaysAsBob.put(lnonce, DUMMY) != null;
            }
            if (isDup) {
                // fairly common from i2pd
                if (_log.shouldInfo())
                    _log.info("Dropping dup relay request from " + alice 
                          + " for tag " + tag
                          + " nonce " + nonce
                          + " time " + time
                          + " and relaying with " + charlie);
                return;
            }
            aliceRI = _context.netDb().lookupRouterInfoLocally(alice.getRemotePeer());
            if (aliceRI != null) {
                // validate signed data
                SigningPublicKey spk = aliceRI.getIdentity().getSigningPublicKey();
                if (SSU2Util.validateSig(_context, SSU2Util.RELAY_REQUEST_PROLOGUE,
                                         _context.routerHash(), charlie.getRemotePeer(), data, spk)) {
                    // save tag-to-alice mapping so we can forward the reply from charlie
                    PeerState2 old = _nonceToAlice.putIfAbsent(lnonce, alice);
                    if (old != null && !old.equals(alice)) {
                        // dup tag
                        rcode = SSU2Util.RELAY_REJECT_BOB_UNSPEC;
                    } else {
                        rcode = SSU2Util.RELAY_ACCEPT;
                    }
                    // _nonceToAlice entries will be expired by cleanup()
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Signature failed relay request\n" + aliceRI);
                    rcode = SSU2Util.RELAY_REJECT_BOB_SIGFAIL;
                }
            } else {
                // we do not set a timer to wait for alice's RI to come in.
                // we should have already had it.
                // Java I2P does not send her RI before the relay request.
                if (_log.shouldWarn())
                    _log.warn("Alice RI not found " + alice);
                rcode = SSU2Util.RELAY_REJECT_BOB_UNKNOWN_ALICE;
            }
        }
        if (rcode == SSU2Util.RELAY_ACCEPT) {
            // Send Alice RI and forward data in a Relay Intro to Charlie
            if (_log.shouldInfo())
                _log.info("Receive relay request from " + alice 
                      + " for tag " + tag
                      + " nonce " + nonce
                      + " time " + time
                      + " and relaying with " + charlie);

            // put alice hash in intro data
            byte[] idata = new byte[1 + Hash.HASH_LENGTH + data.length];
            //idata[0] = 0; // flag
            System.arraycopy(alice.getRemotePeer().getData(), 0, idata, 1, Hash.HASH_LENGTH);
            System.arraycopy(data, 0, idata, 1 + Hash.HASH_LENGTH, data.length);

            // See if our RI will compress enough to fit in the relay intro packet,
            // as this makes everything go smoother and faster.
            // Overhead total is 185 IPv4, 217 IPv6
            int avail = charlie.getMTU() -
                        ((charlie.isIPv6() ? PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_DATA_PACKET_OVERHEAD) +
                         SSU2Payload.BLOCK_HEADER_SIZE +     // relay intro block header
                         idata.length +                      // relay intro block payload
                         SSU2Payload.BLOCK_HEADER_SIZE +     // RI block header
                         2                                   // RI block flag/frag bytes
                        );
            byte[] info = aliceRI.toByteArray();
            byte[] gzipped = DataHelper.compress(info, 0, info.length, DataHelper.MAX_COMPRESSION);
            if (_log.shouldDebug())
                _log.debug("Alice RI: " + info.length + " bytes uncompressed, " + gzipped.length +
                           " compressed, charlie MTU " + charlie.getMTU() + ", available " + avail);
            boolean gzip = gzipped.length < info.length;
            if (gzip)
                info = gzipped;

            try {
                if (info.length <= avail) {
                    SSU2Payload.RIBlock riblock = new SSU2Payload.RIBlock(info,  0, info.length, false, gzip, 0, 1);
                    UDPPacket packet = _builder2.buildRelayIntro(idata, riblock, (PeerState2) charlie);
                    _transport.send(packet);
                } else {
                    DatabaseStoreMessage dbsm = new DatabaseStoreMessage(_context);
                    dbsm.setEntry(aliceRI);
                    dbsm.setMessageExpiration(now + 10*1000);
                    _transport.send(dbsm, charlie);
                    UDPPacket packet = _builder2.buildRelayIntro(idata, null, (PeerState2) charlie);
                    // delay because dbsm is queued, we want it to get there first
                    new DelaySend(packet, 40);
                }
                charlie.setLastSendTime(now);
                return;
            } catch (IOException ioe) {
                rcode = SSU2Util.RELAY_REJECT_BOB_UNSPEC;
                // fall thru to send reject
            }
        }

        try {
            // send rejection to Alice
            SigningPrivateKey spk = _context.keyManager().getSigningPrivateKey();
            data = SSU2Util.createRelayResponseData(_context, _context.routerHash(), rcode,
                                                    nonce, null, 0, spk, 0);
            if (data == null) {
                if (_log.shouldWarn())
                    _log.warn("sig fail");
                 return;
            }
            if (_log.shouldInfo())
                _log.info("Send relay response rejection as bob, reason: " + rcode + " to alice " + alice);
            UDPPacket packet = _builder2.buildRelayResponse(data, alice);
            alice.setLastSendTime(now);
            _transport.send(packet);
        } catch (IOException ioe) {}
    }

    /**
     *  We are Charlie and we got this from Bob.
     *  Send a HolePunch to Alice, who will soon be sending us a SessionRequest.
     *  And send a RelayResponse to bob.
     *
     *  SSU 2 only.
     *
     *  @since 0.9.55
     */
    void receiveRelayIntro(PeerState2 bob, Hash alice, byte[] data) {
        receiveRelayIntro(bob, alice, data, 0);
    }

    /**
     *  We are Charlie and we got this from Bob.
     *  Bob should have sent us the RI, but maybe it's in the block
     *  after this, or maybe it's in a different packet.
     *  Check for RI, if not found, return true to retry, unless retryCount is at the limit.
     *  Creates the timer if retryCount == 0.
     *
     *  SSU 2 only.
     *
     *  @return true if RI found, false to delay and retry.
     *  @since 0.9.55
     */
    private boolean receiveRelayIntro(PeerState2 bob, Hash alice, byte[] data, int retryCount) {
        RouterInfo aliceRI = null;
        if (retryCount >= 5) {
            // last chance
            aliceRI = _context.netDb().lookupRouterInfoLocally(alice);
        } else if (!_context.banlist().isBanlisted(alice)) {
            aliceRI = _context.netDb().lookupRouterInfoLocally(alice);
            if (aliceRI == null) {
                if (_log.shouldInfo())
                    _log.info("Delay after " + retryCount + " retries, no RI for " + alice.toBase64());
                if (retryCount == 0)
                    new DelayIntro(bob, alice, data);
                return false;
            }
        }
        receiveRelayIntro(bob, alice, data, aliceRI);
        return true;
    }

    /** 
     * Wait for RI.
     * @since 0.9.55
     */
    private class DelayIntro extends SimpleTimer2.TimedEvent {
        private final PeerState2 bob;
        private final Hash alice;
        private final byte[] data;
        private volatile int count;
        private static final long DELAY = 50;

        public DelayIntro(PeerState2 b, Hash a, byte[] d) {
            super(_context.simpleTimer2());
            bob = b;
            alice = a;
            data = d;
            schedule(DELAY);
        }

        public void timeReached() {
            boolean ok = receiveRelayIntro(bob, alice, data, ++count);
            if (!ok)
                reschedule(DELAY << count);
        }
    }

    /** 
     * Simple fix for RI DSM getting there before RelayIntro.
     * Most times not needed as the compressed RI will fit in the packet with the RelayIntro.
     * SSU2 only.
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
     *  We are Charlie and we got this from Bob.
     *  Send a HolePunch to Alice, who will soon be sending us a SessionRequest.
     *  And send a RelayResponse to bob.
     *
     *  SSU 2 only.
     *
     *  @since 0.9.55
     */
    private void receiveRelayIntro(PeerState2 bob, Hash alice, byte[] data, RouterInfo aliceRI) {
        long nonce = DataHelper.fromLong(data, 0, 4);
        long tag = DataHelper.fromLong(data, 4, 4);
        long time = DataHelper.fromLong(data, 8, 4) * 1000;
        long now = _context.clock().now();
        long skew = time - now;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
            if (_log.shouldWarn())
                _log.warn("Too skewed for relay intro from " + bob);
            return;
        }
        int ver = data[12] & 0xff;
        if (ver != 2) {
            if (_log.shouldWarn())
                _log.warn("Bad relay intro version " + ver + " from " + bob);
            return;
        }
        int iplen = data[13] & 0xff;
        if (iplen != 6 && iplen != 18) {
            if (_log.shouldWarn())
                _log.warn("Bad IP length " + iplen + " from " + bob);
            return;
        }
        boolean isIPv6 = iplen == 18;
        int testPort = (int) DataHelper.fromLong(data, 14, 2);
        byte[] testIP = new byte[iplen - 2];
        System.arraycopy(data, 16, testIP, 0, iplen - 2);
        InetAddress aliceIP;
        try {
            aliceIP = InetAddress.getByAddress(testIP);
        } catch (UnknownHostException uhe) {
            return;
        }

        SessionKey aliceIntroKey = null;
        int rcode;
        PeerState aps = _transport.getPeerState(alice);
        if (_transport.isSymNatted()) {
            rcode = SSU2Util.RELAY_REJECT_CHARLIE_ADDRESS;
        } else if (aps != null && aps.isIPv6() == isIPv6) {
            rcode = SSU2Util.RELAY_REJECT_CHARLIE_CONNECTED;
        } else if (_context.banlist().isBanlisted(alice) ||
                   _context.blocklist().isBlocklisted(testIP)) {
            rcode = SSU2Util.RELAY_REJECT_CHARLIE_BANNED;
        } else if (!TransportUtil.isValidPort(testPort) ||
                  !_transport.isValid(testIP) ||
                 _transport.isTooClose(testIP)) {
            rcode = SSU2Util.RELAY_REJECT_CHARLIE_ADDRESS;
        } else {
            // bob should have sent it to us. Don't bother to lookup
            // remotely if he didn't, or it was lost.
            if (aliceRI != null) {
                // validate signed data
                SigningPublicKey spk = aliceRI.getIdentity().getSigningPublicKey();
                if (SSU2Util.validateSig(_context, SSU2Util.RELAY_REQUEST_PROLOGUE,
                                         bob.getRemotePeer(), _context.routerHash(), data, spk)) {
                    aliceIntroKey = PeerTestManager.getIntroKey(getAddress(aliceRI, isIPv6));
                    if (aliceIntroKey != null)
                        rcode = SSU2Util.RELAY_ACCEPT;
                    else
                        rcode = SSU2Util.RELAY_REJECT_CHARLIE_ADDRESS;
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Signature failed relay intro\n" + aliceRI);
                    rcode = SSU2Util.RELAY_REJECT_CHARLIE_SIGFAIL;
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("Alice RI not found " + alice + " for relay intro from " + bob);
                rcode = SSU2Util.RELAY_REJECT_CHARLIE_UNKNOWN_ALICE;
            }
        }
        byte[] ourIP = null;
        RouterAddress ourra = _transport.getCurrentExternalAddress(isIPv6);
        if (ourra != null) {
            ourIP = ourra.getIP();
            if (ourIP == null) {
                if (_log.shouldWarn())
                    _log.warn("No IP to send in relay response");
                rcode = SSU2Util.RELAY_REJECT_CHARLIE_ADDRESS;
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("No address to send in relay response");
            rcode = SSU2Util.RELAY_REJECT_CHARLIE_ADDRESS;
        }
        int ourPort = _transport.getRequestedPort();

        // generate our signed data
        // we sign it even if rejecting, not required though
        long token;
        if (rcode == SSU2Util.RELAY_ACCEPT) {
            RemoteHostId aliceID = new RemoteHostId(testIP, testPort);
            EstablishmentManager.Token tok = _transport.getEstablisher().getInboundToken(aliceID, 60*1000);
            token = tok.getToken();
        } else {
            token = 0;
        }
        SigningPrivateKey spk = _context.keyManager().getSigningPrivateKey();
        data = SSU2Util.createRelayResponseData(_context, bob.getRemotePeer(), rcode,
                                                nonce, ourIP, ourPort, spk, token);
        if (data == null) {
            if (_log.shouldWarn())
                _log.warn("sig fail");
             return;
        }
        try {
            UDPPacket packet = _builder2.buildRelayResponse(data, bob);
            if (_log.shouldInfo())
                _log.info("Send relay response " + rcode + " as charlie " + " nonce " + nonce + " to bob " + bob +
                          " with token " + token +
                          " for alice " + Addresses.toString(testIP, testPort) + ' ' + aliceRI);
            _transport.send(packet);
            bob.setLastSendTime(now);
        } catch (IOException ioe) {
            return;
        }
        if (rcode == SSU2Util.RELAY_ACCEPT) {
            // send hole punch with the same data we sent to Bob
            if (_log.shouldDebug())
                _log.debug("Send hole punch to " + Addresses.toString(testIP, testPort));
            long sendId = (nonce << 32) | nonce;
            long rcvId = ~sendId;
            UDPPacket packet = _builder2.buildHolePunch(aliceIP, testPort, aliceIntroKey, sendId, rcvId, data);
            _transport.send(packet);
        }
    }

    /**
     *  We are Bob and we got this from Charlie, OR
     *  we are Alice and we got this from Bob.
     *
     *  If we are Bob, send to Alice.
     *  If we are Alice, send a SessionRequest to Charlie.
     *  We should already have a session with Charlie, but not necessarily with Alice.
     *
     *  SSU 2 only.
     *
     *  @since 0.9.55
     */
    void receiveRelayResponse(PeerState2 peer, int status, byte[] data) {
        long nonce = DataHelper.fromLong(data, 0, 4);
        long time = DataHelper.fromLong(data, 4, 4) * 1000;
        long now = _context.clock().now();
        peer.setLastReceiveTime(now);
        long skew = time - now;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
            if (_log.shouldWarn())
                _log.warn("Too skewed for relay resp from " + peer);
            return;
        }
        int ver = data[8] & 0xff;
        if (ver != 2) {
            if (_log.shouldWarn())
                _log.warn("Bad relay intro version " + ver + " from " + peer);
            return;
        }
        // Look up nonce to determine if we are Alice or Bob
        Long lnonce = Long.valueOf(nonce);
        PeerState2 alice = _nonceToAlice.remove(lnonce);
        if (alice != null) {
            // We are Bob, send to Alice
            // Debug, check the signature, but send it along even if failed
            if (true) {
                RouterInfo charlie = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
                if (charlie != null) {
                    byte[] signedData;
                    if (status == 0)
                        signedData = Arrays.copyOfRange(data, 0, data.length - 8);  // token
                    else
                        signedData = data;
                    SigningPublicKey spk = charlie.getIdentity().getSigningPublicKey();
                    if (SSU2Util.validateSig(_context, SSU2Util.RELAY_RESPONSE_PROLOGUE,
                                             _context.routerHash(), null, signedData, spk)) {
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Signature failed relay response as bob from charlie:\n" + charlie);
                    }
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Signer RI not found " + peer);
                }
            }
            byte[] idata = new byte[2 + data.length];
            //idata[0] = 0; // flag
            idata[1] = (byte) status;
            System.arraycopy(data, 0, idata, 2, data.length);
            try {
                UDPPacket packet = _builder2.buildRelayResponse(idata, alice);
                if (_log.shouldInfo())
                    _log.info("Got relay response " + status + " as bob, forward " + " nonce " + nonce + " to " + alice);
                _transport.send(packet);
                alice.setLastSendTime(now);
            } catch (IOException ioe) {}
        } else {
            boolean isDup;
            synchronized(_recentRelaysAsBob) {
                isDup = _recentRelaysAsBob.get(lnonce) != null;
            }
            if (isDup) {
                // very rare
                if (_log.shouldInfo())
                    _log.info("Dropping dup relay response as bob from charlie " + peer.getRemotePeer()
                          + " for nonce " + nonce);
                return;
            }
            // We are Alice, give to EstablishmentManager to check sig and process
            if (_log.shouldInfo())
                _log.info("Got relay response " + status + " as alice " + " nonce " + nonce + " from " + peer);
            _transport.getEstablisher().receiveRelayResponse(peer, nonce, status, data);
        }
    }

    /**
     *  We are Alice and we got this from Charlie.
     *  Send a SessionRequest to Charlie, whether or not we got the Relay Response already.
     *
     *  SSU 2 only, out-of-session.
     *
     *  @since 0.9.55
     */
    void receiveHolePunch(RemoteHostId charlie, byte[] data) {
    }

    /**
     *  Get an address out of a RI. SSU2 only.
     *
     *  @return address or null
     *  @since 0.9.55
     */
    private RouterAddress getAddress(RouterInfo ri, boolean isIPv6) {
        List<RouterAddress> addrs = _transport.getTargetAddresses(ri);
        return PeerTestManager.getAddress(addrs, isIPv6);
    }

    /**
     *  Are IP and port valid?
     *  Reject all IPv6, for now, even if we are configured for it.
     *  Refuse anybody in the same /16
     *  @since 0.9.3
     */
/*
    private boolean isValid(byte[] ip, int port) {
        return isValid(ip, port, false);
    }
*/

    /**
     *  Are IP and port valid?
     *  @since 0.9.50
     */
    private boolean isValid(byte[] ip, int port, boolean allowIPv6) {
        return TransportUtil.isValidPort(port) &&
               ip != null &&
               (ip.length == 4 || (allowIPv6 && ip.length == 16)) &&
               _transport.isValid(ip) &&
               (!_transport.isTooClose(ip)) &&
               (!_context.blocklist().isBlocklisted(ip));
    }

    /** 
     * Loop and cleanup _nonceToAlice
     * Called from EstablishmentManager doFailSafe() so we don't need a cleaner timer here.
     * @since 0.9.57
     */
    public void cleanup() {
        if (_nonceToAlice == null || _nonceToAlice.isEmpty())
            return;
        for (Iterator<PeerState2> iter = _nonceToAlice.values().iterator(); iter.hasNext(); ) {
            PeerState2 state = iter.next();
            if (state.isDead())
                iter.remove();
        }
    }

}
