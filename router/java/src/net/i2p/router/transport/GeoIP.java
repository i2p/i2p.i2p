package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.io.InputStreamReader;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Manage geoip lookup in a file with the Tor geoip format.
 *
 * The lookup is expensive, so a lookup is queued with add().
 * The actual lookup of multiple IPs is fired with lookup().
 * To get a country for an IP, use get() which returns a lower-case,
 * generally two-letter country code or null.
 *
 * Everything here uses longs, since Java is signed-only, the file is
 * sorted by unsigned, and we don't store the table in memory
 * (unlike in Blocklist.java, where it's in-memory so we want to be
 * space-efficient)
 *
 * @author zzz
 */
class GeoIP {
    private final Log _log;
    // change to test with main()
    //private final I2PAppContext _context;
    private final RouterContext _context;
    private final Map<String, String> _codeToName;
    /** code to itself to prevent String proliferation */
    private final Map<String, String> _codeCache;
    private final Map<Long, String> _IPToCountry;
    private final Set<Long> _pendingSearch;
    private final Set<Long> _notFound;
    private final AtomicBoolean _lock;
    private int _lookupRunCount;
    
    //public GeoIP(I2PAppContext context) {
    public GeoIP(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GeoIP.class);
        _codeToName = new ConcurrentHashMap(256);
        _codeCache = new ConcurrentHashMap(256);
        _IPToCountry = new ConcurrentHashMap();
        _pendingSearch = new ConcurrentHashSet();
        _notFound = new ConcurrentHashSet();
        _lock = new AtomicBoolean();
        readCountryFile();
    }
    
    static final String PROP_GEOIP_ENABLED = "routerconsole.geoip.enable";
    static final String GEOIP_DIR_DEFAULT = "geoip";
    static final String GEOIP_FILE_DEFAULT = "geoip.txt";
    static final String COUNTRY_FILE_DEFAULT = "countries.txt";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";

    /**
     * Fire off a thread to lookup all pending IPs.
     * There is no indication of completion.
     * Results will be added to the table and available via get() after completion.
     */
