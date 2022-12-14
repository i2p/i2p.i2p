package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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
    private final PacketBuilder _builder;
    private final PacketBuilder2 _builder2;
    /** map of relay tag to Charlie PeerState that should receive the introduction (we are Bob) */
    private final Map<Long, PeerState> _outbound;
    /** map of relay tag to Bob PeerState who have given us introduction tags (we are Charlie) */
    private final Map<Long, PeerState> _inbound;
    /** map of relay nonce to alice PeerState who requested it */
    private final ConcurrentHashMap<Long, PeerState2> _nonceToAlice;
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
    private static final int MAX_PUNCHES = 20;
    private static final long INTRODUCER_EXPIRATION = 80*60*1000L;
    private static final String MIN_IPV6_INTRODUCER_VERSION = "0.9.50";
    private static final long MAX_SKEW = 2*60*1000;
    /** testing */
    private static final String PROP_PREFER_SSU2 = "i2np.ssu2.preferSSU2Introducers";
    private static final boolean DEFAULT_PREFER_SSU2 = true;

    public IntroductionManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(IntroductionManager.class);
        _transport = transport;
        _builder = transport.getBuilder();
        _builder2 = transport.getBuilder2();
        _outbound = new ConcurrentHashMap<Long, PeerState>(MAX_OUTBOUND);
        _inbound = new ConcurrentHashMap<Long, PeerState>(MAX_INBOUND);
        _nonceToAlice = (_builder2 != null) ? new ConcurrentHashMap<Long, PeerState2>(MAX_INBOUND) : null;
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
        // Skip SSU2 until we have support for relay
        if (peer.getVersion() != 1 && !SSU2Util.ENABLE_RELAY)
            return;
        // let's not use an introducer on a privileged port, sounds like trouble
        if (!TransportUtil.isValidPort(peer.getRemotePort()))
            return;
        long id = peer.getWeRelayToThemAs();
        boolean added = id > 0;
        if (added)
            _outbound.put(Long.valueOf(id), peer);
        long id2 = peer.getTheyRelayToUsAs();
        //if (id2 > 0 && _inbound.size() < MAX_INBOUND) {
        // test
        if (id2 > 0 && (_inbound.size() < MAX_INBOUND || peer.getVersion() == 2)) {
            added = true;
            _inbound.put(Long.valueOf(id2), peer);
        }
        if (added &&_log.shouldLog(Log.DEBUG))
            _log.debug("adding peer " + peer);
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
        if ((id > 0 || id2 > 0) &&_log.shouldLog(Log.DEBUG))
            _log.debug("removing peer " + peer);
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
        boolean preferV2 = _builder2 != null && _context.getProperty(PROP_PREFER_SSU2, DEFAULT_PREFER_SSU2);
        Collections.sort(peers, new PeerStateComparator(preferV2));
        int found = 0;
        long now = _context.clock().now();
        long inactivityCutoff = now - (UDPTransport.EXPIRE_TIMEOUT / 2);    // 15 min
        // if not too many to choose from, be less picky
        if (sz <= howMany + 2)
            inactivityCutoff -= UDPTransport.EXPIRE_TIMEOUT / 4;
        List<Introducer> introducers = new ArrayList<Introducer>(howMany);
        String exp = Long.toString((now + INTRODUCER_EXPIRATION) / 1000);

        // try to keep a mix of v1 and v2
        int ssu1count = 0;
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
                Introducer intro;
                byte[] key = ua.getIntroducerKey(i);
                if (key != null) {
                    // SSU 1
                    //// Replace SSU 1 with SSU 2 if available for slot 2
                    //// leave slots 0 and 1 for SSU 1
                    //// this will churn the SSU 1 introducers, oh well
                    if (preferV2 && ssu1count >= 2)
                        continue;
                    intro = new Introducer(ua.getIntroducerHost(i).getAddress(),
                                           ua.getIntroducerPort(i), key, tag, sexp);
                    ssu1count++;
                    if (_log.shouldInfo())
                        _log.info("Reusing introducer: " + ua.getIntroducerHost(i));
                } else {
                    // SSU 2
                    if (_builder != null && ssu2count >= 2)
                        continue;
                    intro = new Introducer(ua.getIntroducerHash(i), tag, sexp);
                    ssu2count++;
                    if (_log.shouldInfo())
                        _log.info("Reusing introducer: " + ua.getIntroducerHash(i));
                }
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
            if (SSU2Util.ENABLE_RELAY && cur.getVersion() > 1) {
                String b64 = hash.toBase64();
                for (Introducer intro : introducers) {
                    if (b64.equals(intro.shash))
                        continue outerloop;
                }
                if (_builder != null && ssu2count >= 2)
                    continue;
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
            loop:
            for (RouterAddress ra : ras) {
                byte[] ip = ra.getIP();
                if (ip == null)
                    continue;
                // we must canonicalize IPv6 addresses
                String host = ip.length == 4 ? ra.getHost() : Addresses.toString(ip);
                if (host == null)
                    continue;
                // dup check of reused introducers
                for (Introducer intro : introducers) {
                    if (host.equals(intro.sip))
                        continue loop;
                }
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
                Introducer intro;
                if (cur.getVersion() == 1) {
                    UDPAddress ura = new UDPAddress(ra);
                    byte[] ikey = ura.getIntroKey();
                    if (ikey == null)
                        continue;
                    intro = new Introducer(ip, port, ikey, cur.getTheyRelayToUsAs(), exp);
                    ssu1count++;
                } else {
                    intro = new Introducer(hash, cur.getTheyRelayToUsAs(), exp);
                    ssu2count++;
                }
                introducers.add(intro);
                found++;
                // two per router max, one for SSU 2
                if (found - oldFound >= 2 || cur.getVersion() > 1)
                    break;
            }
            if (oldFound != found && _log.shouldLog(Log.INFO))
                _log.info("Picking introducer: " + cur);
        }

        // we sort them so a change in order only won't happen, and won't cause a republish
        Collections.sort(introducers);
        for (int i = 0; i < found; i++) {
            Introducer in = introducers.get(i);
            if (in.version == 1) {
                ssuOptions.setProperty(UDPAddress.PROP_INTRO_HOST_PREFIX + i, in.sip);
                ssuOptions.setProperty(UDPAddress.PROP_INTRO_PORT_PREFIX + i, in.sport);
                ssuOptions.setProperty(UDPAddress.PROP_INTRO_KEY_PREFIX + i, in.skey);
            } else {
                ssuOptions.setProperty(UDPAddress.PROP_INTRO_HASH_PREFIX + i, in.shash);
            }
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + i, in.stag);
            String sexp = in.sexp;
            // look for existing expiration in current published
            // and reuse if still recent enough, so deepEquals() won't fail in UDPT.rEA
            if (current != null) {
                for (int j = 0; j < UDPTransport.PUBLIC_RELAY_COUNT; j++) {
                    String oexp = null;
                    if (in.version == 1) {
                        if (in.sip.equals(current.getOption(UDPAddress.PROP_INTRO_HOST_PREFIX + j)) &&
                            in.sport.equals(current.getOption(UDPAddress.PROP_INTRO_PORT_PREFIX + j)) &&
                            in.skey.equals(current.getOption(UDPAddress.PROP_INTRO_KEY_PREFIX + j)) &&
                            in.stag.equals(current.getOption(UDPAddress.PROP_INTRO_TAG_PREFIX + j))) {
                            // found old one
                            oexp = current.getOption(UDPAddress.PROP_INTRO_EXP_PREFIX + j);
                        }
                    } else {
                        if (in.shash.equals(current.getOption(UDPAddress.PROP_INTRO_HASH_PREFIX + j)) &&
                            in.stag.equals(current.getOption(UDPAddress.PROP_INTRO_TAG_PREFIX + j))) {
                            // found old one
                            oexp = current.getOption(UDPAddress.PROP_INTRO_EXP_PREFIX + j);
                        }
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
     *  Reverse sort, version 2 first, for testing
     *  Then lowest uptime first, to reduce idle timeout and disconnect,
     *  and ensure variety.
     *
     *  @since 0.9.55
     */
    private static class PeerStateComparator implements Comparator<PeerState> {
        private final boolean _v2;

        public PeerStateComparator(boolean preferV2) {
            _v2 = preferV2;
        }

        public int compare(PeerState l, PeerState r) {
            if (_v2) {
                int rv = r.getVersion() - l.getVersion();
                if (rv != 0)
                    return rv;
            }
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
        public final String sip, sport, skey, stag, sexp, shash;
        public final int version;

        /**
         * SSU 1
         */
        public Introducer(byte[] ip, int port, byte[] key, long tag, String exp) {
            sip = Addresses.toString(ip);
            sport = String.valueOf(port);
            skey = Base64.encode(key);
            stag = String.valueOf(tag);
            sexp = exp;
            version = 1;
            shash = null;
        }

        /**
         * SSU 2
         * @since 0.9.55
         */
        public Introducer(Hash h, long tag, String exp) {
            stag = String.valueOf(tag);
            sexp = exp;
            shash = h.toBase64();
            version = 2;
            sip = null;
            sport = null;
            skey = null;
        }

        @Override
        public int compareTo(Introducer i) {
            // put SSU 2 at the end to not confuse SSU 1
            int diff = version - i.version;
            if (diff != 0)
                return diff;
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
        for (PeerState cur : _inbound.values()) {
            if (cur.getIntroducerTime() > pingCutoff &&
                cur.getLastSendOrPingTime() < inactivityCutoff) {
                if (_log.shouldDebug())
                    _log.debug("Pinging introducer: " + cur);
                cur.setLastPingTime(now);
                UDPPacket ping;
                if (cur.getVersion() == 2)
                    ping = _builder2.buildPing((PeerState2) cur);
                else
                    ping = _builder.buildPing(cur);
                _transport.send(ping);
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
     *  We are Charlie and we got this from Bob.
     *  Send a HolePunch to Alice, who will soon be sending us a SessionRequest.
     *  We should already have a session with Bob, but probably not with Alice.
     *
     *  If we don't have a session with Bob, we removed the relay tag from
     *  our _outbound table, so this won't work.
     *
     *  We do some throttling here.
     *
     *  SSU 1 only.
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

        // allow IPv6 as of 0.9.50
        // validate alice IP/port here. We don't need to validate Bob, we have a session with him.
        if (!isValid(ip, port, true)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid relay intro for alice " + Addresses.toString(ip, port) + " via bob " + bob);
            _context.statManager().addRateData("udp.relayBadIP", 1);
            return;
        }

        if (_log.shouldDebug())
            _log.debug("Receive relay intro from " + bob + " for " + Addresses.toString(ip, port));
        
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
     *
     *  SSU 1 only.
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
        // here we allow IPv6
        if (!isValid(aliceIP, alicePort, true)) {
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
        // allow IPv6 as of 0.9.50
        RemoteHostId aliceRelayID;
        if (ipIncluded) {
            if (!isValid(aliceIP, alicePort, true)) {
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
            if (_log.shouldDebug())
                _log.debug("Receive relay request from " + alice 
                      + " with unknown tag " + tag);
            _context.statManager().addRateData("udp.receiveRelayRequestBadTag", 1);
            return;
        }
        if (charlie.getVersion() != 1) {
            if (_log.shouldWarn())
                _log.warn("Receive SSU1 relay request from " + alice  + " for SSU2 " + charlie);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("Receive relay request from " + alice 
                      + " for tag " + tag
                      + " and relaying with " + charlie);

        // TODO throttle based on alice identity and/or intro tag?

        _context.statManager().addRateData("udp.receiveRelayRequest", 1);

        // send that peer an introduction for alice
        _transport.send(_builder.buildRelayIntro(aliceRelayID, charlie, rrReader));
        long now = _context.clock().now();
        charlie.setLastSendTime(now);

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
            if (_log.shouldDebug())
                _log.debug("Sending relay response (w/ intro key) to " + alice);
        } else {
            if (_log.shouldDebug())
                _log.debug("Sending relay response (in-session) to " + alice);
            aliceState.setLastSendTime(now);
        }
        _transport.send(_builder.buildRelayResponse(alice, charlie, rrReader.readNonce(),
                                                    cipherKey, macKey));
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
            if (_log.shouldInfo())
                _log.info("Relay tag not found " + tag + " from " + alice);
            rcode = SSU2Util.RELAY_REJECT_BOB_NO_TAG;
        } else if (charlie.getVersion() != 2) {
            if (_log.shouldWarn())
                _log.warn("Receive SSU2 relay request from " + alice  + " for SSU1 " + charlie);
            // add a code for this?
            rcode = SSU2Util.RELAY_REJECT_BOB_NO_TAG;
        } else {
            aliceRI = _context.netDb().lookupRouterInfoLocally(alice.getRemotePeer());
            if (aliceRI != null) {
                // validate signed data
                SigningPublicKey spk = aliceRI.getIdentity().getSigningPublicKey();
                if (SSU2Util.validateSig(_context, SSU2Util.RELAY_REQUEST_PROLOGUE,
                                         _context.routerHash(), charlie.getRemotePeer(), data, spk)) {
                    // save tag-to-alice mapping so we can forward the reply from charlie
                    PeerState2 old = _nonceToAlice.putIfAbsent(Long.valueOf(nonce), alice);
                    if (old != null && !old.equals(alice)) {
                        // dup tag
                        rcode = SSU2Util.RELAY_REJECT_BOB_UNSPEC;
                    } else {
                        rcode = SSU2Util.RELAY_ACCEPT;
                    }
                    // TODO add timer to remove from _nonceToAlice
                } else {
                    if (_log.shouldWarn())
                        _log.warn("Signature failed relay request\n" + aliceRI);
                    rcode = SSU2Util.RELAY_REJECT_BOB_SIGFAIL;
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("Alice RI not found " + alice);
                rcode = SSU2Util.RELAY_REJECT_BOB_UNKNOWN_ALICE;
            }
        }
        UDPPacket packet;
        if (rcode == SSU2Util.RELAY_ACCEPT) {
            // Send Alice RI and forward data in a Relay Intro to Charlie
            if (_log.shouldInfo())
                _log.info("Receive relay request from " + alice 
                      + " for tag " + tag
                      + " nonce " + nonce
                      + " and relaying with " + charlie);
            DatabaseStoreMessage dbsm = new DatabaseStoreMessage(_context);
            dbsm.setEntry(aliceRI);
            dbsm.setMessageExpiration(now + 10*1000);
            _transport.send(dbsm, charlie);
            // put alice hash in intro data
            byte[] idata = new byte[1 + Hash.HASH_LENGTH + data.length];
            //idata[0] = 0; // flag
            System.arraycopy(alice.getRemotePeer().getData(), 0, idata, 1, Hash.HASH_LENGTH);
            System.arraycopy(data, 0, idata, 1 + Hash.HASH_LENGTH, data.length);
            packet = _builder2.buildRelayIntro(idata, (PeerState2) charlie);
            charlie.setLastSendTime(now);
        } else {
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
                _log.info("Send relay response rejection as bob " + rcode + " to alice " + alice);
            packet = _builder2.buildRelayResponse(data, alice);
            alice.setLastSendTime(now);
        }
        _transport.send(packet);
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
        if (_transport.isSnatted()) {
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
        UDPPacket packet = _builder2.buildRelayResponse(data, bob);
        if (_log.shouldInfo())
            _log.info("Send relay response " + rcode + " as charlie " + " nonce " + nonce + " to bob " + bob +
                      " with token " + token +
                      " for alice " + Addresses.toString(testIP, testPort) + ' ' + aliceRI);
        _transport.send(packet);
        bob.setLastSendTime(now);
        if (rcode == SSU2Util.RELAY_ACCEPT) {
            // send hole punch with the same data we sent to Bob
            if (_log.shouldDebug())
                _log.debug("Send hole punch to " + Addresses.toString(testIP, testPort));
            long sendId = (nonce << 32) | nonce;
            long rcvId = ~sendId;
            packet = _builder2.buildHolePunch(aliceIP, testPort, aliceIntroKey, sendId, rcvId, data);
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
        PeerState2 alice = _nonceToAlice.remove(Long.valueOf(nonce));
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
            UDPPacket packet = _builder2.buildRelayResponse(idata, alice);
            if (_log.shouldDebug())
                _log.debug("Got relay response " + status + " as bob, forward " + " nonce " + nonce + " to " + alice);
            _transport.send(packet);
            alice.setLastSendTime(now);
        } else {
            // We are Alice, give to EstablishmentManager to check sig and process
            if (_log.shouldDebug())
                _log.debug("Got relay response " + status + " as alice " + " nonce " + nonce + " from " + peer);
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
}
