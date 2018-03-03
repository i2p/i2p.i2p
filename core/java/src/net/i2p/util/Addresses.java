package net.i2p.util;

/*
 * public domain
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.http.conn.util.InetAddressUtils;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;


/**
 * Methods to get the local addresses, and other IP utilities
 *
 * @since 0.8.3 moved to core from router/transport
 * @author zzz
 */
public abstract class Addresses {
    
    private static final File IF_INET6_FILE = new File("/proc/net/if_inet6");
    private static final long INET6_CACHE_EXPIRE = 10*60*1000;
    private static final boolean INET6_CACHE_ENABLED = !SystemVersion.isMac() && !SystemVersion.isWindows() &&
                                                    !SystemVersion.isAndroid() && IF_INET6_FILE.exists();
    private static final int FLAG_PERMANENT = 0x80;
    private static final int FLAG_DEPRECATED = 0x20;
    private static final int FLAG_TEMPORARY = 0x01;
    private static long _ifCacheTime;
    private static final Map<Inet6Address, Inet6Addr> _ifCache = INET6_CACHE_ENABLED ? new HashMap<Inet6Address, Inet6Addr>(8) : null;
    /** 12 char hex lower case */
    private static final Set<String> _macCache = new HashSet<String>();

    /**
     *  Do we have any non-loop, non-wildcard IPv4 address at all?
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     *  @since 0.9.4
     */
    public static boolean isConnected() {
        // not as good as using a Java DBus implementation to talk to NetworkManager...
        return !getAddresses(true, false, false).isEmpty();
    }

    /**
     *  Do we have any non-loop, non-wildcard IPv6 address at all?
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     *  @since 0.9.29
     */
    public static boolean isConnectedIPv6() {
        // not as good as using a Java DBus implementation to talk to NetworkManager...
        for (String ip : getAddresses(false, true)) {
            if (ip.contains(":"))
                return true;
        }
        return false;
    }

    /**
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     * @return the first non-local address IPv4 address it finds, or null
     */
    public static String getAnyAddress() {
        SortedSet<String> a = getAddresses();
        if (!a.isEmpty())
            return a.first();
        return null;
    }

    /**
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     *  @return a sorted set of all addresses, excluding
     *  IPv6, local, broadcast, multicast, etc.
     */
    public static SortedSet<String> getAddresses() {
        return getAddresses(false, false);
    }

    /**
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     *  @return a sorted set of all addresses, excluding
     *  only link local and multicast
     *  @since 0.8.3
     */
    public static SortedSet<String> getAllAddresses() {
        return getAddresses(true, true);
    }

    /**
     *  Warning: When includeLocal is false,
     *  all returned addresses should be routable, but they are not necessarily
     *  appropriate for external use. For example, Teredo and 6to4 addresses
     *  are included with IPv6 results. Additional validation is recommended.
     *  See e.g. TransportUtil.isPubliclyRoutable().
     *
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     *  @return a sorted set of all addresses including wildcard
     *  @param includeLocal whether to include local
     *  @param includeIPv6 whether to include IPV6
     *  @return a Set of all addresses
     *  @since 0.8.3
     */
    public static SortedSet<String> getAddresses(boolean includeLocal, boolean includeIPv6) {
        return getAddresses(includeLocal, includeLocal, includeIPv6);
    }

