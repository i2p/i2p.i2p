package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
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
    private final PacketBuilder _builder;
    /** map of relay tag to PeerState that should receive the introduction */
    private final Map<Long, PeerState> _outbound;
    /** list of peers (PeerState) who have given us introduction tags */
    private final Set<PeerState> _inbound;
    private final Set<InetAddress> _recentHolePunches;
    private long _lastHolePunchClean;

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
    private static final int MAX_PUNCHES = 8;
    private static final long INTRODUCER_EXPIRATION = 80*60*1000L;
    private static final String MIN_IPV6_INTRODUCER_VERSION = "0.9.50";

    public IntroductionManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(IntroductionManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx, transport);
        _outbound = new ConcurrentHashMap<Long, PeerState>(MAX_OUTBOUND);
        _inbound = new ConcurrentHashSet<PeerState>(MAX_INBOUND);
        _recentHolePunches = new HashSet<InetAddress>(16);
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
            _inbound.add(peer);
        }
        if (added &&_log.shouldLog(Log.DEBUG))
            _log.debug("adding peer " + peer.getRemoteHostId() + ", weRelayToThemAs "
                       + id + ", theyRelayToUsAs " + id2);
    }
    
    public void remove(PeerState peer) {
        if (peer == null) return;
        long id = peer.getWeRelayToThemAs(); 
        if (id > 0) 
            _outbound.remove(Long.valueOf(id));
        long id2 = peer.getTheyRelayToUsAs();
        if (id2 > 0) {
            _inbound.remove(peer);
        }
        if ((id > 0 || id2 > 0) &&_log.shouldLog(Log.DEBUG))
            _log.debug("removing peer " + peer.getRemoteHostId() + ", weRelayToThemAs "
                       + id + ", theyRelayToUsAs " + id2);
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
     * @param ssuOptions out parameter, options are added
     * @return number of introducers added
     */
    public int pickInbound(RouterAddress current, Properties ssuOptions, int howMany) {
        int start = _context.random().nextInt();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Picking inbound out of " + _inbound.size());
        if (_inbound.isEmpty()) return 0;
        List<PeerState> peers = new ArrayList<PeerState>(_inbound);
        int sz = peers.size();
        start = start % sz;
        int found = 0;
        long now = _context.clock().now();
        long inactivityCutoff = now - (UDPTransport.EXPIRE_TIMEOUT / 2);    // 15 min
        // if not too many to choose from, be less picky
        if (sz <= howMany + 2)
            inactivityCutoff -= UDPTransport.EXPIRE_TIMEOUT / 4;
        List<Introducer> introducers = new ArrayList<Introducer>(howMany);
        for (int i = 0; i < sz && found < howMany; i++) {
            PeerState cur = peers.get((start + i) % sz);
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(cur.getRemotePeer());
            if (ri == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peer has no local routerInfo: " + cur);
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
                _context.banlist().isBanlisted(cur.getRemotePeer()) ||
                _transport.wasUnreachable(cur.getRemotePeer())) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer is failing, blocklisted or was unreachable: " + cur);
                continue;
            }
            // Try to pick active peers...
            // FIXME this is really strict and causes us to run out of introducers
            // We have much less introducers than we used to have because routers don't offer
            // if they are approaching max connections (see EstablishmentManager)
            // FIXED, was ||, is this OK now?
            if (cur.getLastReceiveTime() < inactivityCutoff && cur.getLastSendTime() < inactivityCutoff) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer is idle too long: " + cur);
                continue;
            }
            int oldFound = found;
            for (RouterAddress ra : ras) {
                // IPv6 allowed as of 0.9.50
                byte[] ip = ra.getIP();
                if (ip.length == 16 && VersionComparator.comp(ri.getVersion(), MIN_IPV6_INTRODUCER_VERSION) < 0) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Would have picked IPv6 introducer but he doesn't support it: " + cur);
                    continue;
                }
                int port = ra.getPort();
                if (!isValid(ip, port, true))
                    continue;
                cur.setIntroducerTime();
                UDPAddress ura = new UDPAddress(ra);
                byte[] ikey = ura.getIntroKey();
                if (ikey == null)
                    continue;
                introducers.add(new Introducer(ip, port, ikey, cur.getTheyRelayToUsAs()));
                found++;
                // two per router max
                if (found - oldFound >= 2)
                    break;
            }
            if (oldFound != found && _log.shouldLog(Log.INFO))
                _log.info("Picking introducer: " + cur);
        }

        // we sort them so a change in order only won't happen, and won't cause a republish
        Collections.sort(introducers);
        String exp = Long.toString((now + INTRODUCER_EXPIRATION) / 1000);
        for (int i = 0; i < found; i++) {
            Introducer in = introducers.get(i);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_HOST_PREFIX + i, in.sip);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_PORT_PREFIX + i, in.sport);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_KEY_PREFIX + i, in.skey);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + i, in.stag);
            String sexp = exp;
            // look for existing expiration in current published
            // and reuse if still recent enough, so deepEquals() won't fail in UDPT.rEA
            if (current != null) {
                for (int j = 0; j < UDPTransport.PUBLIC_RELAY_COUNT; j++) {
                    if (in.sip.equals(current.getOption(UDPAddress.PROP_INTRO_HOST_PREFIX + j)) &&
                        in.sport.equals(current.getOption(UDPAddress.PROP_INTRO_PORT_PREFIX + j)) &&
                        in.skey.equals(current.getOption(UDPAddress.PROP_INTRO_KEY_PREFIX + j)) &&
                        in.stag.equals(current.getOption(UDPAddress.PROP_INTRO_TAG_PREFIX + j))) {
                        // found old one
                        String oexp = current.getOption(UDPAddress.PROP_INTRO_EXP_PREFIX + j);
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
            }
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_EXP_PREFIX + i, sexp);
        }

        // FIXME failsafe if found == 0, relax inactivityCutoff and try again?

        pingIntroducers();
        return found;
    }

    /**
     *  So we can sort them
     *  @since 0.9.18
     */
    private static class Introducer implements Comparable<Introducer> {
        public final String sip, sport, skey, stag;

        public Introducer(byte[] ip, int port, byte[] key, long tag) {
            sip = Addresses.toString(ip);
            sport = String.valueOf(port);
            skey = Base64.encode(key);
            stag = String.valueOf(tag);
        }

        @Override
        public int compareTo(Introducer i) {
            return skey.compareTo(i.skey);
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
        	return skey.hashCode(); 
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
        long inactivityCutoff = now - UDPTransport.MIN_EXPIRE_TIMEOUT;
        for (PeerState cur : _inbound) {
            if (cur.getIntroducerTime() > pingCutoff &&
                cur.getLastSendTime() < inactivityCutoff) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Pinging introducer: " + cur);
                cur.setLastSendTime(now);
                _transport.send(_builder.buildPing(cur));
            }
        }
    }
    
    /**
     * Not as elaborate as pickInbound() above.
     * Just a quick check to see how many volunteers we know,
     * which the Transport uses to see if we need more.
     * @return number of peers that have volunteered to introduce us
     */
    int introducerCount() {
            return _inbound.size();
    }
    
    /**
     *  @return number of peers we have volunteered to introduce
     *  @since 0.9.3
     */
    int introducedCount() {
            return _outbound.size();
    }

    /**
     *  We are Charlie and we got this from Bob.
     *  Send a HolePunch to Alice, who will soon be sending us a RelayRequest.
     *  We should already have a session with Bob, but probably not with Alice.
     *
     *  If we don't have a session with Bob, we removed the relay tag from
     *  our _outbound table, so this won't work.
     *
     *  We do some throttling here.
     */
    void receiveRelayIntro(RemoteHostId bob, UDPPacketReader reader) {
        if (_context.router().isHidden())
            return;
        _context.statManager().addRateData("udp.receiveRelayIntro", 1);

        if (!_transport.allowConnection()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping RelayIntro, over conn limit");
            return;
        }
        
        int ipSize = reader.getRelayIntroReader().readIPSize();
        byte ip[] = new byte[ipSize];
        reader.getRelayIntroReader().readIP(ip, 0);
        int port = reader.getRelayIntroReader().readPort();

        if ((!isValid(ip, port)) || (!isValid(bob.getIP(), bob.getPort()))) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad relay intro from " + bob + " for " + Addresses.toString(ip, port));
            _context.statManager().addRateData("udp.relayBadIP", 1);
            return;
        }

        if (_log.shouldLog(Log.INFO))
            _log.info("Receive relay intro from " + bob + " for " + Addresses.toString(ip, port));
        
        InetAddress to = null;
        try {
            to = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            // banlist Bob?
            if (_log.shouldLog(Log.WARN))
                _log.warn("IP for alice to hole punch to is invalid", uhe);
            _context.statManager().addRateData("udp.relayBadIP", 1);
            return;
        }
        
        RemoteHostId alice = new RemoteHostId(ip, port);
        if (_transport.getPeerState(alice) != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Ignoring RelayIntro, already have a session to " + to);
            return;
        }
        EstablishmentManager establisher = _transport.getEstablisher();
        if (establisher != null) {
            if (establisher.getInboundState(alice) != null) {
                // This check may be common, as Alice sends RelayRequests to
                // several introducers at once.
                if (_log.shouldLog(Log.INFO))
                    _log.info("Ignoring RelayIntro, establishment in progress to " + to);
                return;
            }
            if (!establisher.shouldAllowInboundEstablishment()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping RelayIntro, too many establishments in progress - for " + to);
                return;
            }
        }

        // basic throttle, don't bother saving per-peer send times
        // we throttle on IP only, ignoring port
        boolean tooMany = false;
        boolean already = false;
        synchronized (_recentHolePunches) {
            long now = _context.clock().now();
            if (now > _lastHolePunchClean + PUNCH_CLEAN_TIME) {
                _recentHolePunches.clear();
                _lastHolePunchClean = now;
                _recentHolePunches.add(to);
            } else {
                tooMany = _recentHolePunches.size() >= MAX_PUNCHES;
                if (!tooMany)
                    already = !_recentHolePunches.add(to);
            }
        }
        if (tooMany) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping - too many - RelayIntro for " + to);
            return;
        }
        if (already) {
            // This check will trigger a lot, as Alice sends RelayRequests to
            // several introducers at once.
            if (_log.shouldLog(Log.INFO))
                _log.info("Ignoring dup RelayIntro for " + to);
            return;
        }

        _transport.send(_builder.buildHolePunch(to, port));
    }
    
    /**
     *  We are Bob and we got this from Alice.
     *  Send a RelayIntro to Charlie and a RelayResponse to Alice.
     *  We should already have a session with Charlie, but not necessarily with Alice.
     */
    void receiveRelayRequest(RemoteHostId alice, UDPPacketReader reader) {
        if (_context.router().isHidden())
            return;
        UDPPacketReader.RelayRequestReader rrReader = reader.getRelayRequestReader();
        long tag = rrReader.readTag();
        int ipSize = rrReader.readIPSize();
        int port = rrReader.readPort();

        byte[] aliceIP = alice.getIP();
        int alicePort = alice.getPort();
        boolean ipIncluded = ipSize != 0;
        // here we allow IPv6, but only if there's an IP included
        if (!isValid(aliceIP, alicePort, ipIncluded)) {
            // not necessarily invalid ip/port, could be blocklisted
            if (_log.shouldWarn())
                _log.warn("Rejecting relay req from " + alice + " for " + Addresses.toString(aliceIP, alicePort));
            _context.statManager().addRateData("udp.relayBadIP", 1);
            return;
        }
        // prior to 0.9.24 we rejected any non-zero-length ip
        // here we reject anything different if it's the same size
        // As of 0.9.50 we allow relay request over IPv6
        if (ipIncluded) {
            byte ip[] = new byte[ipSize];
            rrReader.readIP(ip, 0);
            if (ipSize == aliceIP.length && !Arrays.equals(aliceIP, ip)) {
                if (_log.shouldWarn())
                    _log.warn("Bad relay req from " + alice + " for " + Addresses.toString(ip, port));
                _context.statManager().addRateData("udp.relayBadIP", 1);
                return;
            }
            aliceIP = ip;
        }
        // prior to 0.9.24 we rejected any nonzero port
        // here we reject anything different
        // As of 0.9.50 we allow it if the IP was included
        if (port != 0) {
            if (ipIncluded) {
                alicePort = port;
            } else if (port != alicePort) {
                if (_log.shouldWarn())
                    _log.warn("Bad relay req from " + alice + " for " + Addresses.toString(aliceIP, port));
                _context.statManager().addRateData("udp.relayBadIP", 1);
            }
            return;
        }
        // check again if IP was provided
        // here we do not allow IPv6
        RemoteHostId aliceRelayID;
        if (ipIncluded) {
            if (!isValid(aliceIP, alicePort)) {
                if (_log.shouldWarn())
                    _log.warn("Bad relay req from " + alice + " for " + Addresses.toString(aliceIP, alicePort));
                _context.statManager().addRateData("udp.relayBadIP", 1);
                return;
            }
            aliceRelayID = new RemoteHostId(aliceIP, alicePort);
        } else {
            aliceRelayID = alice;
        }

        PeerState charlie = get(tag);
        if (charlie == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Receive relay request from " + alice 
                      + " with unknown tag");
            _context.statManager().addRateData("udp.receiveRelayRequestBadTag", 1);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Receive relay request from " + alice 
                      + " for tag " + tag
                      + " and relaying with " + charlie);

        // TODO throttle based on alice identity and/or intro tag?

        _context.statManager().addRateData("udp.receiveRelayRequest", 1);

        // send that peer an introduction for alice
        _transport.send(_builder.buildRelayIntro(aliceRelayID, charlie, rrReader));

        // send alice back charlie's info
        // lookup session so we can use session key if available
        SessionKey cipherKey = null;
        SessionKey macKey = null;
        PeerState aliceState = _transport.getPeerState(alice);
        if (aliceState != null) {
            // established session (since 0.9.12)
            cipherKey = aliceState.getCurrentCipherKey();
            macKey = aliceState.getCurrentMACKey();
        }
        if (cipherKey == null || macKey == null) {
            // no session, use intro key (was only way before 0.9.12)
            byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
            reader.getRelayRequestReader().readAliceIntroKey(key, 0);
            cipherKey = new SessionKey(key);
            macKey = cipherKey;
            if (_log.shouldLog(Log.INFO))
                _log.info("Sending relay response (w/ intro key) to " + alice);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Sending relay response (in-session) to " + alice);
        }
        _transport.send(_builder.buildRelayResponse(alice, charlie, rrReader.readNonce(),
                                                    cipherKey, macKey));
    }

    /**
     *  Are IP and port valid?
     *  Reject all IPv6, for now, even if we are configured for it.
     *  Refuse anybody in the same /16
     *  @since 0.9.3
     */
    private boolean isValid(byte[] ip, int port) {
        return isValid(ip, port, false);
    }

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
}
