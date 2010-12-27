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
 * Get the local addresses
 *
 * @since 0.8.3 moved to core
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
     *  @param whether to exclude IPV6 and local
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
            for(Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces(); ifcs.hasMoreElements();) {
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
