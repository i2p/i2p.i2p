package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSettings;
import net.i2p.util.Log;

class TunnelBuilder {
    private Log _log;
    private RouterContext _context;
    
    private final static long DEFAULT_TUNNEL_DURATION = 10*60*1000; // 10 minutes
    /**
     * Chance that the tunnel build will be 0 hop, on a PROBABILITY_LOCAL_SCALE
     */
    private final static int PROBABILITY_LOCAL = -1;
    private final static int PROBABILITY_LOCAL_SCALE = 10;
    
    public TunnelBuilder(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(TunnelBuilder.class);
    }
    
    public TunnelInfo configureInboundTunnel(Destination dest, ClientTunnelSettings settings) {
        return configureInboundTunnel(dest, settings, false);
    }
    public TunnelInfo configureInboundTunnel(Destination dest, ClientTunnelSettings settings, boolean useFake) {
        boolean randFake = (_context.random().nextInt(PROBABILITY_LOCAL_SCALE) <= PROBABILITY_LOCAL);
        List peerLists = null;
        if (useFake || randFake) {
            peerLists = new ArrayList(0);
        } else {
            List peerHashes = selectInboundPeers(1, settings.getDepthInbound());
            peerLists = randomizeLists(peerHashes, 1, settings.getDepthInbound());
        }
        if (peerLists.size() <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Configuring local inbound tunnel");
            return configureInboundTunnel(dest, settings, new ArrayList());
        } else {
            List peerHashList = (List)peerLists.get(0);
            return configureInboundTunnel(dest, settings, peerHashList);
        }
    }
    
    public TunnelInfo configureOutboundTunnel(ClientTunnelSettings settings) {
        return configureOutboundTunnel(settings, false);
    }
    public TunnelInfo configureOutboundTunnel(ClientTunnelSettings settings, boolean useFake) {
        boolean randFake = (_context.random().nextInt(PROBABILITY_LOCAL_SCALE) <= PROBABILITY_LOCAL);
        List peerLists = null;
        if (useFake || randFake) {
            peerLists = new ArrayList(0);
        } else {
            List peerHashes = selectOutboundPeers(1, settings.getDepthOutbound());
            peerLists = randomizeLists(peerHashes, 1, settings.getDepthOutbound());
        }
        if (peerLists.size() <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Configuring local outbound tunnel");
            return configureOutboundTunnel(settings, new ArrayList());
        } else {
            List peerHashList = (List)peerLists.get(0);
            return configureOutboundTunnel(settings, peerHashList);
        }
    }
    
    /**
     * Select a series of participants for the inbound tunnel, define each of
     * their operating characteristics, and return them as a chain of TunnelInfo
     * structures.  The first TunnelInfo in each chain is the inbound gateway
     * to which the lease should be attached, and the last is the local router.
     *
     * @return set of TunnelInfo structures, where each value is the gateway of
     *         a different tunnel (and these TunnelInfo structures are chained
     *         via getNextHopInfo())
     */
    public Set configureInboundTunnels(Destination dest, ClientTunnelSettings settings) {
        return configureInboundTunnels(dest, settings, false);
    }
    /**
     * @param useFake if true, make this tunnel include no remote peers (so it'll always succeed)
     *
     */
    public Set configureInboundTunnels(Destination dest, ClientTunnelSettings settings, boolean useFake) {
        Set tunnels = new HashSet();
        int numIn = settings.getNumInboundTunnels();
        if (numIn <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("No inbound tunnels requested, but we're creating one anyway");
            numIn = 1;
        }
        List peerLists = null;
        if (!useFake) {
            List peerHashes = selectInboundPeers(numIn, settings.getDepthInbound());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peer hashes selected: " + peerHashes.size());
            peerLists = randomizeLists(peerHashes, settings.getNumInboundTunnels(), settings.getDepthInbound());
        } else {
            peerLists = new ArrayList(0);
        }
        if (peerLists.size() <= 0) {
            for (int i = 0; i < numIn; i++) {
                TunnelInfo tunnel = configureInboundTunnel(dest, settings, new ArrayList());
                tunnels.add(tunnel);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Dummy inbound tunnel " + tunnel.getTunnelId() + " configured (" + tunnel + ")");
            }
        } else {
            for (Iterator iter = peerLists.iterator(); iter.hasNext();) {
                List peerList = (List)iter.next();
                TunnelInfo tunnel = configureInboundTunnel(dest, settings, peerList);
                tunnels.add(tunnel);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Real inbound tunnel " + tunnel.getTunnelId() + " configured (" + tunnel + ")");
            }
        }
        
        return tunnels;
    }
    