/******
    public void lookup() {
        if (! Boolean.valueOf(_context.getProperty(PROP_GEOIP_ENABLED, "true")).booleanValue()) {
            _pendingSearch.clear();
            return;
        }
        Thread t = new Thread(new LookupJob());
        t.start();
    }
******/

    /**
     * Blocking lookup of all pending IPs.
     * Results will be added to the table and available via get() after completion.
     */
    public void blockingLookup() {
        if (! Boolean.valueOf(_context.getProperty(PROP_GEOIP_ENABLED, "true")).booleanValue()) {
            _pendingSearch.clear();
            return;
        }
        int pri = Thread.currentThread().getPriority();
        if (pri > Thread.MIN_PRIORITY)
            Thread.currentThread().setPriority(pri - 1);
        try {
            LookupJob j = new LookupJob();
            j.run();
            updateOurCountry();
        } finally {
            if (pri > Thread.MIN_PRIORITY)
                Thread.currentThread().setPriority(pri);
        }
    }

    private class LookupJob implements Runnable {
        private static final int CLEAR = 8;

        public void run() {
            if (_lock.getAndSet(true))
                return;
            try {
                // clear the negative cache every few runs, to prevent it from getting too big
                if (((++_lookupRunCount) % CLEAR) == 0)
                    _notFound.clear();
                Long[] search = _pendingSearch.toArray(new Long[_pendingSearch.size()]);
                if (search.length <= 0)
                    return;
                _pendingSearch.clear();
                Arrays.sort(search);
                String[] countries = readGeoIPFile(search);
    
                for (int i = 0; i < countries.length; i++) {
                    if (countries[i] != null)
                        _IPToCountry.put(search[i], countries[i]);
                    else
                        _notFound.add(search[i]);
                }
            } finally {
                _lock.set(false);
            }
        }
    }

   /**
    * Read in and parse the country file.
    * The file need not be sorted.
    *
    * Acceptable formats:
    *   #comment (# must be in column 1)
    *   code,full name
    *
    * Example:
    *   US,UNITED STATES
    *
    * To create:
    * wget http://ip-to-country.webhosting.info/downloads/ip-to-country.csv.zip
    * unzip ip-to-country.csv.zip
    * cut -d, -f3,5 < ip-to-country.csv|sed 's/"//g' | sort | uniq > countries.txt
    *
    */
    private void readCountryFile() {
        File GeoFile = new File(_context.getBaseDir(), GEOIP_DIR_DEFAULT);
        GeoFile = new File(GeoFile, COUNTRY_FILE_DEFAULT);
        if (!GeoFile.exists()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Country file not found: " + GeoFile.getAbsolutePath());
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(GeoFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#') {
                        continue;
                    }
                    String[] s = line.split(",");
                    String lc = s[0].toLowerCase(Locale.US);
                    _codeToName.put(lc, s[1]);
                    _codeCache.put(lc, lc);
                } catch (IndexOutOfBoundsException ioobe) {
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the Country File", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

   /**
    * Read in and parse the geoip file.
    * The geoip file must be sorted, and may not contain overlapping entries.
    *
    * Acceptable formats (IPV4 only):
    *   #comment (# must be in column 1)
    *   integer IP,integer IP, country code
    *
    * Example:
    *   121195296,121195327,IT
    *
    * This is identical to the Tor geoip file, which can be found in
    * src/config/geoip in their distribution, or /usr/local/lib/share/tor/geoip
    * in their installation.
    * Thanks to Tor for finding a source for the data, and the format script.
    *
    * To create:
    * wget http://ip-to-country.webhosting.info/downloads/ip-to-country.csv.zip
    * unzip ip-to-country.csv.zip
    * cut -d, -f0-3 < ip-to-country.csv|sed 's/"//g' > geoip.txt
    *
    * @param search a sorted array of IPs to search
    * @return an array of country codes, same order as the search param,
    *         or a zero-length array on failure
    *
    */
    private String[] readGeoIPFile(Long[] search) {
        File GeoFile = new File(_context.getBaseDir(), GEOIP_DIR_DEFAULT);
        GeoFile = new File(GeoFile, GEOIP_FILE_DEFAULT);
        if (!GeoFile.exists()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("GeoIP file not found: " + GeoFile.getAbsolutePath());
            return new String[0];
        }
        String[] rv = new String[search.length];
        int idx = 0;
        long start = _context.clock().now();
        FileInputStream in = null;
        try {
            in = new FileInputStream(GeoFile);
            String buf = null;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
            while ((buf = br.readLine()) != null && idx < search.length) {
                try {
                    if (buf.charAt(0) == '#') {
                        continue;
                    }
                    String[] s = buf.split(",");
                    long ip1 = Long.parseLong(s[0]);
                    long ip2 = Long.parseLong(s[1]);
                    while (idx < search.length && search[idx].longValue() < ip1) {
                        idx++;
                    }
                    while (idx < search.length && search[idx].longValue() >= ip1 && search[idx].longValue() <= ip2) {
                        String lc = s[2].toLowerCase(Locale.US);
                        // replace the new string with the identical one from the cache
                        String cached = _codeCache.get(lc);
                        if (cached == null)
                            cached = lc;
                        rv[idx++] = cached;
                    }
                } catch (IndexOutOfBoundsException ioobe) {
                } catch (NumberFormatException nfe) {
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the GeoFile", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }

        if (_log.shouldLog(Log.INFO)) {
            _log.info("GeoIP processing finished, time: " + (_context.clock().now() - start));
        }
        return rv;
    }

    /**
     *  Put our country code in the config, where others (such as Timestamper) can get it,
     *  and it will be there next time at startup.
     */
    private void updateOurCountry() {
        /**** comment out to test with main() */
        String oldCountry = _context.router().getConfigSetting(PROP_IP_COUNTRY);
        Hash ourHash = _context.routerHash();
        // we should always have a RouterInfo by now, but we had one report of an NPE here
        if (ourHash == null)
            return;
        String country = _context.commSystem().getCountry(ourHash);
        if (country != null && !country.equals(oldCountry)) {
            _context.router().setConfigSetting(PROP_IP_COUNTRY, country);
            _context.router().saveConfig();
        }
        /****/
    }

    /**
     * Add to the list needing lookup
     */
    public void add(String ip) {
        InetAddress pi;
        try {
            pi = InetAddress.getByName(ip);
        } catch (UnknownHostException uhe) {
            return;
        }
        if (pi == null) return;
        byte[] pib = pi.getAddress();
        add(pib);
    }

    public void add(byte ip[]) {
        if (ip.length != 4)
            return;
        add(toLong(ip));
    }

    private void add(long ip) {
        Long li = Long.valueOf(ip);
        if (!(_IPToCountry.containsKey(li) || _notFound.contains(li)))
            _pendingSearch.add(li);
    }

    /**
     * Get the country for an IP from the cache.
     * @return lower-case code, generally two letters, or null.
     */
    public String get(String ip) {
        InetAddress pi;
        try {
            pi = InetAddress.getByName(ip);
        } catch (UnknownHostException uhe) {
            return null;
        }
        if (pi == null) return null;
        byte[] pib = pi.getAddress();
        return get(pib);
    }

    /**
     * Get the country for an IP from the cache.
     * @return lower-case code, generally two letters, or null.
     */
    public String get(byte ip[]) {
        if (ip.length != 4)
            return null;
        return get(toLong(ip));
    }

    private String get(long ip) {
        return _IPToCountry.get(Long.valueOf(ip));
    }

    private static long toLong(byte ip[]) {
        int rv = 0;
        for (int i = 0; i < 4; i++)
            rv |= (ip[i] & 0xff) << ((3-i)*8);
        return ((long) rv) & 0xffffffffl;
    }

    /**
     * Get the country for a country code
     * @param code two-letter lower case code
     * @return untranslated name or null
     */
    public String fullName(String code) {
        return _codeToName.get(code);
    }

/*** doesn't work since switched to RouterContext above
    public static void main(String args[]) {
        GeoIP g = new GeoIP(new I2PAppContext());
        String tests[] = {"0.0.0.0", "0.0.0.1", "0.0.0.2", "0.0.0.255", "1.0.0.0",
                                        "94.3.3.3", "77.1.2.3", "127.0.0.0", "127.127.127.127", "128.0.0.0",
                                        "89.8.9.3", "72.5.6.8", "217.4.9.7", "175.107.027.107", "135.6.5.2",
                                        "129.1.2.3", "255.255.255.254", "255.255.255.255"};
        for (int i = 0; i < tests.length; i++)
            g.add(tests[i]);
        long start = System.currentTimeMillis();
        g.blockingLookup();
        System.out.println("Lookup took " + (System.currentTimeMillis() - start));
        for (int i = 0; i < tests.length; i++)
            System.out.println(tests[i] + " : " + g.get(tests[i]));

    }
***/
}