    /**
     *  Warning: When includeSiteLocal and includeLoopbackAndWildcard are false,
     *  all returned addresses should be routable, but they are not necessarily
     *  appropriate for external use. For example, Teredo and 6to4 addresses
     *  are included with IPv6 results. Additional validation is recommended.
     *  See e.g. TransportUtil.isPubliclyRoutable().
     *
     *  Warning, very slow on Windows, appx. 200ms + 50ms/interface
     *
     *  @return a sorted set of all addresses
     *  @param includeSiteLocal whether to include private like 192.168.x.x
     *  @param includeLoopbackAndWildcard whether to include 127.x.x.x and 0.0.0.0
     *  @param includeIPv6 whether to include IPV6
     *  @return a Set of all addresses
     *  @since 0.9.4
     */
    public static SortedSet<String> getAddresses(boolean includeSiteLocal,
                                                 boolean includeLoopbackAndWildcard,
                                                 boolean includeIPv6) {
        boolean haveIPv4 = false;
        boolean haveIPv6 = false;
        SortedSet<String> rv = new TreeSet<String>();
        final boolean omitDeprecated = INET6_CACHE_ENABLED && !includeSiteLocal && includeIPv6;
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            InetAddress[] allMyIps = InetAddress.getAllByName(localhost.getCanonicalHostName());
            if (allMyIps != null) {
                for (int i = 0; i < allMyIps.length; i++) {
                    boolean isv4 = allMyIps[i] instanceof Inet4Address;
                    if (isv4)
                        haveIPv4 = true;
                    else
                        haveIPv6 = true;
                    if (omitDeprecated && !isv4) {
                        if (isDeprecated((Inet6Address) allMyIps[i]))
                            continue;
                    }
                    if (shouldInclude(allMyIps[i], includeSiteLocal,
                                      includeLoopbackAndWildcard, includeIPv6)) {
                        rv.add(stripScope(allMyIps[i].getHostAddress()));
                    }
                }
            }
        } catch (UnknownHostException e) {}