    public Set configureOutboundTunnels(ClientTunnelSettings settings) {
        return configureOutboundTunnels(settings, false);
    }
    /**
     * @param useFake if true, make this tunnel include no remote peers (so it'll always succeed)
     *
     */
    public Set configureOutboundTunnels(ClientTunnelSettings settings, boolean useFake) {
        Set tunnels = new HashSet();
        
        List peerLists = null;
        if (!useFake) {
            List peerHashes = selectOutboundPeers(settings.getNumOutboundTunnels(), settings.getDepthOutbound());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peer hashes selected: " + peerHashes.size());
            peerLists = randomizeLists(peerHashes, settings.getNumOutboundTunnels(), settings.getDepthOutbound());
        } else {
            peerLists = new ArrayList(0);
        }
        if (peerLists.size() <= 0) {
            for (int i = 0; i < settings.getNumOutboundTunnels(); i++) {
                TunnelInfo tunnel = configureOutboundTunnel(settings, new ArrayList());
                tunnels.add(tunnel);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Dummy outbound tunnel " + tunnel.getTunnelId() + " configured (" + tunnel + ")");
            }
        } else {
            for (Iterator iter = peerLists.iterator(); iter.hasNext();) {
                List peerList = (List)iter.next();
                TunnelInfo tunnel = configureOutboundTunnel(settings, peerList);
                tunnels.add(tunnel);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Real outbound tunnel " + tunnel.getTunnelId() + " configured (" + tunnel + ")");
            }
        }
        return tunnels;
    }
    
    private List selectInboundPeers(int numTunnels, int numPerTunnel) {
        return selectPeers(numTunnels, numPerTunnel);
    }
    
    private List selectOutboundPeers(int numTunnels, int numPerTunnel) {
        return selectPeers(numTunnels, numPerTunnel);
    }
    
