package net.i2p.router.transport.tcp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simple persistent impl writing the connection tags to connectionTag.keys
 * (or another file specified via "i2np.tcp.tagFile")
 *
 */
public class PersistentConnectionTagManager extends ConnectionTagManager {
    private Object _ioLock;
    
    public PersistentConnectionTagManager(RouterContext context) {
        super(context);
        _ioLock = new Object();
    }

    public static final String PROP_TAG_FILE = "i2np.tcp.tagFile";
    public static final String DEFAULT_TAG_FILE = "connectionTag.keys";

    protected void initialize() {
        loadTags();
    }
    
    /**
     * Save the tags/keys associated with the peer.
     *
     * @param keyByPeer H(routerIdentity) to SessionKey
     * @param tagByPeer H(routerIdentity) to ByteArray
     */
    protected void saveTags(Map keyByPeer, Map tagByPeer) {
        byte data[] = prepareData(keyByPeer, tagByPeer);
        if (data == null) return;
        
        synchronized (_ioLock) {
            File tagFile = getFile();
            if (tagFile == null) return;
            
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tagFile);
                fos.write(data);
                fos.flush();
                
                if (_log.shouldLog(Log.INFO))
                    _log.info("Wrote connection tags for " + keyByPeer.size() + " peers");
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error writing out the tags", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     * Get the raw data to be written to disk.
     *
     * @param keyByPeer H(routerIdentity) to SessionKey
     * @param tagByPeer H(routerIdentity) to ByteArray
     */
    private byte[] prepareData(Map keyByPeer, Map tagByPeer) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(keyByPeer.size() * 32 * 3 + 32);
        try {
            for (Iterator iter = keyByPeer.keySet().iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                SessionKey key = (SessionKey)keyByPeer.get(peer);
                ByteArray tag = (ByteArray)tagByPeer.get(peer);
                
                if ( (key == null) || (tag == null) ) continue;
                
                baos.write(peer.getData());
                baos.write(key.getData());
                baos.write(tag.getData());
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Wrote connection tag for " + peer.toBase64().substring(0,6));
            }
            byte pre[] = baos.toByteArray();
            Hash check = getContext().sha().calculateHash(pre);
            baos.write(check.getData());
            return baos.toByteArray();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error preparing the tags", ioe);
            return null;
        }
    }
    
    private void loadTags() {
        File tagFile = getFile();
        if ( (tagFile == null) || (tagFile.length() <= 31) ) {
            initializeData(new HashMap(), new HashMap(), new HashMap());
            return;
        }
        
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(tagFile);
            byte data[] = getData(tagFile, fin);
            if (data == null) {
                initializeData(new HashMap(), new HashMap(), new HashMap());
                return;
            }
        
            int entries = data.length / (32 * 3);
            Map keyByPeer = new HashMap(entries);
            Map tagByPeer = new HashMap(entries);
            Map peerByTag = new HashMap(entries);
        
            for (int i = 0; i < data.length; i += 32*3) {
                byte peer[] = new byte[32];
                byte key[] = new byte[32];
                byte tag[] = new byte[32];
                System.arraycopy(data, i, peer, 0, 32);
                System.arraycopy(data, i + 32, key, 0, 32);
                System.arraycopy(data, i + 64, tag, 0, 32);
                
                Hash peerData = new Hash(peer);
                SessionKey keyData = new SessionKey(key);
                ByteArray tagData = new ByteArray(tag);
                
                keyByPeer.put(peerData, keyData);
                tagByPeer.put(peerData, tagData);
                peerByTag.put(tagData, peerData);
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Loaded connection tag for " + peerData.toBase64().substring(0,6));
                
                if (keyByPeer.size() > ConnectionTagManager.MAX_CONNECTION_TAGS)
                    break;
            }
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Loaded connection tags for " + keyByPeer.size() + " peers");
            initializeData(keyByPeer, tagByPeer, peerByTag);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))        
                _log.error("Connection tag file is corrupt, removing it");
            try { fin.close(); } catch (IOException ioe2) {}
            tagFile.delete(); // ignore rv
            fin = null;
            initializeData(new HashMap(), new HashMap(), new HashMap());
            return;
        } finally {
            if (fin != null) try { fin.close(); } catch (IOException ioe) {}
        }
    }
    
    private byte[] getData(File tagFile, FileInputStream fin) throws IOException {
        byte data[] = new byte[(int)tagFile.length() - 32];
        int read = DataHelper.read(fin, data);
        if (read != data.length) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Connection tag file is corrupt (too short), removing it");
            try { fin.close(); } catch (IOException ioe) {}
            tagFile.delete(); // ignore rv
            fin = null;
            return null;
        }

        Hash readHash = new Hash();
        try {
            readHash.readBytes(fin);
        } catch (DataFormatException dfe) {
            readHash = null;
        }

        Hash calcHash = getContext().sha().calculateHash(data);
        if ( (readHash == null) || (!calcHash.equals(readHash)) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Connection tag file is corrupt, removing it");
            try { fin.close(); } catch (IOException ioe) {}
            tagFile.delete(); // ignore rv
            fin = null;
            return null;
        }
        
        return data;
    }
    
    private File getFile() {
        return new File(getContext().getProperty(PROP_TAG_FILE, DEFAULT_TAG_FILE));
    }
}
