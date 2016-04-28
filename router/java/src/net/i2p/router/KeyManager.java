package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.startup.CreateRouterInfoJob;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

/**
 * Maintain all of the key pairs for the router.
 * Router keys are written to files in a backup directory.
 * LeaseSet keys are not written to files.
 *
 */
public class KeyManager {
    private final Log _log;
    private final RouterContext _context;
    private PrivateKey _privateKey;
    private PublicKey _publicKey;
    private SigningPrivateKey _signingPrivateKey;
    private SigningPublicKey _signingPublicKey;
    private final Map<Hash, LeaseSetKeys> _leaseSetKeys; // Destination --> LeaseSetKeys
    
    public final static String PROP_KEYDIR = "router.keyBackupDir";
    public final static String DEFAULT_KEYDIR = "keyBackup";
    public final static String KEYFILE_PRIVATE_ENC = "privateEncryption.key";
    public final static String KEYFILE_PUBLIC_ENC = "publicEncryption.key";
    public final static String KEYFILE_PRIVATE_SIGNING = "privateSigning.key";
    public final static String KEYFILE_PUBLIC_SIGNING = "publicSigning.key";
    
    public KeyManager(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(KeyManager.class);	
        _leaseSetKeys = new ConcurrentHashMap<Hash, LeaseSetKeys>();
    }
    
    /**
     *  Read keys in from disk, blocking
     *
     *  @deprecated we never read keys in anymore
     */
    @Deprecated
    public void startup() {
        // run inline so keys are loaded immediately
        (new SynchronizeKeysJob()).runJob();
    }
    
    /**
     *  Configure the router's keys.
     *  @since 0.9.4 replace individual setters
     */
    public void setKeys(PublicKey key1, PrivateKey key2, SigningPublicKey key3, SigningPrivateKey key4) { 
        synchronized(this) {
            _publicKey = key1; 
            _privateKey = key2; 
            _signingPublicKey = key3; 
            _signingPrivateKey = key4; 
        }
        queueWrite();
    }

    /**
     * Router key
     * @return will be null on error or before startup() or setKeys() is called
     */
    public synchronized PrivateKey getPrivateKey() { return _privateKey; }

    /**
     * Router key
     * @return will be null on error or before startup() or setKeys() is called
     */
    public synchronized PublicKey getPublicKey() { return _publicKey; }

    /**
     * Router key
     * @return will be null on error or before startup() or setKeys() is called
     */
    public synchronized SigningPrivateKey getSigningPrivateKey() { return _signingPrivateKey; }

    /**
     * Router key
     * @return will be null on error or before startup() or setKeys() is called
     */
    public synchronized SigningPublicKey getSigningPublicKey() { return _signingPublicKey; }
    