    /**
     * Retrieve a list of Hash structures (from RouterIdentity) for routers that
     * should be used for the tunnels.  A sufficient number should be retrieved so
     * that there are enough for the specified numTunnels where each tunnel has numPerTunnel
     * hops in it.
     *
     */
    private List selectPeers(int numTunnels, int numPerTunnel) {
        PeerSelectionCriteria criteria = new PeerSelectionCriteria();
        int maxNeeded = numTunnels * numPerTunnel;
        int minNeeded = numPerTunnel;
        criteria.setMaximumRequired(maxNeeded);
        criteria.setMinimumRequired(minNeeded);
        criteria.setPurpose(PeerSelectionCriteria.PURPOSE_TUNNEL);
        
        List peers = _context.peerManager().selectPeers(criteria);
        List rv = new ArrayList(peers.size());
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            if (null != _context.netDb().lookupRouterInfoLocally(peer))
                rv.add(peer);
            else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("peer manager selected a peer we don't know about - drop it");
            }
        }
        return rv;
    }
    
    /**
     * Take the router hashes and organize them into numTunnels lists where each
     * list contains numPerTunnel hashes.
     *
     * @return Set of List of Hash objects, where the each list contains an ordered
     *         group of routers to participate in the tunnel.  Note that these lists
     *         do NOT include the local router at the end, so numPerTunnel = 0 (and
     *         hence, an empty list) is a valid (albeit insecure) length
     */
    private List randomizeLists(List peerHashes, int numTunnels, int numPerTunnel) {
        List tunnels = new ArrayList(numTunnels);
        
        if (peerHashes.size() == 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("No peer hashes provided");
            return tunnels;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("# peers randomizing: " + peerHashes + " into " + numTunnels + " tunnels");
        }
        
        for (int i = 0; i < numTunnels; i++) {
            int startOn = _context.random().nextInt(peerHashes.size());
            List peers = new ArrayList();
            for (int j = 0; j < numPerTunnel; j++) {
                int k = (j + startOn) % peerHashes.size();
                Hash peer = (Hash)peerHashes.get(k);
                if (!peers.contains(peer))
                    peers.add(peer);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Tunnel " + i + " [" + numPerTunnel + "/(" + startOn+ ")]: " + peers);
            tunnels.add(peers);
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Tunnels: " + tunnels);
        
        return tunnels;
    }
    
    /**
     * Create a chain of TunnelInfo structures with the appropriate settings using
     * the supplied routers for each hop, as well as a final hop ending with the current
     * router
     */
    private TunnelInfo configureInboundTunnel(Destination dest, ClientTunnelSettings settings, List peerHashList) {
        SessionKey encryptionKey = _context.keyGenerator().generateSessionKey();
        Object kp[] = _context.keyGenerator().generateSigningKeypair();
        SigningPublicKey pubkey = (SigningPublicKey)kp[0];
        SigningPrivateKey privkey = (SigningPrivateKey)kp[1];
        
        long duration = settings.getInboundDuration();
        if (duration <= 0)
            duration = DEFAULT_TUNNEL_DURATION;
        long expiration = _context.clock().now() + duration;
        
        TunnelSettings tunnelSettings = new TunnelSettings(_context);
        tunnelSettings.setBytesPerMinuteAverage(settings.getBytesPerMinuteInboundAverage());
        tunnelSettings.setBytesPerMinutePeak(settings.getBytesPerMinuteInboundPeak());
        tunnelSettings.setDepth(peerHashList.size()+1);
        tunnelSettings.setExpiration(expiration);
        tunnelSettings.setIncludeDummy(settings.getIncludeDummyInbound());
        tunnelSettings.setMessagesPerMinuteAverage(settings.getMessagesPerMinuteInboundAverage());
        tunnelSettings.setMessagesPerMinutePeak(settings.getMessagesPerMinuteInboundPeak());
        tunnelSettings.setReorder(settings.getReorderInbound());
        
        TunnelId id = new TunnelId();
        id.setTunnelId(_context.random().nextLong(TunnelId.MAX_ID_VALUE));
        id.setType(TunnelId.TYPE_INBOUND);
        
        TunnelInfo first = null;
        TunnelInfo prev = null;
        for (int i = 0; i < peerHashList.size(); i++) {
            Hash peer = (Hash)peerHashList.get(i);
            TunnelInfo cur = new TunnelInfo(_context);
            cur.setThisHop(peer);
            cur.setConfigurationKey(_context.keyGenerator().generateSessionKey());
            cur.setDestination(null);
            if (i == 0) {
                // gateway
                cur.setEncryptionKey(encryptionKey);
                cur.setSigningKey(privkey);
            }
            cur.setSettings(tunnelSettings);
            cur.setTunnelId(id);
            cur.setVerificationKey(pubkey);
            
            if (prev != null) {
                prev.setNextHop(peer);
                prev.setNextHopInfo(cur);
            } else {
                first = cur;
            }
            prev = cur;
        }
        
        TunnelInfo last = new TunnelInfo(_context);
        last.setThisHop(_context.routerHash());
        last.setDestination(dest);
        last.setEncryptionKey(encryptionKey);
        last.setSettings(tunnelSettings);
        last.setTunnelId(id);
        last.setVerificationKey(pubkey);
        last.setSigningKey(privkey);
        last.setConfigurationKey(_context.keyGenerator().generateSessionKey());
        
        TunnelInfo cur = first;
        if (cur == null) {
            first = last;
        } else {
            while (cur.getNextHopInfo() != null)
                cur = cur.getNextHopInfo();
            cur.setNextHop(last.getThisHop());
            cur.setNextHopInfo(last);
        }
        
        return first;
    }
    
    
    /**
     * Create a chain of TunnelInfo structures with the appropriate settings using
     * the supplied routers for each hop, starting with the current router
     */
    private TunnelInfo configureOutboundTunnel(ClientTunnelSettings settings, List peerHashList) {
        SessionKey encryptionKey = _context.keyGenerator().generateSessionKey();
        Object kp[] = _context.keyGenerator().generateSigningKeypair();
        SigningPublicKey pubkey = (SigningPublicKey)kp[0];
        SigningPrivateKey privkey = (SigningPrivateKey)kp[1];
        
        long duration = settings.getInboundDuration(); // uses inbound duration for symmetry
        if (duration <= 0)
            duration = DEFAULT_TUNNEL_DURATION;
        long expiration = _context.clock().now() + duration;
        
        TunnelSettings tunnelSettings = new TunnelSettings(_context);
        tunnelSettings.setBytesPerMinuteAverage(settings.getBytesPerMinuteInboundAverage());
        tunnelSettings.setBytesPerMinutePeak(settings.getBytesPerMinuteInboundPeak());
        tunnelSettings.setDepth(peerHashList.size()+1);
        tunnelSettings.setExpiration(expiration);
        tunnelSettings.setIncludeDummy(settings.getIncludeDummyInbound());
        tunnelSettings.setMessagesPerMinuteAverage(settings.getMessagesPerMinuteInboundAverage());
        tunnelSettings.setMessagesPerMinutePeak(settings.getMessagesPerMinuteInboundPeak());
        tunnelSettings.setReorder(settings.getReorderInbound());
        
        TunnelId id = new TunnelId();
        id.setTunnelId(_context.random().nextLong(TunnelId.MAX_ID_VALUE));
        id.setType(TunnelId.TYPE_OUTBOUND);
        
        TunnelInfo first = new TunnelInfo(_context);
        first.setThisHop(_context.routerHash());
        first.setDestination(null);
        first.setEncryptionKey(encryptionKey);
        first.setSettings(tunnelSettings);
        first.setTunnelId(id);
        first.setVerificationKey(pubkey);
        first.setSigningKey(privkey);
        first.setConfigurationKey(_context.keyGenerator().generateSessionKey());
        
        TunnelInfo prev = first;
        for (int i = 0; i < peerHashList.size(); i++) {
            Hash peer = (Hash)peerHashList.get(i);
            TunnelInfo cur = new TunnelInfo(_context);
            cur.setThisHop(peer);
            cur.setConfigurationKey(_context.keyGenerator().generateSessionKey());
            cur.setDestination(null);
            if (i == peerHashList.size() -1) {
                // endpoint
                cur.setEncryptionKey(encryptionKey);
            }
            cur.setSettings(tunnelSettings);
            cur.setTunnelId(id);
            cur.setVerificationKey(pubkey);
            
            prev.setNextHop(peer);
            prev.setNextHopInfo(cur);
            prev = cur;
        }
        
        return first;
    }
}
