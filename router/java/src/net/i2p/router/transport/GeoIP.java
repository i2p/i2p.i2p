package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.maxmind.geoip.InvalidDatabaseException;
import com.maxmind.geoip.LookupService;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

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
public class GeoIP {
    private final Log _log;
    private final I2PAppContext _context;
    private final Map<String, String> _codeToName;
    /** code to itself to prevent String proliferation */
    private final Map<String, String> _codeCache;

    // In the following structures, an IPv4 IP is stored as a non-negative long, 0 to 2**32 - 1,
    // and the first 8 bytes of an IPv6 IP are stored as a signed long.
    private final Map<Long, String> _IPToCountry;
    private final Set<Long> _pendingSearch;
    private final Set<Long> _pendingIPv6Search;
    private final Set<Long> _notFound;
    private final AtomicBoolean _lock;
    private int _lookupRunCount;
    
    static final String PROP_GEOIP_ENABLED = "routerconsole.geoip.enable";
    public static final String PROP_GEOIP_DIR = "geoip.dir";
    public static final String GEOIP_DIR_DEFAULT = "geoip";
    static final String GEOIP_FILE_DEFAULT = "geoip.txt";
    static final String COUNTRY_FILE_DEFAULT = "countries.txt";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";
    public static final String PROP_DEBIAN_GEOIP = "geoip.dat";
    public static final String PROP_DEBIAN_GEOIPV6 = "geoip.v6.dat";
    private static final String DEBIAN_GEOIP_FILE = "/usr/share/GeoIP/GeoIP.dat";
    private static final String DEBIAN_GEOIPV6_FILE = "/usr/share/GeoIP/GeoIPv6.dat";
    private static final boolean ENABLE_DEBIAN = !(SystemVersion.isWindows() || SystemVersion.isAndroid());
    /** maxmind API */
    private static final String UNKNOWN_COUNTRY_CODE = "--";

    /**
     *  @param context RouterContext in production, I2PAppContext for testing only
     */
    public GeoIP(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(GeoIP.class);
        _codeToName = new ConcurrentHashMap<String, String>(512);
        _codeCache = new ConcurrentHashMap<String, String>(512);
        _IPToCountry = new ConcurrentHashMap<Long, String>();
        _pendingSearch = new ConcurrentHashSet<Long>();
        _pendingIPv6Search = new ConcurrentHashSet<Long>();
        _notFound = new ConcurrentHashSet<Long>();
        _lock = new AtomicBoolean();
        readCountryFile();
    }

    /**
     *  @since 0.9.3
     */
    public void shutdown() {
        _codeToName.clear();
        _codeCache.clear();
        _IPToCountry.clear();
        _pendingSearch.clear();
        _pendingIPv6Search.clear();
        _notFound.clear();
    }

