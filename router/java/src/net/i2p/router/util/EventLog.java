package net.i2p.router.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Simple event logger for occasional events,
 *  with caching for reads.
 *  Does not keep the file open.
 *  @since 0.9.3
 */
public class EventLog {

    private final I2PAppContext _context;
    private final File _file;
    /** event to cached map */
    private final Map<String, SortedMap<Long, String>> _cache;
    /** event to starting time of cached map */
    private final Map<String, Long> _cacheTime;

    /** for convenience, not required */
    public static final String ABORTED = "aborted";
    public static final String BECAME_FLOODFILL = "becameFloodfill";
    public static final String CHANGE_IP = "changeIP";
    public static final String CHANGE_PORT = "changePort";
    public static final String CLOCK_SHIFT = "clockShift";
    public static final String CRASHED = "crashed";
    public static final String CRITICAL = "critical";
    public static final String INSTALLED = "installed";
    public static final String INSTALL_FAILED = "installFailed";
    public static final String NETWORK = "network";
    public static final String NEW_IDENT = "newIdent";
    public static final String NOT_FLOODFILL = "disabledFloodfill";
    public static final String OOM = "oom";
    public static final String REACHABILITY = "reachability";
    public static final String REKEYED = "rekeyed";
    public static final String RESEED = "reseed";
    public static final String SOFT_RESTART = "softRestart";
    public static final String STARTED = "started";
    public static final String STOPPED = "stopped";
    public static final String UPDATED = "updated";
    public static final String WATCHDOG = "watchdog";

    /**
     *  @param file should be absolute
     */
    public EventLog(I2PAppContext ctx, File file) {
        //if (!file.isAbsolute())
        //    throw new IllegalArgumentException();
        _context = ctx;
        _file = file;
        _cache = new HashMap<String, SortedMap<Long, String>>(4);
        _cacheTime = new HashMap<String, Long>(4);
    }

    /**
     *  Append an event. Fails silently.
     *  @param event no spaces, e.g. "started"
     *  @throws IllegalArgumentException if event contains a space or newline
     */
    public void addEvent(String event) {
        addEvent(event, null);
    }

    /**
     *  Append an event. Fails silently.
     *  @param event no spaces or newlines, e.g. "started"
     *  @param info no newlines, may be blank or null
     *  @throws IllegalArgumentException if event contains a space or either contains a newline
     */
    public synchronized void addEvent(String event, String info) {
        if (event.contains(" ") || event.contains("\n") ||
            (info != null && info.contains("\n")))
            throw new IllegalArgumentException();
        _cache.remove(event);
        _cacheTime.remove(event);
        OutputStream out = null;
        try {
            out = new SecureFileOutputStream(_file, true);
            StringBuilder buf = new StringBuilder(128);
            buf.append(_context.clock().now()).append(' ').append(event);
            if (info != null && info.length() > 0)
                buf.append(' ').append(info);
            if (SystemVersion.isWindows())
                buf.append('\r');
            buf.append('\n');
            out.write(buf.toString().getBytes("UTF-8"));
        } catch (IOException ioe) {
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Caches.
     *  Fails silently.
     *  @param event matching this event only, case sensitive
     *  @param since since this time, 0 for all
     *  @return non-null, Map of times to (possibly empty) info strings, sorted, earliest first, unmodifiable
     */
    public synchronized SortedMap<Long, String> getEvents(String event, long since) {
        SortedMap<Long, String> rv = _cache.get(event);
        if (rv != null) {
            Long cacheTime = _cacheTime.get(event);
            if (cacheTime != null) {
                if (since >= cacheTime.longValue())
                    return rv.tailMap(Long.valueOf(since));
            }
        }
        rv = new TreeMap<Long, String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(_file), "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    String[] s = DataHelper.split(line.trim(), " ", 3);
                    if (!s[1].equals(event))
                        continue;
                    long time = Long.parseLong(s[0]);
                    if (time <= since)
                        continue;
                    Long ltime = Long.valueOf(time);
                    String info = s.length > 2 ? s[2] : "";
                    rv.put(ltime, info);
                } catch (IndexOutOfBoundsException ioobe) {
                } catch (NumberFormatException nfe) {
                }
            }
            rv = Collections.unmodifiableSortedMap(rv);
            _cache.put(event, rv);
            _cacheTime.put(event, Long.valueOf(since));
        } catch (IOException ioe) {
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        return rv;
    }

    /**
     *  All events since a given time.
     *  Does not cache. Fails silently.
     *  Values in the returned map have the format "event[ info]".
     *  Events do not contain spaces.
     *
     *  @param since since this time, 0 for all
     *  @return non-null, Map of times to info strings, sorted, earliest first, unmodifiable
     *  @since 0.9.14
     */
    public synchronized SortedMap<Long, String> getEvents(long since) {
        SortedMap<Long, String> rv = new TreeMap<Long, String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(_file), "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    String[] s = DataHelper.split(line.trim(), " ", 2);
                    if (s.length < 2)
                        continue;
                    long time = Long.parseLong(s[0]);
                    if (time <= since)
                        continue;
                    Long ltime = Long.valueOf(time);
                    rv.put(ltime, s[1]);
                } catch (IndexOutOfBoundsException ioobe) {
                } catch (NumberFormatException nfe) {
                }
            }
            rv = Collections.unmodifiableSortedMap(rv);
        } catch (IOException ioe) {
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        return rv;
    }
}
