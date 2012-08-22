package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 *  Keep track of inbound and outbound introductions.
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
     * TODO this should be enforced in EstablishmentManager, it isn't now.
     * @since 0.8.11
     */
    private static final int MAX_OUTBOUND = 100;

    /** Max one per target in this time */
    private static final long PUNCH_CLEAN_TIME = 5*1000;
    /** Max for all targets per PUNCH_CLEAN_TIME */
    private static final int MAX_PUNCHES = 8;

    public IntroductionManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(IntroductionManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx, transport);
        _outbound = new ConcurrentHashMap(MAX_OUTBOUND);
        _inbound = new ConcurrentHashSet(MAX_INBOUND);
        _recentHolePunches = new HashSet(16);
        ctx.statManager().createRateStat("udp.receiveRelayIntro", "How often we get a relayed request for us to talk to someone?", "udp", UDPTransport.RATES);
        ctx.statManager().createRateStat("udp.receiveRelayRequest", "How often we receive a good request to relay to someone else?", "udp", UDPTransport.RATES);
        ctx.statManager().createRateStat("udp.receiveRelayRequestBadTag", "Received relay requests with bad/expired tag", "udp", UDPTransport.RATES);
    }
    
    public void reset() {
        _inbound.clear();
        _outbound.clear();
    }
    
    public void add(PeerState peer) {
        if (peer == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Adding peer " + peer.getRemoteHostId() + ", weRelayToThemAs " 
                       + peer.getWeRelayToThemAs() + ", theyRelayToUsAs " + peer.getTheyRelayToUsAs());
        if (peer.getWeRelayToThemAs() > 0) 
            _outbound.put(Long.valueOf(peer.getWeRelayToThemAs()), peer);
        if (peer.getTheyRelayToUsAs() > 0 && _inbound.size() < MAX_INBOUND) {
            _inbound.add(peer);
        }
    }
    
    public void remove(PeerState peer) {
        if (peer == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("removing peer " + peer.getRemoteHostId() + ", weRelayToThemAs " 
                       + peer.getWeRelayToThemAs() + ", theyRelayToUsAs " + peer.getTheyRelayToUsAs());
        if (peer.getWeRelayToThemAs() > 0) 
            _outbound.remove(Long.valueOf(peer.getWeRelayToThemAs()));
        if (peer.getTheyRelayToUsAs() > 0) {
            _inbound.remove(peer);
        }
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
     */
    public int pickInbound(Properties ssuOptions, int howMany) {
        int start = _context.random().nextInt(Integer.MAX_VALUE);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Picking inbound out of " + _inbound.size());
        if (_inbound.isEmpty()) return 0;
        List<PeerState> peers = new ArrayList(_inbound);
        int sz = peers.size();
        start = start % sz;
        int found = 0;
        long inactivityCutoff = _context.clock().now() - (UDPTransport.EXPIRE_TIMEOUT / 2);    // 15 min
        // if not too many to choose from, be less picky
        if (sz <= howMany + 2)
            inactivityCutoff -= UDPTransport.EXPIRE_TIMEOUT / 4;
        for (int i = 0; i < sz && found < howMany; i++) {
            PeerState cur = peers.get((start + i) % sz);
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(cur.getRemotePeer());
            if (ri == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peer has no local routerInfo: " + cur);
                continue;
            }
            RouterAddress ra = ri.getTargetAddress(UDPTransport.STYLE);
            if (ra == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peer has no SSU address: " + ri);
                continue;
            }
            if ( /* _context.profileOrganizer().isFailing(cur.getRemotePeer()) || */
                _context.shitlist().isShitlisted(cur.getRemotePeer()) ||
                _transport.wasUnreachable(cur.getRemotePeer())) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer is failing, shistlisted or was unreachable: " + cur);
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
            byte[] ip = cur.getRemoteIP();
            int port = cur.getRemotePort();
            if (ip == null || !TransportImpl.isPubliclyRoutable(ip) || port <= 0 || port > 65535)
                continue;
            if (_log.shouldLog(Log.INFO))
                _log.info("Picking introducer: " + cur);
            cur.setIntroducerTime();
            UDPAddress ura = new UDPAddress(ra);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_HOST_PREFIX + found, Addresses.toString(ip));
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_PORT_PREFIX + found, String.valueOf(port));
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_KEY_PREFIX + found, Base64.encode(ura.getIntroKey()));
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + found, String.valueOf(cur.getTheyRelayToUsAs()));
            found++;
        }

        // FIXME failsafe if found == 0, relax inactivityCutoff and try again?

        pingIntroducers();
        return found;
    }

    /**
     *  Was part of pickInbound(), moved out so we can call it more often
     *  @since 0.8.11
     */
    public void pingIntroducers() {
        // Try to keep the connection up for two hours after we made anybody an introducer
        long pingCutoff = _context.clock().now() - (105 * 60 * 1000);
        long inactivityCutoff = _context.clock().now() - UDPTransport.MIN_EXPIRE_TIMEOUT;
        for (PeerState cur : _inbound) {
            if (cur.getIntroducerTime() > pingCutoff &&
                cur.getLastSendTime() < inactivityCutoff) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Pinging introducer: " + cur);
                cur.setLastSendTime(_context.clock().now());
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
     *  We are Charlie and we got this from Bob.
     *  Send a HolePunch to Alice, who will soon be sending us a RelayRequest.
     *  We should already have a session with Bob, but probably not with Alice.
     *
     *  We do some throttling here.
     */
    void receiveRelayIntro(RemoteHostId bob, UDPPacketReader reader) {
        if (_context.router().isHidden())
            return;
        _context.statManager().addRateData("udp.receiveRelayIntro", 1, 0);

        if (!_transport.allowConnection()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping RelayIntro, over conn limit");
            return;
        }
        
        int ipSize = reader.getRelayIntroReader().readIPSize();
        byte ip[] = new byte[ipSize];
        reader.getRelayIntroReader().readIP(ip, 0);
        int port = reader.getRelayIntroReader().readPort();
        if (_log.shouldLog(Log.INFO))
            _log.info("Receive relay intro from " + bob + " for " + Addresses.toString(ip, port));
        
        InetAddress to = null;
        try {
            if (!_transport.isValid(ip))
                throw new UnknownHostException("non-public IP");
            if (port <= 0 || port > 65535)
                throw new UnknownHostException("bad port " + port);
            to = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            // shitlist Bob?
            if (_log.shouldLog(Log.WARN))
                _log.warn("IP for alice to hole punch to is invalid", uhe);
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
        long tag = reader.getRelayRequestReader().readTag();
        PeerState charlie = get(tag);
        if (charlie == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Receive relay request from " + alice 
                      + " with unknown tag");
            _context.statManager().addRateData("udp.receiveRelayRequestBadTag", 1, 0);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Receive relay request from " + alice 
                      + " for tag " + tag
                      + " and relaying with " + charlie);

        // TODO throttle based on alice identity and/or intro tag?

        _context.statManager().addRateData("udp.receiveRelayRequest", 1, 0);
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        reader.getRelayRequestReader().readAliceIntroKey(key, 0);
        SessionKey aliceIntroKey = new SessionKey(key);
        // send that peer an introduction for alice
        _transport.send(_builder.buildRelayIntro(alice, charlie, reader.getRelayRequestReader()));
        // send alice back charlie's info
        _transport.send(_builder.buildRelayResponse(alice, charlie, reader.getRelayRequestReader().readNonce(), aliceIntroKey));
    }
}
