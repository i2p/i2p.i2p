package net.i2p.router.networkdb.kademlia;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Write out keys to disk when we get them and periodically read ones we don't know
 * about into memory, with newly read routers are also added to the routing table.
 *
 */
class PersistentDataStore extends TransientDataStore {
    private Log _log;
    private String _dbDir;
    private KademliaNetworkDatabaseFacade _facade;
    
    private final static int READ_DELAY = 60*1000;
    
    public PersistentDataStore(RouterContext ctx, String dbDir, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(PersistentDataStore.class);
        _dbDir = dbDir;
        _facade = facade;
        _context.jobQueue().addJob(new ReadJob());
    }
    
    public void restart() {
        _dbDir = _facade.getDbDir();
    }
    
    public DataStructure remove(Hash key) {
        _context.jobQueue().addJob(new RemoveJob(key));
        return super.remove(key);
    }
    
    public void put(Hash key, DataStructure data) {
        if ( (data == null) || (key == null) ) return;
        super.put(key, data);
        _context.jobQueue().addJob(new WriteJob(key, data));
    }
    
    private void accept(LeaseSet ls) {
        super.put(ls.getDestination().calculateHash(), ls);
    }
    private void accept(RouterInfo ri) {
        Hash key = ri.getIdentity().getHash();
        super.put(key, ri);
        // add recently loaded routers to the routing table
        _facade.getKBuckets().add(key);
    }
    
    private class RemoveJob extends JobImpl {
        private Hash _key;
        public RemoveJob(Hash key) {
            super(PersistentDataStore.this._context);
            _key = key;
        }
        public String getName() { return "Remove Key"; }
        public void runJob() {
            _log.info("Removing key " + _key, getAddedBy());
            try {
                File dbDir = getDbDir();
                removeFile(_key, dbDir);
            } catch (IOException ioe) {
                _log.error("Error removing key " + _key, ioe);
            }
        }
    }
    