    /** client */
    public void registerKeys(Destination dest, SigningPrivateKey leaseRevocationPrivateKey, PrivateKey endpointDecryptionKey) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Registering keys for destination " + dest.calculateHash().toBase64());
        LeaseSetKeys keys = new LeaseSetKeys(dest, leaseRevocationPrivateKey, endpointDecryptionKey);
        _leaseSetKeys.put(dest.calculateHash(), keys);
    }
   
    /**
     *  Read/Write the router keys from/to disk
     */
    private void queueWrite() {
        _context.jobQueue().addJob(new SynchronizeKeysJob());
    }

    /** client */
    public LeaseSetKeys unregisterKeys(Destination dest) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Unregistering keys for destination " + dest.calculateHash().toBase64());
        return _leaseSetKeys.remove(dest.calculateHash());
    }
    
    /** client */
    public LeaseSetKeys getKeys(Destination dest) {
        return getKeys(dest.calculateHash());
    }

    /** client */
    public LeaseSetKeys getKeys(Hash dest) {
            return _leaseSetKeys.get(dest);
    }
    
    /**
     *  Read/Write the 4 files in keyBackup/
     *  As of 0.9.4 this is run on-demand only, there's no need to
     *  periodically sync.
     *  Actually, there's little need for this at all.
     *  If router.keys is corrupt, we should just make a new router identity,
     *  there's no real reason to try so hard to recover our old keys.
     */
    private class SynchronizeKeysJob extends JobImpl {
        public SynchronizeKeysJob() {
            super(KeyManager.this._context);
        }

        public void runJob() {
            String keyDir = getContext().getProperty(PROP_KEYDIR, DEFAULT_KEYDIR);
            File dir = new SecureDirectory(getContext().getRouterDir(), keyDir);
            if (!dir.exists())
                dir.mkdirs();
            if (dir.exists() && dir.isDirectory() && dir.canRead() && dir.canWrite()) {
                synchronized(KeyManager.this) {
                    syncKeys(dir);
                }
            } else {
                _log.log(Log.CRIT, "Unable to synchronize keys in " + keyDir + " - permissions problem?");
            }
        }
        
        private void syncKeys(File keyDir) {
            syncPrivateKey(keyDir);
            syncPublicKey(keyDir);
            SigType type = CreateRouterInfoJob.getSigTypeConfig(getContext());
            syncSigningKey(keyDir, type);
            syncVerificationKey(keyDir, type);
        }

        private void syncPrivateKey(File keyDir) {
            DataStructure ds;
            File keyFile = new File(keyDir, KEYFILE_PRIVATE_ENC);
            boolean exists = (_privateKey != null);
            if (exists)
                ds = _privateKey;
            else
                ds = new PrivateKey();
            DataStructure readin = syncKey(keyFile, ds, exists);
            if (readin != null && !exists)
                _privateKey = (PrivateKey) readin;
        }

        private void syncPublicKey(File keyDir) {
            DataStructure ds;
            File keyFile = new File(keyDir, KEYFILE_PUBLIC_ENC);
            boolean exists = (_publicKey != null);
            if (exists)
                ds = _publicKey;
            else
                ds = new PublicKey();
            DataStructure readin = syncKey(keyFile, ds, exists);
            if (readin != null && !exists)
                _publicKey = (PublicKey) readin;
        }

        /**
         *  @param type the SigType to expect on read-in, ignored on write
         */
        private void syncSigningKey(File keyDir, SigType type) {
            DataStructure ds;
            File keyFile = new File(keyDir, KEYFILE_PRIVATE_SIGNING);
            boolean exists = (_signingPrivateKey != null);
            if (exists)
                ds = _signingPrivateKey;
            else
                ds = new SigningPrivateKey(type);
            DataStructure readin = syncKey(keyFile, ds, exists);
            if (readin != null && !exists)
                _signingPrivateKey = (SigningPrivateKey) readin;
        }

        /**
         *  @param type the SigType to expect on read-in, ignored on write
         */
        private void syncVerificationKey(File keyDir, SigType type) {
            DataStructure ds;
            File keyFile = new File(keyDir, KEYFILE_PUBLIC_SIGNING);
            boolean exists = (_signingPublicKey != null);
            if (exists)
                ds = _signingPublicKey;
            else
                ds = new SigningPublicKey(type);
            DataStructure readin = syncKey(keyFile, ds, exists);
            if (readin != null && !exists)
                _signingPublicKey  = (SigningPublicKey) readin;
        }

        /**
         *  @param param non-null, filled-in if exists is true, or without data if exists is false
         *  @param exists write to file if true, read from file if false
         *  @return structure or null on read error
         */
        private DataStructure syncKey(File keyFile, DataStructure structure, boolean exists) {
            OutputStream out = null;
            InputStream in = null;
            try {
                if (exists) {
                    out = new BufferedOutputStream(new SecureFileOutputStream(keyFile));
                    structure.writeBytes(out);
                    return structure;
                } else {
                    if (keyFile.exists()) {
                        in = new BufferedInputStream(new FileInputStream(keyFile));
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
