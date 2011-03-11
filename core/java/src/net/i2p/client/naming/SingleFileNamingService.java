/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;

/**
 * A naming service based on a single file using the "hosts.txt" format.
 * Supports adds, removes, and listeners.
 *
 * All methods here are case-sensitive.
 * Conversion to lower case is done in HostsTxtNamingService.
 *
 * This does NOT provide .b32.i2p or {b64} resolution.
 * It also does not do any caching.
 * Use from HostsTxtNamingService or chain with another NamingService
 * via MetaNamingService if you need those features.
 *
 * @since 0.8.5
 */
public class SingleFileNamingService extends NamingService {

    private final static Log _log = new Log(SingleFileNamingService.class);
    private final File _file;
    private final ReentrantReadWriteLock _fileLock;

    public SingleFileNamingService(I2PAppContext context, String filename) {
        super(context);
        File file = new File(filename);
        if (!file.isAbsolute())
            file = new File(context.getRouterDir(), filename);
        _file = file;
        _fileLock = new ReentrantReadWriteLock(true);
    }

    /**
     *  @return the base file name
     */
    @Override
    public String getName() {
        return _file.getAbsolutePath();
    }

    /** 
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param lookupOptions ignored
     *  @param storedOptions ignored
     */
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        try {
            String key = getKey(hostname);
            if (key != null)
                return lookupBase64(key);
        } catch (Exception ioe) {
            if (_file.exists())
                _log.error("Error loading hosts file " + _file, ioe);
            else if (_log.shouldLog(Log.WARN))
                _log.warn("Error loading hosts file " + _file, ioe);
        }
        return null;
    }
    
    /** 
     *  @param options ignored
     */
    @Override
    public String reverseLookup(Destination dest, Properties options) {
        String destkey = dest.toBase64();
        BufferedReader in = null;
        getReadLock();
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"), 16*1024);
            String line = null;
            while ( (line = in.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                if (line.indexOf('#') > 0)  // trim off any end of line comment
                    line = line.substring(0, line.indexOf('#')).trim();
                int split = line.indexOf('=');
                if (split <= 0)
                    continue;
                if (destkey.equals(line.substring(split + 1)))
                    return line.substring(0, split);
            }
            return null;
        } catch (Exception ioe) {
            if (_file.exists())
                _log.error("Error loading hosts file " + _file, ioe);
            else if (_log.shouldLog(Log.WARN))
                _log.warn("Error loading hosts file " + _file, ioe);
            return null;
        } finally {
            releaseReadLock();
        }
    }

    /**
     *  Better than DataHelper.loadProps(), doesn't load the whole file into memory,
     *  and stops when it finds a match.
     *
     *  @param host case-sensitive; caller should convert to lower case
     */
    private String getKey(String host) throws IOException {
        BufferedReader in = null;
        getReadLock();
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"), 16*1024);
            String line = null;
            String search = host + '=';
            while ( (line = in.readLine()) != null) {
                if (!line.startsWith(search))
                    continue;
                if (line.indexOf('#') > 0)  // trim off any end of line comment
                    line = line.substring(0, line.indexOf('#')).trim();
                int split = line.indexOf('=');
                return line.substring(split+1);   //.trim() ??????????????
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            releaseReadLock();
        }
        return null;
    }

    /** 
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param options ignored
     */
    @Override
    public boolean put(String hostname, Destination d, Properties options) {
        // try easy way first, most adds are not replaces
        if (putIfAbsent(hostname, d, options))
            return true;
        if (!getWriteLock())
            return false;
        BufferedReader in = null;
        BufferedWriter out = null;
        try {
            File tmp = SecureFile.createTempFile("temp-", ".tmp", _file.getAbsoluteFile().getParentFile());
            out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), "UTF-8"));
            if (_file.exists()) {
                in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"), 16*1024);
                String line = null;
                String search = hostname + '=';
                while ( (line = in.readLine()) != null) {
                    if (line.startsWith(search))
                        continue;
                    out.write(line);
                    out.newLine();
                }
                in.close();
            }
            out.write(hostname);
            out.write('=');
            out.write(d.toBase64());
            out.newLine();
            out.close();
            boolean success = rename(tmp, _file);
            if (success) {
                for (NamingServiceListener nsl : _listeners) { 
                    nsl.entryChanged(this, hostname, d, options);
                }
            }
            return success;
        } catch (IOException ioe) {
            if (in != null) try { in.close(); } catch (IOException e) {}
            if (out != null) try { out.close(); } catch (IOException e) {}
            _log.error("Error adding " + hostname, ioe);
            return false;
        } finally { releaseWriteLock(); }
    }

    /** 
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param options ignored
     */
    @Override
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        if (!getWriteLock())
            return false;
        OutputStream out = null;
        try {
            // simply check if present, and if not, append
            try {
                if (getKey(hostname) != null)
                    return false;
            } catch (IOException ioe) {
                if (_file.exists()) {
                    _log.error("Error adding " + hostname, ioe);
                    return false;
                }
                // else new file
            }
            out = new SecureFileOutputStream(_file, true);
            // FIXME fails if previous last line didn't have a trailing \n
            out.write(hostname.getBytes("UTF-8"));
            out.write('=');
            out.write(d.toBase64().getBytes());
            out.write('\n');
            out.close();
            for (NamingServiceListener nsl : _listeners) { 
                nsl.entryAdded(this, hostname, d, options);
            }
            return true;
        } catch (IOException ioe) {
            if (out != null) try { out.close(); } catch (IOException e) {}
            _log.error("Error adding " + hostname, ioe);
            return false;
        } finally { releaseWriteLock(); }
    }

    /** 
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param options ignored
     */
    @Override
    public boolean remove(String hostname, Properties options) {
        if (!getWriteLock())
            return false;
        if (!_file.exists())
            return false;
        BufferedReader in = null;
        BufferedWriter out = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"), 16*1024);
            File tmp = SecureFile.createTempFile("temp-", ".tmp", _file.getAbsoluteFile().getParentFile());
            out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), "UTF-8"));
            String line = null;
            String search = hostname + '=';
            boolean success = false;
            while ( (line = in.readLine()) != null) {
                if (line.startsWith(search)) {
                    success = true;
                    continue;
                }
                out.write(line);
                out.newLine();
            }
            in.close();
            out.close();
            if (!success) {
                tmp.delete();
                return false;
            }
            success = rename(tmp, _file);
            if (success) {
                for (NamingServiceListener nsl : _listeners) { 
                    nsl.entryRemoved(this, hostname);
                }
            }
            return success;
        } catch (IOException ioe) {
            if (in != null) try { in.close(); } catch (IOException e) {}
            if (out != null) try { out.close(); } catch (IOException e) {}
            _log.error("Error removing " + hostname, ioe);
            return false;
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * @param options As follows:
     *                Key "startsWith": return only those starting with
     */
    @Override
    public Map<String, Destination> getEntries(Properties options) {
        if (!_file.exists())
            return Collections.EMPTY_MAP;
        String startsWith = "";
        if (options != null) {
            startsWith = options.getProperty("startsWith");
        }
        BufferedReader in = null;
        getReadLock();
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"), 16*1024);
            String line = null;
            String search = startsWith + '=';
            Map<String, Destination> rv = new HashMap();
            while ( (line = in.readLine()) != null) {
                if ((!startsWith.equals("")) && !line.startsWith(search))
                    continue;
                if (line.startsWith("#"))
                    continue;
                if (line.indexOf('#') > 0)  // trim off any end of line comment
                    line = line.substring(0, line.indexOf('#')).trim();
                int split = line.indexOf('=');
                if (split <= 0)
                    continue;
                String key = line.substring(split);
                String b64 = line.substring(split+1);   //.trim() ??????????????
                try {
                    Destination dest = new Destination(b64);
                    rv.put(key, dest);
                } catch (DataFormatException dfe) {}
            }
            return rv;
        } catch (IOException ioe) {
            _log.error("getEntries error", ioe);
            return Collections.EMPTY_MAP;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            releaseReadLock();
        }
    }

    /** 
     *  @param options ignored
     */
    @Override
    public int size(Properties options) {
        if (!_file.exists())
            return 0;
        BufferedReader in = null;
        getReadLock();
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), "UTF-8"), 16*1024);
            String line = null;
            int rv = 0;
            while ( (line = in.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                rv++;
            }
            return rv;
        } catch (IOException ioe) {
            _log.error("size() error", ioe);
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            releaseReadLock();
        }
    }

    private static boolean rename(File from, File to) {
        boolean success = false;
        boolean isWindows = System.getProperty("os.name").startsWith("Win");
        // overwrite fails on windows
        if (!isWindows)
            success = from.renameTo(to);
        if (!success) {
            to.delete();
            success = from.renameTo(to);
            if (!success) {
                // hard way
                success = FileUtil.copy(from.getAbsolutePath(), to.getAbsolutePath(), true, true);
                from.delete();
            }
        }
        return success;
    }

    private void getReadLock() {
        _fileLock.readLock().lock();
    }

    private void releaseReadLock() {
        _fileLock.readLock().unlock();
    }

    /** @return true if the lock was acquired */
    private boolean getWriteLock() {
        try {
            boolean rv = _fileLock.writeLock().tryLock(10000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldLog(Log.WARN))
                _log.warn("no lock, size is: " + _fileLock.getQueueLength(), new Exception("rats"));
            return rv;
        } catch (InterruptedException ie) {}
        return false;
    }

    private void releaseWriteLock() {
        _fileLock.writeLock().unlock();
    }

    public static void main(String[] args) {
        NamingService ns = new SingleFileNamingService(I2PAppContext.getGlobalContext(), "hosts.txt");
        Destination d = new Destination();
        try {
            d.readBytes(new byte[387], 0);
        } catch (DataFormatException dfe) {}
        boolean b = ns.put("aaaaa", d);
        System.out.println("Test 1 pass? " + b);
        b = ns.put("bbbbb", d);
        System.out.println("Test 2 pass? " + b);
        b = ns.remove("aaaaa");
        System.out.println("Test 3 pass? " + b);
        b = ns.lookup("aaaaa") == null;
        System.out.println("Test 4 pass? " + b);
        b = ns.lookup("bbbbb") != null;
        System.out.println("Test 5 pass? " + b);
        b = !ns.putIfAbsent("bbbbb", d);
        System.out.println("Test 6 pass? " + b);
    }
}
