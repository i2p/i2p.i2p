package net.i2p.router.transport.tcp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;

/**
 * Organize the tags used to connect with peers.
 *
 */
public class ConnectionTagManager {
    private RouterContext _context;
    /** H(routerIdentity) to ByteArray */
    private Map _tags;
    /** H(routerIdentity) to SessionKey */
    private Map _keys;
    
    /** synchronize against this when dealing with the data */
    private Object _lock;
    
    public ConnectionTagManager(RouterContext context) {
        _context = context;
        _tags = new HashMap(128);
        _keys = new HashMap(128);
        _lock = new Object();
    }
    
    /** Retrieve the associated tag (but do not consume it) */
    public ByteArray getTag(Hash peer) {
        synchronized (_lock) {
            return (ByteArray)_tags.get(peer);
        }
    }
    
    public SessionKey getKey(Hash peer) {
        synchronized (_lock) { // 
            return (SessionKey)_keys.get(peer);
        }
    }
    public SessionKey getKey(ByteArray tag) {
        synchronized (_lock) { // 
            for (Iterator iter = _tags.keySet().iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                ByteArray cur = (ByteArray)_tags.get(peer);
                if (cur.equals(tag))
                    return (SessionKey)_keys.get(peer);
            }
            return null;
        }
    }
    
    /** Update the tag associated with a peer, dropping the old one */
    public void replaceTag(Hash peer, ByteArray newTag, SessionKey key) {
        synchronized (_lock) {
            _tags.put(peer, newTag);
            _keys.put(peer, key);
        }
    }
}
