package net.i2p.router.networkdb.kademlia;
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
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

/**
 * Write out keys to disk when we get them and periodically read ones we don't know
 * about into memory, with newly read routers are also added to the routing table.
 *
 * Public only for access to static methods by startup classes
 *
 */
public class PersistentDataStore extends TransientDataStore {
    private final File _dbDir;
    private final KademliaNetworkDatabaseFacade _facade;
    private final Writer _writer;
    private final ReadJob _readJob;
    private volatile boolean _initialized;
    private final boolean _flat;
    private final int _networkID;
    
    private final static int READ_DELAY = 2*60*1000;
    private static final String PROP_FLAT = "router.networkDatabase.flat";
    static final String DIR_PREFIX = "r";
    private static final String B64 = Base64.ALPHABET_I2P;

    /**
     *  @param dbDir relative path
     */
    public PersistentDataStore(RouterContext ctx, String dbDir, KademliaNetworkDatabaseFacade facade) throws IOException {
        super(ctx);
        _networkID = ctx.router().getNetworkID();
        _flat = ctx.getBooleanProperty(PROP_FLAT);
        _dbDir = getDbDir(dbDir);
        _facade = facade;
        _readJob = new ReadJob();
        _context.jobQueue().addJob(_readJob);
        ctx.statManager().createRateStat("netDb.writeClobber", "How often we clobber a pending netDb write", "NetworkDatabase", new long[] { 20*60*1000 });
        ctx.statManager().createRateStat("netDb.writePending", "How many pending writes are there", "NetworkDatabase", new long[] { 60*1000 });
        ctx.statManager().createRateStat("netDb.writeOut", "How many we wrote", "NetworkDatabase", new long[] { 20*60*1000 });
        ctx.statManager().createRateStat("netDb.writeTime", "How long it took", "NetworkDatabase", new long[] { 20*60*1000 });
        //ctx.statManager().createRateStat("netDb.readTime", "How long one took", "NetworkDatabase", new long[] { 20*60*1000 });
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
    public DatabaseEntry get(Hash key) {
        return get(key, true);
    }

    /**
     *  Prepare for having only a partial set in memory and the rest on disk
     *  @param persist if false, call super only, don't access disk
     */
    @Override
    public DatabaseEntry get(Hash key, boolean persist) {
        DatabaseEntry rv =  super.get(key);
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
    public DatabaseEntry remove(Hash key) {
        return remove(key, true);
    }

    /*
     *  @param persist if false, call super only, don't access disk
     */
    @Override
    public DatabaseEntry remove(Hash key, boolean persist) {
        if (persist) {
            _writer.remove(key);
            _context.jobQueue().addJob(new RemoveJob(key));
        }
        return super.remove(key);
    }
    
    @Override
    public boolean put(Hash key, DatabaseEntry data) {
        return put(key, data, true);
    }

    /*
     *  @param persist if false, call super only, don't access disk
     *  @return success
     */
    @Override
    public boolean put(Hash key, DatabaseEntry data, boolean persist) {
        if ( (data == null) || (key == null) ) return false;
        boolean rv = super.put(key, data);
        // Don't bother writing LeaseSets to disk
        if (rv && persist && data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
            _writer.queue(key, data);
        return rv;
    }
    
    private class RemoveJob extends JobImpl {
        private final Hash _key;
        public RemoveJob(Hash key) {
            super(PersistentDataStore.this._context);
            _key = key;
        }
        public String getName() { return "Delete RI file"; }
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
     * a subset of all DatabaseEntrys in memory and keeping the rest on disk.
     */
    private class Writer implements Runnable, Flushable {
        private final Map<Hash, DatabaseEntry>_keys;
        private final Object _waitLock;
        private volatile boolean _quit;

        public Writer() { 
            _keys = new ConcurrentHashMap<Hash, DatabaseEntry>(64);
            _waitLock = new Object();
        }

        public void queue(Hash key, DatabaseEntry data) {
            int pending = _keys.size();
            boolean exists = (null != _keys.put(key, data));
            if (exists)
                _context.statManager().addRateData("netDb.writeClobber", pending);
            _context.statManager().addRateData("netDb.writePending", pending);
        }

        public void remove(Hash key) {
            _keys.remove(key);
        }

        public void run() {
            _quit = false;
            Hash key = null;
            DatabaseEntry data = null;
            int count = 0;
            int lastCount = 0;
            long startTime = 0;
            while (true) {
                // get a new iterator every time to get a random entry without
                // having concurrency issues or copying to a List or Array
                Iterator<Map.Entry<Hash, DatabaseEntry>> iter = _keys.entrySet().iterator();
                try {
                    Map.Entry<Hash, DatabaseEntry> entry = iter.next();
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
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Wrote " + lastCount + " entries to disk in " + time);
                         _context.statManager().addRateData("netDb.writeOut", lastCount);
                         _context.statManager().addRateData("netDb.writeTime", time);
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
    
    private void write(Hash key, DatabaseEntry data) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Writing key " + key);
        OutputStream fos = null;
        File dbFile = null;
        try {
            String filename = null;

            if (data.getType() == DatabaseEntry.KEY_TYPE_LEASESET)
                filename = getLeaseSetName(key);
            else if (data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                filename = getRouterInfoName(key);
            else
                throw new IOException("We don't know how to write objects of type " + data.getClass().getName());

            dbFile = new File(_dbDir, filename);
            long dataPublishDate = getPublishDate(data);
            if (dbFile.lastModified() < dataPublishDate) {
                // our filesystem is out of date, lets replace it
                fos = new SecureFileOutputStream(dbFile);
                fos = new BufferedOutputStream(fos);
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
    private long getPublishDate(DatabaseEntry data) {
        return data.getDate();
    }
    
    /**
     *  This was mostly for manual reseeding, i.e. the user manually
     *  copies RI files to the directory. Nobody does this,
     *  so this is run way too often.
     *
     *  But it's also for migrating and reading the files after a reseed.
     *  Reseed task calls wakeup() on completion.
     *  As of 0.9.4, also initiates an automatic reseed if necessary.
     */
    private class ReadJob extends JobImpl {
        private volatile long _lastModified;
        private volatile long _lastReseed;
        private static final int MIN_ROUTERS = KademliaNetworkDatabaseFacade.MIN_RESEED;
        private static final long MIN_RESEED_INTERVAL = 90*60*1000;

        public ReadJob() {
            super(PersistentDataStore.this._context);
        }

        public String getName() { return "DB Read Job"; }

        public void runJob() {
            if (getContext().router().gracefulShutdownInProgress()) {
                // don't cause more disk I/O while saving,
                // or start a reseed
                requeue(READ_DELAY);
                return;
            }
            long now = getContext().clock().now();
            // check directory mod time to save a lot of object churn in scanning all the file names
            long lastMod = _dbDir.lastModified();
            // if size() (= RI + LS) is too low, call anyway to check for reseed
            boolean shouldScan = lastMod > _lastModified || size() < MIN_ROUTERS + 10;
            if (!shouldScan && !_flat) {
                for (int j = 0; j < B64.length(); j++) {
                    File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                    if (subdir.lastModified() > _lastModified) {
                        shouldScan = true;
                        break;
                    }
                }
            }
            if (shouldScan) {
                _log.info("Rereading new files");
                // synch with the writer job
                synchronized (_dbDir) {
                    // _lastModified must be 0 for the first run
                    readFiles();
                }
                _lastModified = now;
            }
            requeue(READ_DELAY);
        }
        
        public void wakeup() {
            requeue(0);
        }
        
        private void readFiles() {
            int routerCount = 0;

            File routerInfoFiles[] = _dbDir.listFiles(RI_FILTER);
            if (_flat) {
                if (routerInfoFiles != null) {
                    routerCount = routerInfoFiles.length;
                    for (int i = 0; i < routerInfoFiles.length; i++) {
                        // drop out if the router gets killed right after startup
                        if (!_context.router().isAlive())
                            break;
                        Hash key = getRouterInfoHash(routerInfoFiles[i].getName());
                        if ( (key != null) && (!isKnown(key)) ) {
                            // Run it inline so we don't clog up the job queue, esp. at startup
                            // Also this allows us to wait until it is really done to call checkReseed() and set _initialized
                            //PersistentDataStore.this._context.jobQueue().addJob(new ReadRouterJob(routerInfoFiles[i], key));
                            //long start = System.currentTimeMillis();
                            (new ReadRouterJob(routerInfoFiles[i], key)).runJob();
                            //_context.statManager().addRateData("netDb.readTime", System.currentTimeMillis() - start);
                        }
                    }
                }
            } else {
                // move all new RIs to subdirs, then scan those
                if (routerInfoFiles != null)
                    migrate(_dbDir, routerInfoFiles);
                // Loading the files in-order causes clumping in the kbuckets,
                // and bias on early peer selection, so first collect all the files,
                // then shuffle and load.
                List<File> toRead = new ArrayList<File>(2048);
                for (int j = 0; j < B64.length(); j++) {
                    File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                    File[] files = subdir.listFiles(RI_FILTER);
                    if (files == null)
                        continue;
                    long lastMod = subdir.lastModified();
                    if (routerCount >= MIN_ROUTERS && lastMod <= _lastModified)
                        continue;
                    routerCount += files.length;
                    if (lastMod <= _lastModified)
                        continue;
                    for (int i = 0; i < files.length; i++) {
                        toRead.add(files[i]);
                    }
                }
                Collections.shuffle(toRead, _context.random());
                for (File file : toRead) {
                    Hash key = getRouterInfoHash(file.getName());
                    if (key != null && !isKnown(key))
                        (new ReadRouterJob(file, key)).runJob();
                }
            }
            
            if (!_initialized) {
                _initialized = true;
                if (_facade.reseedChecker().checkReseed(routerCount)) {
                    _lastReseed = _context.clock().now();
                    // checkReseed will call wakeup() when done and we will run again
                } else {
                    _context.router().setNetDbReady();
                }
            } else if (_lastReseed < _context.clock().now() - MIN_RESEED_INTERVAL) {
                int count = Math.min(routerCount, size());
                if (count < MIN_ROUTERS) {
                    if (_facade.reseedChecker().checkReseed(count))
                        _lastReseed = _context.clock().now();
                        // checkReseed will call wakeup() when done and we will run again
                } else {
                    _context.router().setNetDbReady();
                }
            }
        }
    }
    
    private class ReadRouterJob extends JobImpl {
        private final File _routerFile;
        private final Hash _key;
        private long _knownDate;

        /**
         *  @param key must match the RI hash in the file
         */
        public ReadRouterJob(File routerFile, Hash key) {
            super(PersistentDataStore.this._context);
            _routerFile = routerFile;
            _key = key;
        }

        public String getName() { return "Read RouterInfo"; }
        
        private boolean shouldRead() {
            // persist = false to call only super.get()
            DatabaseEntry data = get(_key, false);
            if (data == null) return true;
            if (data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                _knownDate = ((RouterInfo)data).getPublished();
                long fileDate = _routerFile.lastModified();
                // don't overwrite recent netdb RIs with reseed data
                return fileDate > _knownDate + (60*60*1000);
            } else {
                // safety measure - prevent injection from reseeding
                _log.error("Prevented LS overwrite by RI " + _key + " from " + _routerFile);
                return false;
            }
        }

        public void runJob() {
            if (!shouldRead()) return;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reading " + _routerFile);

                InputStream fis = null;
                boolean corrupt = false;
                try {
                    fis = new FileInputStream(_routerFile);
                    fis = new BufferedInputStream(fis);
                    RouterInfo ri = new RouterInfo();
                    ri.readBytes(fis, true);  // true = verify sig on read
                    if (ri.getNetworkId() != _networkID) {
                        corrupt = true;
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("The router "
                                       + ri.getIdentity().calculateHash().toBase64() 
                                       + " is from a different network");
                    } else if (!ri.getIdentity().calculateHash().equals(_key)) {
                        // prevent injection from reseeding
                        // this is checked in KNDF.validate() but catch it sooner and log as error.
                        corrupt = true;
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(ri.getIdentity().calculateHash() + " does not match " + _key + " from " + _routerFile);
                    } else if (ri.getPublished() <= _knownDate) {
                        // Don't store but don't delete
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Skipping since netdb newer than " + _routerFile);
                    } else if (getContext().blocklist().isBlocklisted(ri)) {
                        corrupt = true;
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(ri.getHash() + " is blocklisted");
                    } else {
                        try {
                            // persist = false so we don't write what we just read
                            _facade.store(ri.getIdentity().getHash(), ri, false);
                            // when heardAbout() was removed from TransientDataStore, it broke
                            // profile bootstrapping for new routers,
                            // so add it here.
                            getContext().profileManager().heardAbout(ri.getIdentity().getHash(), ri.getPublished());
                        } catch (IllegalArgumentException iae) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Refused locally loaded routerInfo - deleting", iae);
                            corrupt = true;
                        }
                    }
                } catch (DataFormatException dfe) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Error reading the routerInfo from " + _routerFile.getName(), dfe);
                    corrupt = true;
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Unable to read the router reference in " + _routerFile.getName(), ioe);
                    corrupt = true;
                } catch (RuntimeException e) {
                    // key certificate problems, etc., don't let one bad RI kill the whole thing
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Unable to read the router reference in " + _routerFile.getName(), e);
                    corrupt = true;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
                if (corrupt) _routerFile.delete();
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
        if (_flat) {
            unmigrate(f);
        } else {
            for (int j = 0; j < B64.length(); j++) {
                File subdir = new SecureDirectory(f, DIR_PREFIX + B64.charAt(j));
                if (!subdir.exists())
                    subdir.mkdir();
            }
            File routerInfoFiles[] = f.listFiles(RI_FILTER);
            if (routerInfoFiles != null)
                migrate(f, routerInfoFiles);
        }
        return f;
    }

    /**
     *  Migrate from two-level to one-level directory structure
     *  @since 0.9.5
     */
    private static void unmigrate(File dbdir) {
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new File(dbdir, DIR_PREFIX + B64.charAt(j));
            File[] files = subdir.listFiles(RI_FILTER);
            if (files == null)
                continue;
            for (int i = 0; i < files.length; i++) {
                File from = files[i];
                File to = new File(dbdir, from.getName());
                FileUtil.rename(from, to);
            }
        }
    }

    /**
     *  Migrate from one-level to two-level directory structure
     *  @since 0.9.5
     */
    private static void migrate(File dbdir, File[] files) {
        for (int i = 0; i < files.length; i++) {
            File from = files[i];
            if (!from.isFile())
                continue;
            File dir = new File(dbdir, DIR_PREFIX + from.getName().charAt(ROUTERINFO_PREFIX.length()));
            File to = new File(dir, from.getName());
            FileUtil.rename(from, to);
        }
    }
    
    private final static String LEASESET_PREFIX = "leaseSet-";
    private final static String LEASESET_SUFFIX = ".dat";
    private final static String ROUTERINFO_PREFIX = "routerInfo-";
    private final static String ROUTERINFO_SUFFIX = ".dat";

    /** @since 0.9.34 */
    public static final FileFilter RI_FILTER = new FileSuffixFilter(ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);
    
    private static String getLeaseSetName(Hash hash) {
        return LEASESET_PREFIX + hash.toBase64() + LEASESET_SUFFIX;
    }

    private String getRouterInfoName(Hash hash) {
        String b64 = hash.toBase64();
        if (_flat)
            return ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
        return DIR_PREFIX + b64.charAt(0) + File.separatorChar + ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
    }

    /**
     *  The persistent RI file for a hash.
     *  This is available before the netdb subsystem is running, so we can delete our old RI.
     *
     *  @return non-null, should be absolute, does not necessarily exist
     *  @since 0.9.23
     */
    public static File getRouterInfoFile(RouterContext ctx, Hash hash) {
        String b64 = hash.toBase64();
        File dir = new File(ctx.getRouterDir(), ctx.getProperty(KademliaNetworkDatabaseFacade.PROP_DB_DIR, KademliaNetworkDatabaseFacade.DEFAULT_DB_DIR));
        if (ctx.getBooleanProperty(PROP_FLAT))
            return new File(dir, ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX);
        return new File(dir, DIR_PREFIX + b64.charAt(0) + File.separatorChar + ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX);
    }
    
    /**
     *  Package private for installer BundleRouterInfos
     */
    static Hash getRouterInfoHash(String filename) {
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
        } catch (RuntimeException e) {
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
            if (!removed) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to remove router info at " + f.getAbsolutePath());
            } else if (_log.shouldLog(Log.INFO)) {
                _log.info("Removed router info at " + f.getAbsolutePath());
            }
            return;
        }
    }
}
