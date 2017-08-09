package net.i2p.data.router;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.util.ConvertToHash;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/**
 * Component to manage the munging of hashes into routing keys - given a hash, 
 * perform some consistent transformation against it and return the result.
 * This transformation is fed by the current "mod data".  
 *
 * Right now the mod data is the current date (GMT) as a string: "yyyyMMdd",
 * and the transformation takes the original hash, appends the bytes of that mod data,
 * then returns the SHA256 of that concatenation.
 *
 * Do we want this to simply do the XOR of the SHA256 of the current mod data and
 * the key?  does that provide the randomization we need?  It'd save an SHA256 op.
 * Bah, too much effort to think about for so little gain.  Other algorithms may come
 * into play layer on about making periodic updates to the routing key for data elements
 * to mess with Sybil.  This may be good enough though.
 *
 * Also - the method generateDateBasedModData() should be called after midnight GMT 
 * once per day to generate the correct routing keys!
 *
 * @since 0.9.16 moved from net.i2p.data.RoutingKeyGenerator..
 *
 */
public class RouterKeyGenerator extends RoutingKeyGenerator {
    private final Log _log;
    private final I2PAppContext _context;

    public RouterKeyGenerator(I2PAppContext context) {
        _log = context.logManager().getLog(RoutingKeyGenerator.class);
        _context = context;
        // make sure GMT is set, azi2phelper Vuze plugin is disabling static JVM TZ setting in Router.java
        _fmt.setCalendar(_cal);
        // ensure non-null mod data
        generateDateBasedModData();
    }
    
    private volatile byte _currentModData[];
    private volatile byte _nextModData[];
    private volatile long _nextMidnight;
    private volatile long _lastChanged;

    private final Calendar _cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
    private static final String FORMAT = "yyyyMMdd";
    private static final int LENGTH = FORMAT.length();
    private final SimpleDateFormat _fmt = new SimpleDateFormat(FORMAT, Locale.US);

    /**
     *  The current (today's) mod data.
     *  Warning - not a copy, do not corrupt.
     *
     *  @return non-null, 8 bytes
     */
    public byte[] getModData() {
        return _currentModData;
    }

    /**
     *  Tomorrow's mod data.
     *  Warning - not a copy, do not corrupt.
     *  For debugging use only.
     *
     *  @return non-null, 8 bytes
     *  @since 0.9.10
     */
    public byte[] getNextModData() {
        return _nextModData;
    }

    public long getLastChanged() {
        return _lastChanged;
    }

    /**
     *  How long until midnight (ms)
     *
     *  @return could be slightly negative
     *  @since 0.9.10 moved from UpdateRoutingKeyModifierJob
     */
    public long getTimeTillMidnight() {
        return _nextMidnight - _context.clock().now();
    }

    /**
     *  Set _cal to midnight for the time given.
     *  Caller must synch.
     *  @since 0.9.10
     */
    private void setCalToPreviousMidnight(long now) {
            _cal.setTime(new Date(now));
            _cal.set(Calendar.YEAR, _cal.get(Calendar.YEAR));               // gcj <= 4.0 workaround
            _cal.set(Calendar.DAY_OF_YEAR, _cal.get(Calendar.DAY_OF_YEAR)); // gcj <= 4.0 workaround
            _cal.set(Calendar.HOUR_OF_DAY, 0);
            _cal.set(Calendar.MINUTE, 0);
            _cal.set(Calendar.SECOND, 0);
            _cal.set(Calendar.MILLISECOND, 0);
    }

    /**
     *  Generate mod data from _cal.
     *  Caller must synch.
     *  @since 0.9.10
     */
    private byte[] generateModDataFromCal() {
        Date today = _cal.getTime();
        
        String modVal = _fmt.format(today);
        if (modVal.length() != LENGTH)
            throw new IllegalStateException();
        byte[] mod = DataHelper.getASCII(modVal);
        return mod;
    }

