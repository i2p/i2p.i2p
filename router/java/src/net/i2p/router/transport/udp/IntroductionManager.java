package net.i2p.router.transport.udp;

import java.util.*;

import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *
 */
public class IntroductionManager {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private PacketBuilder _builder;
    /** map of relay tag to PeerState that should receive the introduction */
    private Map _outbound;
    /** list of peers (PeerState) who have given us introduction tags */
    private List _inbound;

    public IntroductionManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(IntroductionManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx, transport);
        _outbound = Collections.synchronizedMap(new HashMap(128));
        _inbound = new ArrayList(128);
        ctx.statManager().createRateStat("udp.receiveRelayIntro", "How often we get a relayed request for us to talk to someone?", "udp", new long[] { 60*1000, 5*60*1000, 10*60*1000 });
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
            _outbound.put(new Long(peer.getWeRelayToThemAs()), peer);
        if (peer.getTheyRelayToUsAs() > 0) {
            synchronized (_inbound) {
                if (!_inbound.contains(peer))
                    _inbound.add(peer);
            }
        }
    }
    
    public void remove(PeerState peer) {
        if (peer == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("removing peer " + peer.getRemoteHostId() + ", weRelayToThemAs " 
                       + peer.getWeRelayToThemAs() + ", theyRelayToUsAs " + peer.getTheyRelayToUsAs());
        if (peer.getWeRelayToThemAs() > 0) 
            _outbound.remove(new Long(peer.getWeRelayToThemAs()));
        if (peer.getTheyRelayToUsAs() > 0) {
            synchronized (_inbound) {
                _inbound.remove(peer);
            }
        }
    }
    
    public PeerState get(long id) {
        return (PeerState)_outbound.get(new Long(id));
    }
    
    public void pickInbound(List rv, int howMany) {
        int start = _context.random().nextInt(Integer.MAX_VALUE);
        synchronized (_inbound) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Picking inbound out of " + _inbound);
            if (_inbound.size() <= 0) return;
            start = start % _inbound.size();
            for (int i = 0; i < _inbound.size() && rv.size() < howMany; i++) {
                PeerState cur = (PeerState)_inbound.get((start + i) % _inbound.size());
                rv.add(cur);
            }
        }
    }
    
    public void receiveRelayIntro(RemoteHostId bob, UDPPacketReader reader) {
        _context.statManager().addRateData("udp.receiveRelayIntro", 1, 0);
        _transport.send(_builder.buildHolePunch(reader));
    }
    
    public void receiveRelayRequest(RemoteHostId alice, UDPPacketReader reader) {
        long tag = reader.getRelayRequestReader().readTag();
        PeerState charlie = _transport.getPeerState(tag);
        if (charlie == null)
            return;
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        reader.getRelayRequestReader().readAliceIntroKey(key, 0);
        SessionKey aliceIntroKey = new SessionKey(key);
        // send that peer an introduction for alice
        _transport.send(_builder.buildRelayIntro(alice, charlie, reader.getRelayRequestReader()));
        // send alice back charlie's info
        _transport.send(_builder.buildRelayResponse(alice, charlie, reader.getRelayRequestReader().readNonce(), aliceIntroKey));
    }
}