    /**
     * Fire off a thread to lookup all pending IPs.
     * There is no indication of completion.
     * Results will be added to the table and available via get() after completion.
     */
/******
    public void lookup() {
        if (! _context.getBooleanPropertyDefaultTrue(PROP_GEOIP_ENABLED)) {
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
        if (! _context.getBooleanPropertyDefaultTrue(PROP_GEOIP_ENABLED)) {
            _pendingSearch.clear();
            _pendingIPv6Search.clear();
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
                // IPv4
                Long[] search = _pendingSearch.toArray(new Long[_pendingSearch.size()]);
                _pendingSearch.clear();
                if (search.length > 0) {
                    Arrays.sort(search);
                    File f = new File(_context.getProperty(PROP_DEBIAN_GEOIP, DEBIAN_GEOIP_FILE));
                    if (ENABLE_DEBIAN && f.exists()) {
                        // Maxmind database
                        LookupService ls = null;
                        try {
                            ls = new LookupService(f, LookupService.GEOIP_STANDARD);
                            for (int i = 0; i < search.length; i++) {
                                long ip = search[i].longValue();
                                // returns upper case or "--"
                                String uc = ls.getCountry(ip).getCode();
                                if (!uc.equals(UNKNOWN_COUNTRY_CODE)) {
                                    String cached = _codeCache.get(uc.toLowerCase(Locale.US));
                                    _IPToCountry.put(search[i], cached);
                                } else {
                                    _notFound.add(search[i]);
                                }
                            }
                        } catch (IOException ioe) {
                            _log.error("GeoIP failure", ioe);
                        } catch (InvalidDatabaseException ide) {
                            _log.error("GeoIP failure", ide);
                        } finally {
                            if (ls != null) ls.close();
                        }
                    } else {
                        // Tor-style database
                        String[] countries = readGeoIPFile(search);
                        for (int i = 0; i < countries.length; i++) {
                            if (countries[i] != null)
                                _IPToCountry.put(search[i], countries[i]);
                            else
                                _notFound.add(search[i]);
                        }
                    }
                }
                // IPv6
                search = _pendingIPv6Search.toArray(new Long[_pendingIPv6Search.size()]);
                _pendingIPv6Search.clear();
                if (search.length > 0) {
                    Arrays.sort(search);
                    File f = new File(_context.getProperty(PROP_DEBIAN_GEOIPV6, DEBIAN_GEOIPV6_FILE));
                    if (ENABLE_DEBIAN && f.exists()) {
                        // Maxmind database
                        LookupService ls = null;
                        try {
                            ls = new LookupService(f, LookupService.GEOIP_STANDARD);
                            for (int i = 0; i < search.length; i++) {
                                long ip = search[i].longValue();
                                String ipv6 = toV6(ip);
                                // returns upper case or "--"
                                String uc = ls.getCountryV6(ipv6).getCode();
                                if (!uc.equals(UNKNOWN_COUNTRY_CODE)) {
                                    String lc = uc.toLowerCase(Locale.US);
                                    String cached = _codeCache.get(lc);
                                    if (cached == null)
                                        cached = lc;
                                    _IPToCountry.put(search[i], cached);
                                } else {
                                    _notFound.add(search[i]);
                                }
                            }
                        } catch (IOException ioe) {
                            _log.error("GeoIP failure", ioe);
                        } catch (InvalidDatabaseException ide) {
                            _log.error("GeoIP failure", ide);
                        } finally {
                            if (ls != null) ls.close();
                        }
                    } else {
                        // Tor-style database
                        String[] countries = GeoIPv6.readGeoIPFile(_context, search, _codeCache);
                        for (int i = 0; i < countries.length; i++) {
                            if (countries[i] != null)
                                _IPToCountry.put(search[i], countries[i]);
                            else
                                _notFound.add(search[i]);
                        }
                    }
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
        String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute())
            geoFile = new File(_context.getBaseDir(), geoDir);
        geoFile = new File(geoFile, COUNTRY_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Country file not found: " + geoFile.getAbsolutePath());
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(geoFile), "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#') {
                        continue;
                    }
                    String[] s = DataHelper.split(line, ",");
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
            if (br != null) try { br.close(); } catch (IOException ioe) {}
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
        String geoDir = _context.getProperty(PROP_GEOIP_DIR, GEOIP_DIR_DEFAULT);
        File geoFile = new File(geoDir);
        if (!geoFile.isAbsolute())
            geoFile = new File(_context.getBaseDir(), geoDir);
        geoFile = new File(geoFile, GEOIP_FILE_DEFAULT);
        if (!geoFile.exists()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("GeoIP file not found: " + geoFile.getAbsolutePath());
            return new String[0];
        }
        String[] rv = new String[search.length];
        int idx = 0;
        long start = _context.clock().now();
        BufferedReader br = null;
        try {
            String buf = null;
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(geoFile), "ISO-8859-1"));
            while ((buf = br.readLine()) != null && idx < search.length) {
                try {
                    if (buf.charAt(0) == '#') {
                        continue;
                    }
                    String[] s = DataHelper.split(buf, ",");
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
                _log.error("Error reading the geoFile", ioe);
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }

        if (_log.shouldLog(Log.INFO)) {
            _log.info("GeoIP processing finished, time: " + (_context.clock().now() - start));
        }
        return rv;
    }

    /**
     *  Put our country code in the config, where others (such as Timestamper) can get it,
     *  and it will be there next time at startup.
     *
     *  Does nothing in I2PAppContext
     */
    private void updateOurCountry() {
        if (! (_context instanceof RouterContext))
            return;
        RouterContext ctx = (RouterContext) _context;
        String oldCountry = ctx.router().getConfigSetting(PROP_IP_COUNTRY);
        Hash ourHash = ctx.routerHash();
        // we should always have a RouterInfo by now, but we had one report of an NPE here
        if (ourHash == null)
            return;
        String country = ctx.commSystem().getCountry(ourHash);
        if (country != null && !country.equals(oldCountry)) {
            ctx.router().saveConfig(PROP_IP_COUNTRY, country);
            if (ctx.commSystem().isInBadCountry() && ctx.getProperty(Router.PROP_HIDDEN_HIDDEN) == null) {
                String name = fullName(country);
                if (name == null)
                    name = country;
                _log.logAlways(Log.WARN, "Setting hidden mode to protect you in " + name +
                                         ", you may override on the network configuration page");
                ctx.router().rebuildRouterInfo();
            }
        }
        /****/
    }

