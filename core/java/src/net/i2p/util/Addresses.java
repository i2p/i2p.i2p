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
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;


/**
 * Methods to get the local addresses, and other IP utilities
 *
 * @since 0.8.3 moved to core from router/transport
 * @author zzz
 */
public abstract class Addresses {
    
    /** @return the first non-local address it finds, or null */
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
     *  @return a sorted array of all addresses
     *  @param includeLocal whether to include local
     *  @param includeIPv6 whether to include IPV6
     *  @return an array of all addresses
     *  @since 0.8.3
     */
    public static SortedSet<String> getAddresses(boolean includeLocal, boolean includeIPv6) {
        boolean haveIPv4 = false;
        boolean haveIPv6 = false;
        SortedSet<String> rv = new TreeSet();
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            InetAddress[] allMyIps = InetAddress.getAllByName(localhost.getCanonicalHostName());
            if (allMyIps != null) {
                for (int i = 0; i < allMyIps.length; i++) {
                    if (allMyIps[i] instanceof Inet4Address)
                        haveIPv4 = true;
                    else
                        haveIPv6 = true;
                    if (shouldInclude(allMyIps[i], includeLocal, includeIPv6))
                        rv.add(allMyIps[i].getHostAddress());
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
                        if (shouldInclude(addr, includeLocal, includeIPv6))
                            rv.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {}

        if (includeLocal && haveIPv4)
            rv.add("0.0.0.0");
        if (includeLocal && includeIPv6 && haveIPv6)
            rv.add("0:0:0:0:0:0:0:0");  // we could do "::" but all the other ones are probably in long form
        return rv;
    }

    private static boolean shouldInclude(InetAddress ia, boolean includeLocal, boolean includeIPv6) {
        return
            (!ia.isLinkLocalAddress()) &&
            (!ia.isMulticastAddress()) &&
            (includeLocal ||
             ((!ia.isAnyLocalAddress()) &&
              (!ia.isLoopbackAddress()) &&
              (!ia.isSiteLocalAddress()))) &&
            // Hamachi 5/8 allocated to RIPE (30 November 2010)
            // Removed from TransportImpl.isPubliclyRoutable()
            // Check moved to here, for now, but will eventually need to
            // remove it from here also.
            (includeLocal ||
            (!ia.getHostAddress().startsWith("5."))) &&
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
     *  Print out the local addresses
     */
    public static void main(String[] args) {
        System.err.println("External Addresses:");
        Set<String> a = getAddresses(false, false);
        for (String s : a)
            System.err.println(s);
        System.err.println("All addresses:");
        a = getAddresses(true, true);
        for (String s : a)
            System.err.println(s);
    }
}
