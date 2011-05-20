package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 *
 */
class IntroductionManager {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private PacketBuilder _builder;
    /** map of relay tag to PeerState that should receive the introduction */
    private final Map<Long, PeerState> _outbound;
    /** list of peers (PeerState) who have given us introduction tags */
    private final Set<PeerState> _inbound;

    public IntroductionManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(IntroductionManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx, transport);
        _outbound = new ConcurrentHashMap(128);
        _inbound = new ConcurrentHashSet(128);
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
        if (peer.getTheyRelayToUsAs() > 0) {
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
    
    public PeerState get(long id) {
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
            if (_context.profileOrganizer().isFailing(cur.getRemotePeer()) ||
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
            if (_log.shouldLog(Log.INFO))
                _log.info("Picking introducer: " + cur);
            cur.setIntroducerTime();
            UDPAddress ura = new UDPAddress(ra);
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_HOST_PREFIX + found, cur.getRemoteHostId().toHostString());
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_PORT_PREFIX + found, String.valueOf(cur.getRemotePort()));
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_KEY_PREFIX + found, Base64.encode(ura.getIntroKey()));
            ssuOptions.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + found, String.valueOf(cur.getTheyRelayToUsAs()));
            found++;
        }

        // FIXME failsafe if found == 0, relax inactivityCutoff and try again?

        // Try to keep the connection up for two hours after we made anybody an introducer
        long pingCutoff = _context.clock().now() - (2 * 60 * 60 * 1000);
        inactivityCutoff = _context.clock().now() - (UDPTransport.EXPIRE_TIMEOUT / 4);
        for (int i = 0; i < sz; i++) {
            PeerState cur = peers.get(i);
            if (cur.getIntroducerTime() > pingCutoff &&
                cur.getLastSendTime() < inactivityCutoff) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Pinging introducer: " + cur);
                cur.setLastSendTime(_context.clock().now());
                _transport.send(_builder.buildPing(cur));
            }
        }

        return found;
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

    void receiveRelayIntro(RemoteHostId bob, UDPPacketReader reader) {
        if (_context.router().isHidden())
            return;
        if (_log.shouldLog(Log.INFO))
            _log.info("Receive relay intro from " + bob);
        _context.statManager().addRateData("udp.receiveRelayIntro", 1, 0);
        _transport.send(_builder.buildHolePunch(reader));
    }
    
    void receiveRelayRequest(RemoteHostId alice, UDPPacketReader reader) {
        if (_context.router().isHidden())
            return;
        long tag = reader.getRelayRequestReader().readTag();
        PeerState charlie = _transport.getPeerState(tag);
        if (_log.shouldLog(Log.INFO))
            _log.info("Receive relay request from " + alice 
                      + " for tag " + tag
                      + " and relaying with " + charlie);
        if (charlie == null) {
            _context.statManager().addRateData("udp.receiveRelayRequestBadTag", 1, 0);
            return;
        }
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