    private class WriteJob extends JobImpl {
        private Hash _key;
        private DataStructure _data;
        public WriteJob(Hash key, DataStructure data) {
            super(PersistentDataStore.this._context);
            _key = key;
            _data = data;
        }
        public String getName() { return "DB Writer Job"; }
        public void runJob() {
            _log.info("Writing key " + _key);
            FileOutputStream fos = null;
            File dbFile = null;
            try {
                String filename = null;
                File dbDir = getDbDir();
                
                if (_data instanceof LeaseSet)
                    filename = getLeaseSetName(_key);
                else if (_data instanceof RouterInfo)
                    filename = getRouterInfoName(_key);
                else
                    throw new IOException("We don't know how to write objects of type " + _data.getClass().getName());
                
                dbFile = new File(dbDir, filename);
                long dataPublishDate = getPublishDate();
                if (dbFile.lastModified() < dataPublishDate) {
                    // our filesystem is out of date, lets replace it
                    fos = new FileOutputStream(dbFile);
                    try {
                        _data.writeBytes(fos);
                        fos.close();
                        dbFile.setLastModified(dataPublishDate);
                    } catch (DataFormatException dfe) {
                        _log.error("Error writing out malformed object as " + _key + ": " 
                                   + _data, dfe);
                        dbFile.delete();
                    }
                } else {
                    // we've already written the file, no need to waste our time
                }
            } catch (IOException ioe) {
                _log.error("Error writing out the object", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
        private long getPublishDate() {
            if (_data instanceof RouterInfo) {
                return ((RouterInfo)_data).getPublished();
            } else if (_data instanceof LeaseSet) {
                return ((LeaseSet)_data).getEarliestLeaseDate();
            } else {
                return -1;
            }
        }
    }
    
    private class ReadJob extends JobImpl {
        public ReadJob() {
            super(PersistentDataStore.this._context);
        }
        public String getName() { return "DB Read Job"; }
        public void runJob() {
            _log.info("Rereading new files");
            readFiles();
            requeue(READ_DELAY);
        }
        
        private void readFiles() {
            try {
                File dbDir = getDbDir();
                File leaseSetFiles[] = dbDir.listFiles(LeaseSetFilter.getInstance());
                if (leaseSetFiles != null) {
                    for (int i = 0; i < leaseSetFiles.length; i++) {
                        Hash key = getLeaseSetHash(leaseSetFiles[i].getName());
                        if ( (key != null) && (!isKnown(key)) )
                            PersistentDataStore.this._context.jobQueue().addJob(new ReadLeaseJob(leaseSetFiles[i], key));
                    }
                }
                File routerInfoFiles[] = dbDir.listFiles(RouterInfoFilter.getInstance());
                if (routerInfoFiles != null) {
                    for (int i = 0; i < routerInfoFiles.length; i++) {
                        Hash key = getRouterInfoHash(routerInfoFiles[i].getName());
                        if ( (key != null) && (!isKnown(key)) )
                            PersistentDataStore.this._context.jobQueue().addJob(new ReadRouterJob(routerInfoFiles[i], key));
                    }
                }
            } catch (IOException ioe) {
                _log.error("Error reading files in the db dir", ioe);
            }
        }
    }
    
    private class ReadLeaseJob extends JobImpl {
        private File _leaseFile;
        private Hash _key;
        public ReadLeaseJob(File leaseFile, Hash key) {
            super(PersistentDataStore.this._context);
            _leaseFile = leaseFile;
            _key = key;
        }
        public String getName() { return "Read LeaseSet"; }
        private boolean shouldRead() {
            DataStructure data = get(_key);
            if (data == null) return true;
            if (data instanceof LeaseSet) {
                long knownDate = ((LeaseSet)data).getEarliestLeaseDate();
                long fileDate = _leaseFile.lastModified();
                if (fileDate > knownDate)
                    return true;
                else
                    return false;
            } else {
                // wtf
                return true;
            }
        }
        public void runJob() {
            if (!shouldRead()) return;
            try {
                FileInputStream fis = null;
                boolean corrupt = false;
                try {
                    fis = new FileInputStream(_leaseFile);
                    LeaseSet ls = new LeaseSet();
                    ls.readBytes(fis);
                    _facade.store(ls.getDestination().calculateHash(), ls);
                    Object accepted = _facade.lookupLeaseSetLocally(ls.getDestination().calculateHash());
                    if (accepted == null) {
                        _log.info("Refused locally loaded leaseSet - deleting");
                        corrupt = true;
                    }
                } catch (DataFormatException dfe) {
                    _log.warn("Error reading the leaseSet from " + _leaseFile.getAbsolutePath(), dfe);
                    corrupt = true;
                } catch (FileNotFoundException fnfe) {
                    _log.debug("Deleted prior to read.. a race during expiration / load");
                    corrupt = false;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
                if (corrupt) _leaseFile.delete();
            } catch (IOException ioe) {
                _log.warn("Error reading the leaseSet from " + _leaseFile.getAbsolutePath(), ioe);
            }
        }
    }
    
    private class ReadRouterJob extends JobImpl {
        private File _routerFile;
        private Hash _key;
        public ReadRouterJob(File routerFile, Hash key) {
            super(PersistentDataStore.this._context);
            _routerFile = routerFile;
            _key = key;
        }
        public String getName() { return "Read RouterInfo"; }
        
        private boolean shouldRead() {
            DataStructure data = get(_key);
            if (data == null) return true;
            if (data instanceof RouterInfo) {
                long knownDate = ((RouterInfo)data).getPublished();
                long fileDate = _routerFile.lastModified();
                if (fileDate > knownDate)
                    return true;
                else
                    return false;
            } else {
                // wtf
                return true;
            }
        }
        public void runJob() {
            if (!shouldRead()) return;
            try {
                FileInputStream fis = null;
                boolean corrupt = false;
                try {
                    fis = new FileInputStream(_routerFile);
                    RouterInfo ri = new RouterInfo();
                    ri.readBytes(fis);
                    _facade.store(ri.getIdentity().getHash(), ri);
                    Object accepted = _facade.lookupRouterInfoLocally(ri.getIdentity().getHash());
                    if (accepted == null) {
                        _log.info("Refused locally loaded routerInfo - deleting");
                        corrupt = true;
                    }
                } catch (DataFormatException dfe) {
                    _log.warn("Error reading the routerInfo from " + _routerFile.getAbsolutePath(), dfe);
                    corrupt = true;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
                if (corrupt) _routerFile.delete();
            } catch (IOException ioe) {
                _log.warn("Error reading the RouterInfo from " + _routerFile.getAbsolutePath(), ioe);
            }
        }
    }
    
    
    private File getDbDir() throws IOException {
        File f = new File(_dbDir);
        if (!f.exists()) {
            boolean created = f.mkdirs();
            if (!created)
                throw new IOException("Unable to create the DB directory [" + f.getAbsolutePath() + "]");
        }
        if (!f.isDirectory())
            throw new IOException("DB directory [" + f.getAbsolutePath() + "] is not a directory!");
        if (!f.canRead())
            throw new IOException("DB directory [" + f.getAbsolutePath() + "] is not readable!");
        if (!f.canWrite())
            throw new IOException("DB directory [" + f.getAbsolutePath() + "] is not writable!");
        return f;
    }
    
    private final static String LEASESET_PREFIX = "leaseSet-";
    private final static String LEASESET_SUFFIX = ".dat";
    private final static String ROUTERINFO_PREFIX = "routerInfo-";
    private final static String ROUTERINFO_SUFFIX = ".dat";
    
    private String getLeaseSetName(Hash hash) {
        return LEASESET_PREFIX + hash.toBase64() + LEASESET_SUFFIX;
    }
    private String getRouterInfoName(Hash hash) {
        return ROUTERINFO_PREFIX + hash.toBase64() + ROUTERINFO_SUFFIX;
    }
    
    private Hash getLeaseSetHash(String filename) {
        return getHash(filename, LEASESET_PREFIX, LEASESET_SUFFIX);
    }
    
    private Hash getRouterInfoHash(String filename) {
        return getHash(filename, ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);
    }
    
    private Hash getHash(String filename, String prefix, String suffix) {
        try {
            String key = filename.substring(prefix.length());
            key = key.substring(0, key.length() - suffix.length());
            Hash h = new Hash();
            h.fromBase64(key);
            return h;
        } catch (Exception e) {
            _log.warn("Unable to fetch the key from [" + filename + "]", e);
            return null;
        }
    }
    
    private void removeFile(Hash key, File dir) throws IOException {
        String lsName = getLeaseSetName(key);
        String riName = getRouterInfoName(key);
        File f = new File(dir, lsName);
        if (f.exists()) {
            boolean removed = f.delete();
            if (!removed)
                _log.warn("Unable to remove lease set at " + f.getAbsolutePath());
            else
                _log.info("Removed lease set at " + f.getAbsolutePath());
            return;
        }
        f = new File(dir, riName);
        if (f.exists()) {
            boolean removed = f.delete();
            if (!removed)
                _log.warn("Unable to remove router info at " + f.getAbsolutePath());
            else
                _log.info("Removed router info at " + f.getAbsolutePath());
            return;
        }
    }
    
    private final static class LeaseSetFilter implements FilenameFilter {
        private static final FilenameFilter _instance = new LeaseSetFilter();
        public static final FilenameFilter getInstance() { return _instance; }
        public boolean accept(File dir, String name) {
            if (name == null) return false;
            name = name.toUpperCase();
            return (name.startsWith(LEASESET_PREFIX.toUpperCase()) && name.endsWith(LEASESET_SUFFIX.toUpperCase()));
        }
    }
    private final static class RouterInfoFilter implements FilenameFilter {
        private static final FilenameFilter _instance = new RouterInfoFilter();
        public static final FilenameFilter getInstance() { return _instance; }
        public boolean accept(File dir, String name) {
            if (name == null) return false;
            name = name.toUpperCase();
            return (name.startsWith(ROUTERINFO_PREFIX.toUpperCase()) && name.endsWith(ROUTERINFO_SUFFIX.toUpperCase()));
        }
    }
}
