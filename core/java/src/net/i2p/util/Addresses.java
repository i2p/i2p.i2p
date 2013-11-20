package net.i2p.util;

/*
 * public domain
 */

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import net.i2p.I2PAppContext;

/**
 * Methods to get the local addresses, and other IP utilities
 *
 * @since 0.8.3 moved to core from router/transport
 * @author zzz
 */
public abstract class Addresses {
    
    /**
     *  Do we have any non-loop, non-wildcard IPv4 address at all?
     *  @since 0.9.4
     */
    public static boolean isConnected() {
        // not as good as using a Java DBus implementation to talk to NetworkManager...
        return !getAddresses(true, false, false).isEmpty();
    }

    /** @return the first non-local address IPv4 address it finds, or null */
    public static String getAnyAddress() {
        SortedSet<String> a = getAddresses();
        if (!a.isEmpty())
            return a.first();
        return null;
    }

    /**
     *  @return a sorted set of all addresses, excluding
     *  IPv6, local, broadcast, multicast, etc.
     */
    public static SortedSet<String> getAddresses() {
        return getAddresses(false, false);
    }

    /**
     *  @return a sorted set of all addresses, excluding
     *  only link local and multicast
     *  @since 0.8.3
     */
    public static SortedSet<String> getAllAddresses() {
        return getAddresses(true, true);
    }

    /**
     *  @return a sorted set of all addresses including wildcard
     *  @param includeLocal whether to include local
     *  @param includeIPv6 whether to include IPV6
     *  @return an array of all addresses
     *  @since 0.8.3
     */
    public static SortedSet<String> getAddresses(boolean includeLocal, boolean includeIPv6) {
        return getAddresses(includeLocal, includeLocal, includeIPv6);
    }

    /**
     *  @return a sorted set of all addresses
     *  @param includeSiteLocal whether to include private like 192.168.x.x
     *  @param includeLoopbackAndWildcard whether to include 127.x.x.x and 0.0.0.0
     *  @param includeIPv6 whether to include IPV6
     *  @return an array of all addresses
     *  @since 0.9.4
     */
    public static SortedSet<String> getAddresses(boolean includeSiteLocal,
                                                 boolean includeLoopbackAndWildcard,
                                                 boolean includeIPv6) {
        boolean haveIPv4 = false;
        boolean haveIPv6 = false;
        SortedSet<String> rv = new TreeSet<String>();
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            InetAddress[] allMyIps = InetAddress.getAllByName(localhost.getCanonicalHostName());
            if (allMyIps != null) {
                for (int i = 0; i < allMyIps.length; i++) {
                    if (allMyIps[i] instanceof Inet4Address)
                        haveIPv4 = true;
                    else
                        haveIPv6 = true;
                    if (shouldInclude(allMyIps[i], includeSiteLocal,
                                      includeLoopbackAndWildcard, includeIPv6))
                        rv.add(stripScope(allMyIps[i].getHostAddress()));
                }
            }
        } catch (UnknownHostException e) {}

        try {
            Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces();
            if (ifcs != null) {
                while (ifcs.hasMoreElements()) {
                    NetworkInterface ifc = ifcs.nextElement();
                    for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address)
                            haveIPv4 = true;
                        else
                            haveIPv6 = true;
                        if (shouldInclude(addr, includeSiteLocal,
                                          includeLoopbackAndWildcard, includeIPv6))
                            rv.add(stripScope(addr.getHostAddress()));
                    }
                }
            }
        } catch (SocketException e) {}

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
        int pct = ip.indexOf("%");
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

    static {
        int size;
        I2PAppContext ctx = I2PAppContext.getCurrentContext();
        if (ctx != null && ctx.isRouterContext()) {
            long maxMemory = SystemVersion.getMaxMemory();
            long min = 128;
            long max = 4096;
            // 512 nominal for 128 MB
            size = (int) Math.max(min, Math.min(max, 1 + (maxMemory / (256*1024))));
        } else {
            size = 32;
        }
        _IPAddress = new LHMCache<String, byte[]>(size);
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
            try {
                boolean isIPv4 = host.replaceAll("[0-9\\.]", "").length() == 0;
                if (isIPv4 && host.replaceAll("[0-9]", "").length() != 3)
                    return null;
                rv = InetAddress.getByName(host).getAddress();
                if (isIPv4 ||
                    host.replaceAll("[0-9a-fA-F:]", "").length() == 0) {
                    synchronized (_IPAddress) {
                        _IPAddress.put(host, rv);
                    }
                }
            } catch (UnknownHostException uhe) {}
        }
        return rv;
    }

    /**
     *  @since 0.9.3
     */
    public static void clearCaches() {
        synchronized(_IPAddress) {
            _IPAddress.clear();
        }
    }

    /**
     *  Print out the local addresses
     */
    public static void main(String[] args) {
        System.err.println("External IPv4 Addresses:");
        Set<String> a = getAddresses(false, false, false);
        for (String s : a)
            System.err.println(s);
        System.err.println("\nExternal and Local IPv4 Addresses:");
        a = getAddresses(true, false, false);
        for (String s : a)
            System.err.println(s);
        System.err.println("\nAll External Addresses:");
        a = getAddresses(false, false, true);
        for (String s : a)
            System.err.println(s);
        System.err.println("\nAll External and Local Addresses:");
        a = getAddresses(true, false, true);
        for (String s : a)
            System.err.println(s);
        System.err.println("\nAll addresses:");
        a = getAddresses(true, true, true);
        for (String s : a)
            System.err.println(s);
        System.err.println("\nIs connected? " + isConnected());
    }
}
