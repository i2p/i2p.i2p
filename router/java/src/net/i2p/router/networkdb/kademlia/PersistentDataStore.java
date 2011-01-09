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
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

/**
 * Write out keys to disk when we get them and periodically read ones we don't know
 * about into memory, with newly read routers are also added to the routing table.
 *
 */
class PersistentDataStore extends TransientDataStore {
    private final Log _log;
    private final File _dbDir;
    private final KademliaNetworkDatabaseFacade _facade;
    private final Writer _writer;
    private final ReadJob _readJob;
    private boolean _initialized;
    
    private final static int READ_DELAY = 60*1000;
    
    /**
     *  @param dbDir relative path
     */
    public PersistentDataStore(RouterContext ctx, String dbDir, KademliaNetworkDatabaseFacade facade) throws IOException {
        super(ctx);
        _log = ctx.logManager().getLog(PersistentDataStore.class);
        _dbDir = getDbDir(dbDir);
        _facade = facade;
        _readJob = new ReadJob();
        _context.jobQueue().addJob(_readJob);
        ctx.statManager().createRateStat("netDb.writeClobber", "How often we clobber a pending netDb write", "NetworkDatabase", new long[] { 20*60*1000 });
        ctx.statManager().createRateStat("netDb.writePending", "How many pending writes are there", "NetworkDatabase", new long[] { 60*1000 });
        ctx.statManager().createRateStat("netDb.writeOut", "How many we wrote", "NetworkDatabase", new long[] { 20*60*1000 });
        ctx.statManager().createRateStat("netDb.writeTime", "How long it took", "NetworkDatabase", new long[] { 20*60*1000 });
        _writer = new Writer();
        I2PThread writer = new I2PThread(_writer, "DBWriter");
        // stop() must be called to flush data to disk
        //writer.setDaemon(true);
        writer.start();
    }

    @Override
    public boolean isInitialized() { return _initialized; }

    // this doesn't stop the read job or the writer, maybe it should?
    @Override
    public void stop() {
        super.stop();
        _writer.flush();
    }
    
    @Override
    public void restart() {
        super.restart();
    }
    
    @Override
    public void rescan() {
        if (_initialized)
            _readJob.wakeup();
    }

    @Override
    public DataStructure get(Hash key) {
        return get(key, true);
    }

    /**
     *  Prepare for having only a partial set in memory and the rest on disk
     *  @param persist if false, call super only, don't access disk
     */
    @Override
    public DataStructure get(Hash key, boolean persist) {
        DataStructure rv =  super.get(key);
/*****
        if (rv != null || !persist)
            return rv;
        rv = _writer.get(key);
        if (rv != null)
            return rv;
        Job rrj = new ReadRouterJob(getRouterInfoName(key), key));
         run in same thread
        rrj.runJob();
*******/    
        return rv;
    }

    @Override
    public DataStructure remove(Hash key) {
        return remove(key, true);
    }

    /*
     *  @param persist if false, call super only, don't access disk
     */
    @Override
    public DataStructure remove(Hash key, boolean persist) {
        if (persist) {
            _writer.remove(key);
            _context.jobQueue().addJob(new RemoveJob(key));
        }
        return super.remove(key);
    }
    
    @Override
    public boolean put(Hash key, DataStructure data) {
        return put(key, data, true);
    }

    /*
     *  @param persist if false, call super only, don't access disk
     *  @return success
     */
    @Override
    public boolean put(Hash key, DataStructure data, boolean persist) {
        if ( (data == null) || (key == null) ) return false;
        boolean rv = super.put(key, data);
        // Don't bother writing LeaseSets to disk
        if (rv && persist && data instanceof RouterInfo)
            _writer.queue(key, data);
        return rv;
    }
    
