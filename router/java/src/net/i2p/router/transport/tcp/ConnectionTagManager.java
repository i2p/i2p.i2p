package net.i2p.router.transport.tcp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Organize the tags used to connect with peers.
 *
 */
public class ConnectionTagManager {
    protected Log _log;
    private RouterContext _context;
    /** H(routerIdentity) to ByteArray */
    private Map _tagByPeer;
    /** ByteArray to H(routerIdentity) */
    private Map _peerByTag;
    /** H(routerIdentity) to SessionKey */
    private Map _keyByPeer;
    
    /** synchronize against this when dealing with the data */
    private Object _lock;
    
    /** 
     * Only keep the keys and tags for up to *cough* 10,000 peers (everyone 
     * else will need to use a full DH rekey).  Ok, yeah, 10,000 is absurd for 
     * the TCP transport anyway, but we need a limit, and this eats up at most 
     * 1MB (96 bytes per peer).  Later we may add another mapping to drop the
     * oldest ones first, but who cares for now.
     * 
     */
    public static final int MAX_CONNECTION_TAGS = 10*1000;
    
    public ConnectionTagManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(getClass());
        initialize();
        _lock = new Object();
    }
    
    protected void initialize() {
        initializeData(new HashMap(128), new HashMap(128), new HashMap(128));
    }
    
    protected void initializeData(Map keyByPeer, Map tagByPeer, Map peerByTag) {
        _keyByPeer = keyByPeer;
        _tagByPeer = tagByPeer;
        _peerByTag = peerByTag;
    }
    
    /** Retrieve the associated tag (but do not consume it) */
    public ByteArray getTag(Hash peer) {
        synchronized (_lock) {
            return (ByteArray)_tagByPeer.get(peer);
        }
    }
    
    public SessionKey getKey(Hash peer) {
        synchronized (_lock) { // 
            return (SessionKey)_keyByPeer.get(peer);
        }
    }
    public SessionKey getKey(ByteArray tag) {
        synchronized (_lock) { // 
            Hash peer = (Hash)_peerByTag.get(tag);
            if (peer == null) return null;
            return (SessionKey)_keyByPeer.get(peer);
        }
    }
    
    /** Update the tag associated with a peer, dropping the old one */
    public void replaceTag(Hash peer, ByteArray newTag, SessionKey key) {
        synchronized (_lock) {
            while (_keyByPeer.size() > MAX_CONNECTION_TAGS) {
                Hash rmPeer = (Hash)_keyByPeer.keySet().iterator().next();
                ByteArray tag = (ByteArray)_tagByPeer.remove(peer);
                SessionKey oldKey = (SessionKey)_keyByPeer.remove(peer);
                rmPeer = (Hash)_peerByTag.remove(tag);
                
                if (_log.shouldLog(Log.INFO))
                    _log.info("Too many tags, dropping the one for " + rmPeer.toBase64().substring(0,6));
            }
            _keyByPeer.put(peer, key);
            _peerByTag.put(newTag, peer);
            _tagByPeer.put(peer, newTag);
            
            saveTags(_keyByPeer, _tagByPeer);
        }
    }
    
    /**
     * Save the tags/keys associated with the peer.
     *
     * @param keyByPeer H(routerIdentity) to SessionKey
     * @param tagByPeer H(routerIdentity) to ByteArray
     */
    protected void saveTags(Map keyByPeer, Map tagByPeer) {
        // noop, in memory only
    }
    
    protected RouterContext getContext() { return _context; }
}
