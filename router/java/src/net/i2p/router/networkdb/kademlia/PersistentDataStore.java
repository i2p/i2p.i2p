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
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.FileStreamFactory;
import net.i2p.util.I2PFile;
import net.i2p.util.I2PThread;
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
    private Writer _writer;
    
    private final static int READ_DELAY = 60*1000;
    
    public PersistentDataStore(RouterContext ctx, String dbDir, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(PersistentDataStore.class);
        _dbDir = dbDir;
        _facade = facade;
        _context.jobQueue().addJob(new ReadJob());
        ctx.statManager().createRateStat("netDb.writeClobber", "How often we clobber a pending netDb write", "NetworkDatabase", new long[] { 60*1000, 10*60*1000 });
        ctx.statManager().createRateStat("netDb.writePending", "How many pending writes are there", "NetworkDatabase", new long[] { 60*1000, 10*60*1000 });
        _writer = new Writer();
        I2PThread writer = new I2PThread(_writer, "DBWriter");
        writer.setDaemon(true);
        writer.start();
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
        // Don't bother writing LeaseSets to disk
        if (data instanceof RouterInfo)
            _writer.queue(key, data);
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
            _log.info("Removing key " + _key /* , getAddedBy() */);
            try {
                File dbDir = getDbDir();
                removeFile(_key, dbDir);
            } catch (IOException ioe) {
                _log.error("Error removing key " + _key, ioe);
            }
        }
    }
    
    /*
     * Queue up writes, write up to 600 files every 10 minutes
     */
    private class Writer implements Runnable {
        private Map _keys;
        private List _keyOrder;
        public Writer() { 
            _keys = new HashMap(64);
            _keyOrder = new ArrayList(64);
        }
        public void queue(Hash key, DataStructure data) {
            boolean exists = false;
            int pending = 0;
            synchronized (_keys) {
                pending = _keys.size();
                exists = (null != _keys.put(key, data));
                if (!exists)
                    _keyOrder.add(key);
                _keys.notifyAll();
            }
            if (exists)
                _context.statManager().addRateData("netDb.writeClobber", pending, 0);
            _context.statManager().addRateData("netDb.writePending", pending, 0);
        }
        public void run() {
            Hash key = null;
            DataStructure data = null;
            int count = 0;
            while (true) { // hmm, probably want a shutdown handle... though this is a daemon thread
                try {
                    synchronized (_keys) {
                        if (_keyOrder.size() <= 0) {
                            count = 0;
                            _keys.wait();
                        } else {
                            count++;
                            key = (Hash)_keyOrder.remove(0);
                            data = (DataStructure)_keys.remove(key);
                        }
                    }
                } catch (InterruptedException ie) {}
                
                if ( (key != null) && (data != null) )
                    write(key, data);
                key = null;
                data = null;
                if (count >= 600)
                    count = 0;
                if (count == 0)
                    try { Thread.sleep(10*60*1000); } catch (InterruptedException ie) {}
            }
        }
    }
    
    private void write(Hash key, DataStructure data) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Writing key " + key);
        FileOutputStream fos = null;
        File dbFile = null;
        try {
            String filename = null;
            File dbDir = getDbDir();

            if (data instanceof LeaseSet)
                filename = getLeaseSetName(key);
            else if (data instanceof RouterInfo)
                filename = getRouterInfoName(key);
            else
                throw new IOException("We don't know how to write objects of type " + data.getClass().getName());

            dbFile = new I2PFile(dbDir, filename);
            long dataPublishDate = getPublishDate(data);
            if (dbFile.lastModified() < dataPublishDate) {
                // our filesystem is out of date, lets replace it
                fos = FileStreamFactory.getFileOutputStream(dbFile);
                try {
                    data.writeBytes(fos);
                    fos.close();
                    dbFile.setLastModified(dataPublishDate);
                } catch (DataFormatException dfe) {
                    _log.error("Error writing out malformed object as " + key + ": " 
                               + data, dfe);
                    dbFile.delete();
                }
            } else {
                // we've already written the file, no need to waste our time
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Not writing " + key.toBase64() + ", as its up to date on disk (file mod-publish=" +
                               (dbFile.lastModified()-dataPublishDate) + ")");
            }
        } catch (IOException ioe) {
            _log.error("Error writing out the object", ioe);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
    private long getPublishDate(DataStructure data) {
        if (data instanceof RouterInfo) {
            return ((RouterInfo)data).getPublished();
        } else if (data instanceof LeaseSet) {
            return ((LeaseSet)data).getEarliestLeaseDate();
        } else {
            return -1;
        }
    }
    
    /** This is only for manual reseeding? Why bother every 60 sec??? */
    private class ReadJob extends JobImpl {
        private boolean _alreadyWarned;
        public ReadJob() {
            super(PersistentDataStore.this._context);
            _alreadyWarned = false;
        }
        public String getName() { return "DB Read Job"; }
        public void runJob() {
            _log.info("Rereading new files");
            readFiles();
            requeue(READ_DELAY);
        }
        
        private void readFiles() {
            int routerCount = 0;
            try {
                File dbDir = getDbDir();
                File routerInfoFiles[] = dbDir.listFiles(RouterInfoFilter.getInstance());
                if (routerInfoFiles != null) {
                    routerCount += routerInfoFiles.length;
                    if (routerInfoFiles.length > 5)
                        _alreadyWarned = false;
                    for (int i = 0; i < routerInfoFiles.length; i++) {
                        Hash key = getRouterInfoHash(routerInfoFiles[i].getName());
                        if ( (key != null) && (!isKnown(key)) )
                            PersistentDataStore.this._context.jobQueue().addJob(new ReadRouterJob(routerInfoFiles[i], key));
                    }
                }
            } catch (IOException ioe) {
                _log.error("Error reading files in the db dir", ioe);
            }
            
            if ( (routerCount <= 5) && (!_alreadyWarned) ) {
                _log.error("Very few routerInfo files remaining - please reseed");
                _alreadyWarned = true;
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
                    fis = FileStreamFactory.getFileInputStream(_routerFile);
                    RouterInfo ri = new RouterInfo();
                    ri.readBytes(fis);
                    if (ri.getNetworkId() != Router.NETWORK_ID) {
                        corrupt = true;
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("The router "
                                       + ri.getIdentity().calculateHash().toBase64() 
                                       + " is from a different network");
                    } else {
                        try {
                            _facade.store(ri.getIdentity().getHash(), ri);
                        } catch (IllegalArgumentException iae) {
                            _log.info("Refused locally loaded routerInfo - deleting");
                            corrupt = true;
                        }
                    }
                } catch (DataFormatException dfe) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Error reading the routerInfo from " + _routerFile.getName(), dfe);
                    corrupt = true;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
                if (corrupt) _routerFile.delete();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to read the router reference in " + _routerFile.getName(), ioe);
            }
        }
    }
    
    
    private File getDbDir() throws IOException {
        File f = new I2PFile(_dbDir);
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
        String riName = getRouterInfoName(key);
        File f = new I2PFile(dir, riName);
        if (f.exists()) {
            boolean removed = f.delete();
            if (!removed)
                _log.warn("Unable to remove router info at " + f.getAbsolutePath());
            else
                _log.info("Removed router info at " + f.getAbsolutePath());
            return;
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