    private class RemoveJob extends JobImpl {
        private Hash _key;
        public RemoveJob(Hash key) {
            super(PersistentDataStore.this._context);
            _key = key;
        }
        public String getName() { return "Remove Key"; }
        public void runJob() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Removing key " + _key /* , getAddedBy() */);
            try {
                removeFile(_key, _dbDir);
            } catch (IOException ioe) {
                _log.error("Error removing key " + _key, ioe);
            }
        }
    }
    
    /** How many files to write every 10 minutes. Doesn't make sense to limit it,
     *  they just back up in the queue hogging memory.
     */
    private static final int WRITE_LIMIT = 10000;
    private static final long WRITE_DELAY = 10*60*1000;

    /*
     * Queue up writes, write unlimited files every 10 minutes.
     * Since we write all we have, don't save the write order.
     * We store a reference to the data here too,
     * rather than simply pull it from super.get(), because
     * we will soon have to implement a scheme for keeping only
     * a subset of all DataStructures in memory and keeping the rest on disk.
     */
    private class Writer implements Runnable {
        private final Map<Hash, DataStructure>_keys;
        private final Object _waitLock;
        private volatile boolean _quit;

        public Writer() { 
            _keys = new ConcurrentHashMap(64);
            _waitLock = new Object();
        }

        public void queue(Hash key, DataStructure data) {
            int pending = _keys.size();
            boolean exists = (null != _keys.put(key, data));
            if (exists)
                _context.statManager().addRateData("netDb.writeClobber", pending, 0);
            _context.statManager().addRateData("netDb.writePending", pending, 0);
        }

        /** check to see if it's in the write queue */
        public DataStructure get(Hash key) {
            return _keys.get(key);
        }

        public void remove(Hash key) {
            _keys.remove(key);
        }

        public void run() {
            _quit = false;
            Hash key = null;
            DataStructure data = null;
            int count = 0;
            int lastCount = 0;
            long startTime = 0;
            while (true) {
                // get a new iterator every time to get a random entry without
                // having concurrency issues or copying to a List or Array
                Iterator<Map.Entry<Hash, DataStructure>> iter = _keys.entrySet().iterator();
                try {
                    Map.Entry<Hash, DataStructure> entry = iter.next();
                    key = entry.getKey();
                    data = entry.getValue();
                    iter.remove();
                    count++;
                } catch (NoSuchElementException nsee) {
                    lastCount = count;
                    count = 0;
                } catch (IllegalStateException ise) {
                    lastCount = count;
                    count = 0;
                }

                if (key != null) {
                    if (data != null) {
                        // synch with the reader job
                        synchronized (_dbDir) {
                            write(key, data);
                        }
                        data = null;
                    }
                    key = null;
                }
                if (count >= WRITE_LIMIT)
                    count = 0;
                if (count == 0) {
                    if (lastCount > 0) {
                        long time = _context.clock().now() - startTime;
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Wrote " + lastCount + " entries to disk in " + time);
                         _context.statManager().addRateData("netDb.writeOut", lastCount, 0);
                         _context.statManager().addRateData("netDb.writeTime", time, 0);
                    }
                    if (_quit)
                        break;
                    synchronized (_waitLock) {
                        try {
                            _waitLock.wait(WRITE_DELAY);
                        } catch (InterruptedException ie) {}
                    }
                    startTime = _context.clock().now();
                }
            }
        }

        public void flush() {
            synchronized(_waitLock) {
                _quit = true;
                _waitLock.notifyAll();
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

            if (data instanceof LeaseSet)
                filename = getLeaseSetName(key);
            else if (data instanceof RouterInfo)
                filename = getRouterInfoName(key);
            else
                throw new IOException("We don't know how to write objects of type " + data.getClass().getName());

            dbFile = new File(_dbDir, filename);
            long dataPublishDate = getPublishDate(data);
            if (dbFile.lastModified() < dataPublishDate) {
                // our filesystem is out of date, lets replace it
                fos = new SecureFileOutputStream(dbFile);
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
        private long _lastModified;

        public ReadJob() {
            super(PersistentDataStore.this._context);
            _alreadyWarned = false;
        }

        public String getName() { return "DB Read Job"; }

        public void runJob() {
            // check directory mod time to save a lot of object churn in scanning all the file names
            long lastMod = _dbDir.lastModified();
            if (lastMod > _lastModified) {
                _lastModified = lastMod;
                _log.info("Rereading new files");
                // synch with the writer job
                synchronized (_dbDir) {
                    readFiles();
                }
            }
            requeue(READ_DELAY);
        }
        
        public void wakeup() {
            requeue(0);
        }
        
        private void readFiles() {
            int routerCount = 0;

                File routerInfoFiles[] = _dbDir.listFiles(RouterInfoFilter.getInstance());
                if (routerInfoFiles != null) {
                    routerCount += routerInfoFiles.length;
                    if (routerInfoFiles.length > 5)
                        _alreadyWarned = false;
                    for (int i = 0; i < routerInfoFiles.length; i++) {
                        Hash key = getRouterInfoHash(routerInfoFiles[i].getName());
                        if ( (key != null) && (!isKnown(key)) ) {
                            // Run it inline so we don't clog up the job queue, esp. at startup
                            // Also this allows us to wait until it is really done to call checkReseed() and set _initialized
                            //PersistentDataStore.this._context.jobQueue().addJob(new ReadRouterJob(routerInfoFiles[i], key));
                            (new ReadRouterJob(routerInfoFiles[i], key)).runJob();
                        }
                    }
                }
            
            if (!_alreadyWarned) {
                ReseedChecker.checkReseed(_context, routerCount);
                _alreadyWarned = true;
                _initialized = true;
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
            // persist = false to call only super.get()
            DataStructure data = get(_key, false);
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
                    if (ri.getNetworkId() != Router.NETWORK_ID) {
                        corrupt = true;
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("The router "
                                       + ri.getIdentity().calculateHash().toBase64() 
                                       + " is from a different network");
                    } else {
                        try {
                            // persist = false so we don't write what we just read
                            _facade.store(ri.getIdentity().getHash(), ri, false);
                            // when heardAbout() was removed from TransientDataStore, it broke
                            // profile bootstrapping for new routers,
                            // so add it here.
                            getContext().profileManager().heardAbout(ri.getIdentity().getHash(), ri.getPublished());
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
    
    
    private File getDbDir(String dbDir) throws IOException {
        File f = new SecureDirectory(_context.getRouterDir(), dbDir);
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
    
    private static String getLeaseSetName(Hash hash) {
        return LEASESET_PREFIX + hash.toBase64() + LEASESET_SUFFIX;
    }
    private static String getRouterInfoName(Hash hash) {
        return ROUTERINFO_PREFIX + hash.toBase64() + ROUTERINFO_SUFFIX;
    }
    
    private static Hash getLeaseSetHash(String filename) {
        return getHash(filename, LEASESET_PREFIX, LEASESET_SUFFIX);
    }
    
    private static Hash getRouterInfoHash(String filename) {
        return getHash(filename, ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);
    }
    
    private static Hash getHash(String filename, String prefix, String suffix) {
        try {
            String key = filename.substring(prefix.length());
            key = key.substring(0, key.length() - suffix.length());
            //Hash h = new Hash();
            //h.fromBase64(key);
            byte[] b = Base64.decode(key);
            if (b == null)
                return null;
            Hash h = Hash.create(b);
            return h;
        } catch (Exception e) {
            // static
            //_log.warn("Unable to fetch the key from [" + filename + "]", e);
            return null;
        }
    }
    
    private void removeFile(Hash key, File dir) throws IOException {
        String riName = getRouterInfoName(key);
        File f = new File(dir, riName);
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