        try {
            Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces();
            if (ifcs != null) {
                Set<String> newMacs = new HashSet<String>(8);
                while (ifcs.hasMoreElements()) {
                    NetworkInterface ifc = ifcs.nextElement();
                    if (!ifc.isUp()) {
                        // This is super-important on Windows which has 40+ down interfaces
                        // and will also deliver IP addresses for down interfaces,
                        // for example recently disconnected wifi.
                        //System.out.println("Skipping, down: " + ifc.getDisplayName());
                        continue;
                    }
                    try {
                        byte[] mac = ifc.getHardwareAddress();
                        if (mac != null && mac.length == 6) {
                            newMacs.add(DataHelper.toString(mac));
                        }
                    } catch (SocketException ioe) {}
                    for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                        InetAddress addr = addrs.nextElement();
                        boolean isv4 = addr instanceof Inet4Address;
                        if (isv4)
                            haveIPv4 = true;
                        else
                            haveIPv6 = true;
                        if (omitDeprecated && !isv4) {
                            if (isDeprecated((Inet6Address) addr))
                                continue;
                        }
                        if (shouldInclude(addr, includeSiteLocal,
                                          includeLoopbackAndWildcard, includeIPv6)) {
                            rv.add(stripScope(addr.getHostAddress()));
                        }
                    }
                }
                if (!newMacs.isEmpty()) {
                    synchronized(_macCache) {
                        _macCache.clear();
                        _macCache.addAll(newMacs);
                    }
                }
            }
        } catch (SocketException e) {
        } catch (java.lang.Error e) {
            // Windows, possibly when IPv6 only...
            // https://bugs.openjdk.java.net/browse/JDK-8046500
            // java.lang.Error: IP Helper Library GetIfTable function failed
            //   at java.net.NetworkInterface.getAll(Native Method)
            //   at java.net.NetworkInterface.getNetworkInterfaces(Unknown Source)
            //   at net.i2p.util.Addresses.getAddresses ...
        }

        if (includeLoopbackAndWildcard) {
            if (haveIPv4)
                rv.add("0.0.0.0");
            if (includeIPv6 && haveIPv6)
                rv.add("0:0:0:0:0:0:0:0");  // we could do "::" but all the other ones are probably in long form
        }
        return rv;
    }

    /**
     *  Strip the trailing "%nn" from Inet6Address.getHostAddress()
     *  @since IPv6
     */
    private static String stripScope(String ip) {
        int pct = ip.indexOf('%');
        if (pct > 0)
            ip = ip.substring(0, pct);
        return ip;
    }

    private static boolean shouldInclude(InetAddress ia, boolean includeSiteLocal,
                                         boolean includeLoopbackAndWildcard, boolean includeIPv6) {
        return
            (!ia.isLinkLocalAddress()) &&     // 169.254.x.x
            (!ia.isMulticastAddress()) &&
            (includeLoopbackAndWildcard ||
             ((!ia.isAnyLocalAddress()) &&
              (!ia.isLoopbackAddress()))) &&
            (includeSiteLocal ||
             ((!ia.isSiteLocalAddress()) &&
              // disallow fc00::/8 and fd00::/8 (Unique local addresses RFC 4193)
              // not recognized as local by InetAddress
              (ia.getAddress().length != 16 || (ia.getAddress()[0] & 0xfe) != 0xfc))) &&
            // Hamachi 5/8 allocated to RIPE (30 November 2010)
            // Removed from TransportImpl.isPubliclyRoutable()
            // Check moved to here, for now, but will eventually need to
            // remove it from here also.
            //(includeLocal ||
            //(!ia.getHostAddress().startsWith("5."))) &&
            (includeIPv6 ||
             (ia instanceof Inet4Address));
    }

    /**
     *  Convenience method to convert an IP address to a String
     *  without throwing an exception.
     *  @return "null" for null, and "bad IP length x" if length is invalid
     *  @since 0.8.12
     */
    public static String toString(byte[] addr) {
        if (addr == null)
            return "null";
        try {
            return InetAddress.getByAddress(addr).getHostAddress();
        } catch (UnknownHostException uhe) {
            return "bad IP length " + addr.length;
        }
    }

    /**
     *  Convenience method to convert an IP address and port to a String
     *  without throwing an exception.
     *  @return "ip:port"
     *  @since 0.8.12
     */
    public static String toString(byte[] addr, int port) {
        if (addr == null)
            return "null:" + port;
        try {
            String ip = InetAddress.getByAddress(addr).getHostAddress();
            if (addr.length != 16)
                return ip + ':' + port;
            return '[' + ip + "]:" + port;
        } catch (UnknownHostException uhe) {
            return "(bad IP length " + addr.length + "):" + port;
        }
    }
    
    /**
     *  Convenience method to convert and validate a port String
     *  without throwing an exception.
     *  Does not trim.
     *
     *  @return 1-65535 or 0 if invalid
     *  @since 0.9.3
     */
    public static int getPort(String port) {
        int rv = 0;
        if (port != null) {
            try {
                int iport = Integer.parseInt(port);
                if (iport > 0 && iport <= 65535)
                    rv = iport;
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }

    /**
     *  Textual IP to bytes, because InetAddress.getByName() is slow.
     *
     *  @since 0.9.3
     */
    private static final Map<String, byte[]> _IPAddress;
    private static final Map<String, Long> _negativeCache;
    private static final long NEG_CACHE_TIME = 60*60*1000L;

    static {
        int size;
        I2PAppContext ctx = I2PAppContext.getCurrentContext();
        if (ctx != null && ctx.isRouterContext()) {
            long maxMemory = SystemVersion.getMaxMemory();
            long min = 256;
            long max = 4096;
            // 2048 nominal for 128 MB
            size = (int) Math.max(min, Math.min(max, 1 + (maxMemory / (64*1024))));
        } else {
            size = 32;
        }
        _IPAddress = new LHMCache<String, byte[]>(size);
        _negativeCache = new LHMCache<String, Long>(128);
    }

    /**
     *  Caching version of InetAddress.getByName(host).getAddress(), which is slow.
     *  Caches numeric host names only.
     *  Will resolve but not cache DNS host names.
     *
     *  Unlike InetAddress.getByName(), we do NOT allow numeric IPs
     *  of the form d.d.d, d.d, or d, as these are almost certainly mistakes.
     *
     *  @param host DNS or IPv4 or IPv6 host name; if null returns null
     *  @return IP or null
     *  @since 0.9.3
     */
    public static byte[] getIP(String host) {
        if (host == null)
            return null;
        byte[] rv;
        synchronized (_IPAddress) {
            rv = _IPAddress.get(host);
        }
        if (rv == null) {
            synchronized(_negativeCache) {
                Long when = _negativeCache.get(host);
                if (when != null) {
                    if (when.longValue() > System.currentTimeMillis() - NEG_CACHE_TIME)
                        return null;
                    _negativeCache.remove(host);
                }
            }
            //I2PAppContext.getGlobalContext().logManager().getLog(Addresses.class).error("lookup of " + host, new Exception("I did it"));
            try {
                rv = InetAddress.getByName(host).getAddress();
                if (isIPAddress(host)) {
                    synchronized (_IPAddress) {
                        _IPAddress.put(host, rv);
                    }
                }
                // else we do not cache hostnames here, we rely on the JVM
            } catch (UnknownHostException uhe) {
                synchronized(_negativeCache) {
                    _negativeCache.put(host, Long.valueOf(System.currentTimeMillis()));
                }
            }
        }
        return rv;
    }

    /**
     *  Caching version of InetAddress.getByName(host).getAddress(), which is slow.
     *  Resolves literal IP addresses only, will not cause a DNS lookup.
     *  Will return null for host names.
     *
     *  Unlike InetAddress.getByName(), we do NOT allow numeric IPs
     *  of the form d.d.d, d.d, or d, as these are almost certainly mistakes.
     *
     *  @param host literal IPv4 or IPv6 address; if null returns null
     *  @return IP or null
     *  @since 0.9.32
     */
    public static byte[] getIPOnly(String host) {
        if (host == null)
            return null;
        byte[] rv;
        synchronized (_IPAddress) {
            rv = _IPAddress.get(host);
        }
        if (rv == null) {
            if (isIPAddress(host)) {
                try {
                    rv = InetAddress.getByName(host).getAddress();
                    synchronized (_IPAddress) {
                        _IPAddress.put(host, rv);
                    }
                } catch (UnknownHostException uhe) {}
            //} else {
            //    I2PAppContext.getGlobalContext().logManager().getLog(Addresses.class).warn("Not looking up " + host, new Exception("I did it"));
            }
        }
        return rv;
    }

    /**
     *  For literal IP addresses, this is the same as getIP(String).
     *  For host names, will return the preferred type (IPv4/v6) if available,
     *  else the other type if available.
     *  Will resolve but not cache DNS host names.
     *
     *  @param host DNS or IPv4 or IPv6 host name; if null returns null
     *  @return IP or null
     *  @since 0.9.28
     */
    public static byte[] getIP(String host, boolean preferIPv6) {
        if (host == null)
            return null;
        if (isIPAddress(host))
            return getIP(host);
        synchronized(_negativeCache) {
            Long when = _negativeCache.get(host);
            if (when != null) {
                if (when.longValue() > System.currentTimeMillis() - NEG_CACHE_TIME)
                    return null;
                _negativeCache.remove(host);
            }
        }
        byte[] rv = null;
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs == null || addrs.length == 0)
                return null;
            for (int i = 0; i < addrs.length; i++) {
                rv = addrs[i].getAddress();
                if (preferIPv6) {
                    if (rv.length == 16)
                        break;
                } else {
                    if (rv.length == 4)
                        break;
                }
            }
        } catch (UnknownHostException uhe) {
            synchronized(_negativeCache) {
                _negativeCache.put(host, Long.valueOf(System.currentTimeMillis()));
            }
        }
        return rv;
    }

    /**
     *  For literal IP addresses, this is the same as getIP(String).
     *  For host names, may return multiple addresses, both IPv4 and IPv6,
     *  even if those addresses are not reachable due to configuration or available interfaces.
     *  Will resolve but not cache DNS host names.
     *
     *  Note that order of returned results, and whether
     *  multiple results for either IPv4 or IPv6 or both are actually
     *  returned, is platform-specific and may also depend on
     *  JVM options such as java.net.preverIPv4Stack and java.net.preferIPv6Addresses.
     *  Number of results may also change based on caching at various layers,
     *  even if the ultimate name server results did not change.
     *
     *  @param host DNS or IPv4 or IPv6 host name; if null returns null
     *  @return non-empty list IPs, or null if none
     *  @since 0.9.28
     */
    public static List<byte[]> getIPs(String host) {
        if (host == null)
            return null;
        if (isIPAddress(host)) {
            byte[] brv = getIP(host);
            if (brv == null)
                return null;
            return Collections.singletonList(brv);
        }
        synchronized(_negativeCache) {
            Long when = _negativeCache.get(host);
            if (when != null) {
                if (when.longValue() > System.currentTimeMillis() - NEG_CACHE_TIME)
                    return null;
                _negativeCache.remove(host);
            }
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs == null || addrs.length == 0)
                return null;
            List<byte[]> rv = new ArrayList<byte[]>(addrs.length);
            for (int i = 0; i < addrs.length; i++) {
                rv.add(addrs[i].getAddress());
            }
            return rv;
        } catch (UnknownHostException uhe) {
            synchronized(_negativeCache) {
                _negativeCache.put(host, Long.valueOf(System.currentTimeMillis()));
            }
        }
        return null;
    }

    /**
     *  @since 0.9.34
     */
    public static boolean isIPv4Address(String host) {
        return InetAddressUtils.isIPv4Address(host);
    }

    /**
     *  @since 0.9.34
     */
    public static boolean isIPv6Address(String host) {
        return InetAddressUtils.isIPv6Address(host);
    }

    /**
     *  @return true if either IPv4 or IPv6
     *  @since 0.9.34
     */
    public static boolean isIPAddress(String host) {
        return InetAddressUtils.isIPv4Address(host) || InetAddressUtils.isIPv6Address(host);
    }




    //////// IPv6 Cache Utils ///////

    /**
     *  @since 0.9.28
     */
    private static class Inet6Addr {
        private final Inet6Address addr;
        private final boolean isDyn, isDep, isTemp;

        public Inet6Addr(Inet6Address a, int flags) {
            addr = a;
            isDyn = (flags & FLAG_PERMANENT) == 0;
            isDep = (flags & FLAG_DEPRECATED) != 0;
            isTemp = (flags & FLAG_TEMPORARY) != 0;
        }

        public Inet6Address getAddress() { return addr; }
        public boolean isDynamic() { return isDyn; }
        public boolean isDeprecated() { return isDep; }
        public boolean isTemporary() { return isTemp; }
    }

    /**
     *  Only call if INET6_CACHE_ENABLED.
     *  Caller must sync on _ifCache.
     *  @since 0.9.28
     */
    private static void refreshCache() {
        long now = System.currentTimeMillis();
        if (now - _ifCacheTime < INET6_CACHE_EXPIRE)
            return;
        _ifCache.clear();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(IF_INET6_FILE), "ISO-8859-1"), 1024);
            String line = null;
            StringBuilder buf = new StringBuilder(40);
            while ( (line = in.readLine()) != null) {
                // http://tldp.org/HOWTO/html_single/Linux+IPv6-HOWTO/#PROC-NET
                // 00000000000000000000000000000001 01 80 10 80       lo
                String[] parts = DataHelper.split(line, " ", 6);
                if (parts.length < 5)
                    continue;
                String as = parts[0];
                if (as.length() != 32)
                    continue;
                buf.setLength(0);
                int i = 0;
                while(true) {
                    buf.append(as.substring(i, i+4));
                    i += 4;
                    if (i >= 32)
                        break;
                    buf.append(':');
                }
                Inet6Address addr;
                try {
                    addr = (Inet6Address) InetAddress.getByName(buf.toString());
                } catch (UnknownHostException uhe) {
                    continue;
                }
                int flags = FLAG_PERMANENT;
                try {
                    flags = Integer.parseInt(parts[4], 16);
                } catch (NumberFormatException nfe) {}
                Inet6Addr a = new Inet6Addr(addr, flags);
                _ifCache.put(addr, a);
            }
        } catch (IOException ioe) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        _ifCacheTime = now;
    }

    /**
     *  Is this address dynamic?
     *  Should be reliable on Linux.
     *  Returns best guess on Windows, Mac, and BSD, only valid if global scope.
     *  @param addr an address of a local interface, as returned from e.g. getAddresses()
     *  @since 0.9.28
     */
    public static boolean isDynamic(Inet6Address addr) {
        if (!INET6_CACHE_ENABLED)
            return isTemporary(addr);
        Inet6Addr a;
        synchronized(_ifCache) {
            refreshCache();
            a = _ifCache.get(addr);
        }
        if (a == null)
            return false;
        return a.isDynamic();
    }

    /**
     *  Is this address deprecated?
     *  Should be reliable on Linux.
     *  Returns false on Windows, Mac, and BSD.
     *  @param addr an address of a local interface, as returned from e.g. getAddresses()
     *  @since 0.9.28
     */
    public static boolean isDeprecated(Inet6Address addr) {
        if (!INET6_CACHE_ENABLED)
            return false;
        Inet6Addr a;
        synchronized(_ifCache) {
            refreshCache();
            a = _ifCache.get(addr);
        }
        if (a == null)
            return false;
        return a.isDeprecated();
    }

    /**
     *  Is this address temporary?
     *  Should be reliable on Linux.
     *  Returns best guess on Windows, Mac, and BSD, only valid if global scope.
     *  @param addr an address of a local interface, as returned from e.g. getAddresses()
     *  @since 0.9.28
     */
    public static boolean isTemporary(Inet6Address addr) {
        if (!INET6_CACHE_ENABLED) {
            // do some guessing for Win, Mac, and BSD
            byte[] b = addr.getAddress();
            if (b.length != 16)
                return false;
            if (b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0)
                return false;
            String last3 = DataHelper.toHexString(Arrays.copyOfRange(b, 13, 16));
            synchronized(_macCache) {
                for (String m : _macCache) {
                    if (m.endsWith(last3))
                        return false;
                }
            }
            // RFC 7136 Section 3 says the 'U' bit (byte[8] & 0x02) can't be relied on
            // See also RFC 4291 Appendix A
            // Windows doesn't do this but BSD seems to
            if (!SystemVersion.isWindows())
                return (b[8] & 0x02) == 0;
            // This will often be wrong for Windows, which follows
            // RFC 4941 Section 3.2.1, the 'U' bit is always 0.
            // No apparent way to tell.
            return true;
        }
        Inet6Addr a;
        synchronized(_ifCache) {
            refreshCache();
            a = _ifCache.get(addr);
        }
        if (a == null)
            return false;
        return a.isTemporary();
    }

    //////// End IPv6 Cache Utils ///////

    /**
     *  @since 0.9.3
     */
    public static void clearCaches() {
        synchronized(_IPAddress) {
            _IPAddress.clear();
        }
        synchronized(_negativeCache) {
            _negativeCache.clear();
        }
        if (_ifCache != null) {
            synchronized(_ifCache) {
                _ifCache.clear();
                _ifCacheTime = 0;
            }
        }
        synchronized(_macCache) {
            _macCache.clear();
        }
    }

    /**
     *  Print out the local addresses
     */
    public static void main(String[] args) {
        System.out.println("External IPv4 Addresses:");
        Set<String> a = getAddresses(false, false, false);
        print(a);
        System.out.println("\nExternal and Local IPv4 Addresses:");
        a = getAddresses(true, false, false);
        print(a);
        System.out.println("\nAll External Addresses:");
        a = getAddresses(false, false, true);
        print(a);
        System.out.println("\nAll External and Local Addresses:");
        a = getAddresses(true, false, true);
        print(a);
        System.out.println("\nAll addresses:");
        long time = System.currentTimeMillis();
        a = getAddresses(true, true, true);
        time = System.currentTimeMillis() - time;
        print(a);
        System.out.println("\nIPv6 address flags:");
        for (String s : a) {
            if (!s.contains(":"))
                continue;
            StringBuilder buf = new StringBuilder(64);
            buf.append(s);
            Inet6Address addr;
            try {
                addr = (Inet6Address) InetAddress.getByName(buf.toString());
                if (addr.isSiteLocalAddress())
                    buf.append(" host");
                else if (addr.isLinkLocalAddress())
                    buf.append(" link");
                else if (addr.isAnyLocalAddress())
                    buf.append(" wildcard");
                else if (addr.isLoopbackAddress())
                    buf.append(" loopback");
                else {
                    buf.append(" global");
                    if (isTemporary(addr))
                        buf.append(" temporary");
                    if (isDynamic(addr))
                        buf.append(" dynamic");
                    if (isDeprecated(addr))
                        buf.append(" deprecated");
                }
            } catch (UnknownHostException uhe) {}
            System.out.println(buf.toString());
        }

        System.out.println("\nMac addresses:");
        Set<String> macs = new TreeSet<String>();
        StringBuilder buf = new StringBuilder(17);
        for (String m : _macCache) {
            buf.setLength(0);
            int i = 0;
            while(true) {
                buf.append(m.substring(i, i+2));
                i += 2;
                if (i >= 12)
                    break;
                buf.append(':');
            }
            macs.add(buf.toString());
        }
        print(macs);
        System.out.println("\nIs connected? " + isConnected() +
                           "\nIs conn IPv6? " + isConnectedIPv6());
        System.out.println("Has v6 flags? " + INET6_CACHE_ENABLED);
        System.out.println("Uses v6 temp? " + getPrivacyStatus());
        // Windows 8.1 Java 1.8.0_66 netbook appx. 200ms + 50ms/interface
        System.out.println("scan time:    " + DataHelper.formatDuration(time));
    }

    /** @since 0.9.34 */
    private static void print(Set<String> a) {
        if (a.isEmpty()) {
            System.out.println("none");
        } else {
            for (String s : a) {
                System.out.println(s);
            }
        }
    }

    /**
     * RFC 4941
     * @since 0.9.34
     */
    private static String getPrivacyStatus() {
        // Windows: netsh interface ipv6 show privacy
        // Mac: sysctl net.inet6.ip6.use_tempaddr (1 is enabled)
        if (SystemVersion.isMac() || SystemVersion.isWindows())
            return "unknown";
        long t = getLong("/proc/sys/net/ipv6/conf/all/use_tempaddr");
        if (t < 0)
            return "unknown";
        String rv;
        if (t == 0)
            rv = "false";
        else if (t == 2)
            rv = "true";
        else
            rv = "unknown";
        if (t == 2) {
            long pref = getLong("/proc/sys/net/ipv6/conf/all/temp_prefered_lft");
            if (pref > 0)
                rv += ", preferred lifetime " + DataHelper.formatDuration(pref * 1000);
            long valid = getLong("/proc/sys/net/ipv6/conf/all/temp_valid_lft");
            if (pref > 0)
                rv += ", valid lifetime " + DataHelper.formatDuration(valid * 1000);
        }
        return rv;
    }


    /**
     * Return first line in a file as a long
     * @return -1 on failure
     * @since 0.9.34
     */
    private static long getLong(String s) {
        File f = new File(s);
        long rv = -1;
        if (f.exists()) {
            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "ISO-8859-1"), 64);
                String line = in.readLine();
                if (line != null) {
                    try {
                        rv = Long.parseLong(line.trim());
                    } catch (NumberFormatException nfe) {}
                }
            } catch (IOException ioe) {
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
        return rv;
    }
}