    /**
     * Add to the list needing lookup
     * @param ip IPv4 or IPv6
     */
    public void add(String ip) {
        byte[] pib = Addresses.getIP(ip);
        if (pib == null) return;
        add(pib);
    }

    /**
     * Add to the list needing lookup
     * @param ip IPv4 or IPv6
     */
    public void add(byte ip[]) {
        add(toLong(ip));
    }

    /** see above for ip-to-long mapping */
    private void add(long ip) {
        Long li = Long.valueOf(ip);
        if (!(_IPToCountry.containsKey(li) || _notFound.contains(li))) {
            if (ip >= 0 && ip < (1L << 32))
                _pendingSearch.add(li);
            else
                _pendingIPv6Search.add(li);
        }
    }

    /**
     * Get the country for an IP from the cache.
     * @param ip IPv4 or IPv6
     * @return lower-case code, generally two letters, or null.
     */
    public String get(String ip) {
        byte[] pib = Addresses.getIP(ip);
        if (pib == null) return null;
        return get(pib);
    }

    /**
     * Get the country for an IP from the cache.
     * @param ip IPv4 or IPv6
     * @return lower-case code, generally two letters, or null.
     */
    public String get(byte ip[]) {
        return get(toLong(ip));
    }

    /** see above for ip-to-long mapping */
    private String get(long ip) {
        return _IPToCountry.get(Long.valueOf(ip));
    }

    /** see above for ip-to-long mapping */
    private static long toLong(byte ip[]) {
        long rv = 0;
        if (ip.length == 16) {
            for (int i = 0; i < 8; i++)
                rv |= (ip[i] & 0xffL) << ((7-i)*8);
            return rv;
        } else {
            for (int i = 0; i < 4; i++)
                rv |= (ip[i] & 0xff) << ((3-i)*8);
            return rv & 0xffffffffl;
        }
    }

    /**
     * @return e.g. aabb:ccdd:eeff:1122::
     * @since 0.9.26 for maxmind
     */
    private static String toV6(long ip) {
        StringBuilder buf = new StringBuilder(21);
        for (int i = 0; i < 4; i++) {
            buf.append(Long.toHexString((ip >> ((3-i)*16)) & 0xffff));
            buf.append(':');
        }
        buf.append(':');
        return buf.toString();
    }

    /**
     * Get the country for a country code
     * @param code two-letter lower case code
     * @return untranslated name or null
     */
    public String fullName(String code) {
        return _codeToName.get(code);
    }

/***
    public static void main(String args[]) {
        GeoIP g = new GeoIP(new Router().getContext());
        String tests[] = {"0.0.0.0", "0.0.0.1", "0.0.0.2", "0.0.0.255", "1.0.0.0",
                                        "94.3.3.3", "77.1.2.3", "127.0.0.0", "127.127.127.127", "128.0.0.0",
                                        "89.8.9.3", "72.5.6.8", "217.4.9.7", "175.107.027.107", "135.6.5.2",
                                        "129.1.2.3", "255.255.255.254", "255.255.255.255",
                          "::", "1", "2000:1:2:3::", "2001:200:1:2:3:4:5:6", "2001:208:7:8:9::",
                          "2c0f:fff0:1234:5678:90ab:cdef:0:0", "2c0f:fff1:0::"
                          };
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
