package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Maintain all of the key pairs for the router.
 *
 */
public class KeyManager {
    private Log _log;
    private RouterContext _context;
    private PrivateKey _privateKey;
    private PublicKey _publicKey;
    private SigningPrivateKey _signingPrivateKey;
    private SigningPublicKey _signingPublicKey;
    private Map _leaseSetKeys; // Destination --> LeaseSetKeys
    
    public final static String PROP_KEYDIR = "router.keyBackupDir";
    public final static String DEFAULT_KEYDIR = "keyBackup";
    private final static String KEYFILE_PRIVATE_ENC = "privateEncryption.key";
    private final static String KEYFILE_PUBLIC_ENC = "publicEncryption.key";
    private final static String KEYFILE_PRIVATE_SIGNING = "privateSigning.key";
    private final static String KEYFILE_PUBLIC_SIGNING = "publicSigning.key";
    private final static long DELAY = 30*1000;
    
    public KeyManager(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(KeyManager.class);	
        setPrivateKey(null);
        setPublicKey(null);
        setSigningPrivateKey(null);
        setSigningPublicKey(null);
        _leaseSetKeys = new HashMap();
        _context.jobQueue().addJob(new SynchronizeKeysJob());
    }
    
    /** Configure the router's private key */
    public void setPrivateKey(PrivateKey key) { _privateKey = key; }
    public PrivateKey getPrivateKey() { return _privateKey; }
    /** Configure the router's public key */
    public void setPublicKey(PublicKey key) { _publicKey = key; }
    public PublicKey getPublicKey() { return _publicKey; }
    /** Configure the router's signing private key */
    public void setSigningPrivateKey(SigningPrivateKey key) { _signingPrivateKey = key; }
    public SigningPrivateKey getSigningPrivateKey() { return _signingPrivateKey; }
    /** Configure the router's signing public key */
    public void setSigningPublicKey(SigningPublicKey key) { _signingPublicKey = key; }
    public SigningPublicKey getSigningPublicKey() { return _signingPublicKey; }
    
    public void registerKeys(Destination dest, SigningPrivateKey leaseRevocationPrivateKey, PrivateKey endpointDecryptionKey) {
        _log.info("Registering keys for destination " + dest.calculateHash().toBase64());
        LeaseSetKeys keys = new LeaseSetKeys(dest, leaseRevocationPrivateKey, endpointDecryptionKey);
        synchronized (_leaseSetKeys) {
            _leaseSetKeys.put(dest, keys);
        }
    }
    
    public LeaseSetKeys unregisterKeys(Destination dest) {
        _log.info("Unregistering keys for destination " + dest.calculateHash().toBase64());
        synchronized (_leaseSetKeys) {
            return (LeaseSetKeys)_leaseSetKeys.remove(dest);
        }
    }
    
    public LeaseSetKeys getKeys(Destination dest) {
        synchronized (_leaseSetKeys) {
            return (LeaseSetKeys)_leaseSetKeys.get(dest);
        }
    }
    
    public Set getAllKeys() {
        HashSet keys = new HashSet();
        synchronized (_leaseSetKeys) {
            keys.addAll(_leaseSetKeys.values());
        }
        return keys;
    }
    
    private class SynchronizeKeysJob extends JobImpl {
        public SynchronizeKeysJob() {
            super(KeyManager.this._context);
        }
        public void runJob() {
            String keyDir = KeyManager.this._context.router().getConfigSetting(PROP_KEYDIR);
            if (keyDir == null) 
                keyDir = DEFAULT_KEYDIR;
            File dir = new File(keyDir);
            if (!dir.exists())
                dir.mkdirs();
            if (dir.exists() && dir.isDirectory() && dir.canRead() && dir.canWrite())
                syncKeys(dir);

            getTiming().setStartAfter(KeyManager.this._context.clock().now()+DELAY);
            KeyManager.this._context.jobQueue().addJob(this);
        }

        private void syncKeys(File keyDir) {
            syncPrivateKey(keyDir);
            syncPublicKey(keyDir);
            syncSigningKey(keyDir);
            syncVerificationKey(keyDir);
        }

        private void syncPrivateKey(File keyDir) {
            File keyFile = new File(keyDir, KeyManager.KEYFILE_PRIVATE_ENC);
            boolean exists = (_privateKey != null);
            if (!exists)
                _privateKey = new PrivateKey();
            _privateKey = (PrivateKey)syncKey(keyFile, _privateKey, exists);
        }
        private void syncPublicKey(File keyDir) {
            File keyFile = new File(keyDir, KeyManager.KEYFILE_PUBLIC_ENC);
            boolean exists = (_publicKey != null);
            if (!exists)
                _publicKey = new PublicKey();
            _publicKey = (PublicKey)syncKey(keyFile, _publicKey, exists);
        }

        private void syncSigningKey(File keyDir) {
            File keyFile = new File(keyDir, KeyManager.KEYFILE_PRIVATE_SIGNING);
            boolean exists = (_signingPrivateKey != null);
            if (!exists)
                _signingPrivateKey = new SigningPrivateKey();
            _signingPrivateKey = (SigningPrivateKey)syncKey(keyFile, _signingPrivateKey, exists);
        }
        private void syncVerificationKey(File keyDir) {
            File keyFile = new File(keyDir, KeyManager.KEYFILE_PUBLIC_SIGNING);
            boolean exists = (_signingPublicKey != null);
            if (!exists)
                _signingPublicKey  = new SigningPublicKey();
            _signingPublicKey  = (SigningPublicKey)syncKey(keyFile, _signingPublicKey, exists);
        }

        private DataStructure syncKey(File keyFile, DataStructure structure, boolean exists) {
            FileOutputStream out = null;
            FileInputStream in = null;
            try {
                if (exists) {
                    out = new FileOutputStream(keyFile);
                    structure.writeBytes(out);
                    return structure;
                } else {
                    if (keyFile.exists()) {
                        in = new FileInputStream(keyFile);
                        structure.readBytes(in);
                        return structure;
                    } else {
                        // we don't have it, and its not on disk.  oh well.
                        return null;
                    }
                }
            } catch (IOException ioe) {
                _log.error("Error syncing the structure to " + keyFile.getAbsolutePath(), ioe);
            } catch (DataFormatException dfe) {
                _log.error("Error syncing the structure with " + keyFile.getAbsolutePath(), dfe);
            } finally {
                if (out != null) try { out.close(); } catch (IOException ioe) {}
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }

            if (exists)
                return structure;
            else
                return null;
        }

        public String getName() { return "Synchronize Keys to Disk"; }
    }
}