    /**
     * Update the current modifier data with some bytes derived from the current
     * date (yyyyMMdd in GMT)
     *
     * @return true if changed
     */
    public synchronized boolean generateDateBasedModData() {
        long now = _context.clock().now();
        setCalToPreviousMidnight(now);
        byte[] mod = generateModDataFromCal();
        boolean changed = !Arrays.equals(_currentModData, mod);
        if (changed) {
            // add a day and store next midnight and mod data for convenience
            _cal.add(Calendar.DATE, 1);
            _nextMidnight = _cal.getTime().getTime();
            byte[] next = generateModDataFromCal();
            _currentModData = mod;
            _nextModData = next;
            // ensure version is bumped
            if (_lastChanged == now)
                now++;
            _lastChanged = now;
            if (_log.shouldLog(Log.INFO))
                _log.info("Routing modifier generated: " + HexDump.dump(mod));
        }
        return changed;
    }
    
    /**
     * Generate a modified (yet consistent) hash from the origKey by generating the
     * SHA256 of the targetKey with the current modData appended to it
     *
     * This makes Sybil's job a lot harder, as she needs to essentially take over the
     * whole keyspace.
     *
     * @throws IllegalArgumentException if origKey is null
     */
    public Hash getRoutingKey(Hash origKey) {
        return getKey(origKey, _currentModData);
    }
    
    /**
     * Get the routing key using tomorrow's modData, not today's
     *
     * @since 0.9.10
     */
    public Hash getNextRoutingKey(Hash origKey) {
        return getKey(origKey, _nextModData);
    }
    
    /**
     * Get the routing key for the specified date, not today's
     *
     * @param time Java time
     * @since 0.9.28
     */
    public Hash getRoutingKey(Hash origKey, long time) {
        String modVal;
        synchronized(this) {
            modVal = _fmt.format(time);
        }
        if (modVal.length() != LENGTH)
            throw new IllegalStateException();
        byte[] mod = DataHelper.getASCII(modVal);
        return getKey(origKey, mod);
    }
    
    /**
     * Generate a modified (yet consistent) hash from the origKey by generating the
     * SHA256 of the targetKey with the specified modData appended to it
     *
     * @throws IllegalArgumentException if origKey is null
     */
    private static Hash getKey(Hash origKey, byte[] modData) {
        if (origKey == null) throw new IllegalArgumentException("Original key is null");
        byte modVal[] = new byte[Hash.HASH_LENGTH + LENGTH];
        System.arraycopy(origKey.getData(), 0, modVal, 0, Hash.HASH_LENGTH);
        System.arraycopy(modData, 0, modVal, Hash.HASH_LENGTH, LENGTH);
        return SHA256Generator.getInstance().calculateHash(modVal);
    }

    /**
     * @since 0.9.29
     */
    public static void main(String args[]) {
        if (args.length <= 0) {
            System.err.println("Usage: RouterKeyGenerator [-days] [+days] hash|hostname|destination...");
            System.exit(1);
        }
        long now = System.currentTimeMillis();
        int st = 0;
        if (args.length > 1 && (args[0].startsWith("+") || args[0].startsWith("-"))) {
            now += Integer.parseInt(args[0]) * (24*60*60*1000L);
            st++;
        }
        RouterKeyGenerator rkg = new RouterKeyGenerator(I2PAppContext.getGlobalContext());
        System.out.println("Date: " + rkg._fmt.format(now) + '\n' +
                           "Hash                                         Routing Key\n" +
                           "----                                         -----------");
        for (int i = st; i < args.length; i++) {
            String s = args[i];
            String sp = " ";
            if (s.length() < 44)
                sp = "                                            ".substring(0, 45 - s.length());
            Hash h = ConvertToHash.getHash(s);
            if (h == null) {
                System.out.println(s + sp + "Bad hash");
                continue;
            }
            Hash rkey = rkg.getRoutingKey(h, now);
            System.out.println(s + sp + rkey.toBase64());
        }
    }
}
