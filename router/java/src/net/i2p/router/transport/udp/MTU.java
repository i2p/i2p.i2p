package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Get the MTU for the network interface of an address.
 * Not available until Java 6 / Android API 9.
 *
 * Public only for command line test.
 * Not for external use, not a public API.
 *
 * @since 0.9.2. public since 0.9.27
 */
public class MTU {

    private static final boolean hasMTU = SystemVersion.isJava6();
    
    private MTU() {};

    /**
     * The MTU for the socket interface, if available.
     * Not available for Java 5.
     *
     * Note that we don't return the value for the default interface if
     * we can't find the address. Finding the default interface is hard,
     * altough we could perhaps just look for the first non-loopback address.
     * But the MTU of the default route probably isn't relevant.
     *
     * For SSU2 MTU, values lower than PeerState2.MIN_MTU may be returned,
     * so the caller can determine if SSU2 should be supported.
     *
     * @param ia null ok
     * @param isSSU2 if true, calculate SSU2 MTU
     * @return 0 if Java 5, or if not bound to an address;
     *         limited to range MIN_MTU to LARGE_MTU for SSU 1.
     */
    public static int getMTU(InetAddress ia, boolean isSSU2) {
        if (ia == null || !hasMTU)
            return 0;
        Enumeration<NetworkInterface> ifcs;
        try {
            ifcs = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException se) {
            return 0;
        } catch (java.lang.Error e) {
            // Windows, possibly when IPv6 only...
            // https://bugs.openjdk.java.net/browse/JDK-8046500
            // java.lang.Error: IP Helper Library GetIfTable function failed
            //   at java.net.NetworkInterface.getAll(Native Method)
            //   at java.net.NetworkInterface.getNetworkInterfaces(Unknown Source)
            //   at net.i2p.util.Addresses.getAddresses ...
            return 0;
        }
        if (ifcs != null) {
            // save for fallback loop below, so we don't have to call getNetworkInterfaces() again
            List<NetworkInterface> interfaces = new ArrayList<NetworkInterface>();
            while (ifcs.hasMoreElements()) {
                NetworkInterface ifc = ifcs.nextElement();
                try {
                    if (!ifc.isUp()) {
                        // This is super-important on Windows which has 40+ down interfaces
                        // and will also deliver IP addresses for down interfaces,
                        // for example recently disconnected wifi.
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                interfaces.add(ifc);
                for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress addr = addrs.nextElement();
                    if (ia.equals(addr)) {
                        try {
                            // testing
                            //return ifc.getMTU();
                            boolean isIPv6 = addr instanceof Inet6Address;
                            int mtu = ifc.getMTU();
                            // can be -1 on Windows
                            if (mtu < 0)
                                mtu = isIPv6 ? PeerState.MIN_IPV6_MTU : 1500;
                            if (mtu > 0 &&
                                ((isIPv6 && mtu < PeerState.MIN_IPV6_MTU) ||
                                 (!isIPv6 && mtu < PeerState.MIN_MTU))) {
                                Log log = I2PAppContext.getGlobalContext().logManager().getLog(MTU.class);
                                log.logAlways(Log.WARN, "Unusually low MTU " + mtu + " for interface " + ia +
                                                        ", consider disabling");
                            }
                            // fix for brokered tunnels with too big MTU
                            if (isIPv6 && mtu > 1420) {
                                byte[] ip = addr.getAddress();
                                // he.net
                                if (mtu > 1472 &&
                                    ip[0] == 0x20 && ip[1] == 0x01 &&
                                    ip[2] == 0x04 && ip[3] == 0x70)
                                    return 1472;
                                // route48.org, supports Wireguard
                                if (ip[0] == 0x2a && ip[1] == 0x06 &&
                                    ip[2] == (byte) 0xa0 && ip[3] == 0x04)
                                    return 1420;
                            }
                            if (isSSU2)
                                return Math.min(mtu, PeerState2.MAX_MTU);
                            // don't rectify 1280 down to 1276 because that
                            // borks a shared SSU/SSU2 address
                            if (mtu == PeerState2.MIN_MTU)
                                return PeerState2.MIN_MTU;
                            return rectify(isIPv6, mtu);
                        } catch (SocketException se) {
                            // ignore
                        } catch (Throwable t) {
                            // NoSuchMethodException or NoSuchMethodError if we somehow got the
                            // version detection wrong or the JVM doesn't support it
                            return 0;
                        }
                    }
                }
            }

            // didn't find a match, probably behind a NAT
            // try again, looping through all the interfaces that were up,
            // just get the minimum of all interfaces with addresses of that type v4/v6
            boolean isIPv6 = ia instanceof Inet6Address;
            int rv = 1501;
            outer:
            for (NetworkInterface ifc : interfaces) {
                for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress addr = addrs.nextElement();
                    if (isIPv6 != (addr instanceof Inet6Address))
                        continue;
                    if (addr.isLinkLocalAddress() ||
                        addr.isMulticastAddress() ||
                        addr.isAnyLocalAddress() ||
                        addr.isLoopbackAddress())
                        continue;
                    if (isIPv6) {
                        // ygg
                        byte[] ip = addr.getAddress();
                        if ((ip[0] & 0xfe) == 0x02)
                            continue outer;
                    }
                    int mtu;
                    try {
                        mtu = ifc.getMTU();
                    } catch (Throwable t) {
                        continue outer;
                    }
                    if (mtu < 0)
                        continue outer;
                    if (isIPv6 && mtu > 1420) {
                        byte[] ip = addr.getAddress();
                        if (mtu > 1472 &&
                            ip[0] == 0x20 && ip[1] == 0x01 &&
                            ip[2] == 0x04 && ip[3] == 0x70)
                            mtu = 1472;
                        if (ip[0] == 0x2a && ip[1] == 0x06 &&
                            ip[2] == (byte) 0xa0 && ip[3] == 0x04)
                            mtu = 1420;
                    }
                    if (mtu < rv)
                        rv = mtu;
                    continue outer;
                }
            }
            if (rv < 1501) {
                if (isSSU2)
                    return rv;
                if (rv == PeerState2.MIN_MTU)
                    return rv;
                return rectify(isIPv6, rv);
            }
        }
        return 0;
    }

    /**
     * @return min of PeerState.MIN_MTU, max of PeerState.LARGE_MTU,
     *         rectified so rv % 16 == 12 (IPv4)
     *         or rv % 16 == 0 (IPv6)
     */
    public static int rectify(boolean isIPv6, int mtu) {
        int rv = mtu;
        int mod = rv % 16;
        if (isIPv6) {
            rv -= mod;
            return Math.max(PeerState.MIN_IPV6_MTU, Math.min(PeerState.MAX_IPV6_MTU, rv));
        }
        if (mod > 12)
            rv -= mod - 12;
        else if (mod < 12)
            rv -= mod + 4;
        return Math.max(PeerState.MIN_MTU, Math.min(PeerState.LARGE_MTU, rv));
    }

    public static void main(String args[]) {
        if (args.length > 0) {
            System.out.println("Cmd line interfaces:");
            for (int i = 0; i < args.length; i++) {
                try {
                    InetAddress test = InetAddress.getByName(args[i]);
                    System.out.println("I2P MTU of " + args[i] + " is " + getMTU(test, false) +
                                       "; SSU2 MTU is " + getMTU(test, true));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        System.out.println("All interfaces:");
        try {
            Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces();
            if (ifcs != null) {
                // address to MTU
                Map<InetAddress, Integer> sorted = new TreeMap<InetAddress, Integer>(new IAComparator());
                // this is O(n**2) through the interfaces and very slow on windows
                while (ifcs.hasMoreElements()) {
                    NetworkInterface ifc = ifcs.nextElement();
                    try {
                        if (!ifc.isUp()) {
                            // This is super-important on Windows which has 40+ down interfaces
                            // and will also deliver IP addresses for down interfaces,
                            // for example recently disconnected wifi.
                            continue;
                        }
                    } catch (SocketException e) {
                        continue;
                    }
                    int mtu = ifc.getMTU();
                    for (Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                        InetAddress addr = addrs.nextElement();
                        sorted.put(addr, Integer.valueOf(mtu));
                    }
                }
                for (Map.Entry<InetAddress, Integer> e : sorted.entrySet()) {
                    InetAddress addr = e.getKey();
                    Integer mtu = e.getValue();
                    System.out.println("MTU for " + addr.getHostAddress() + " is " + mtu +
                                           "; I2P MTU is " + getMTU(addr, false) +
                                           "; SSU2 MTU is " + getMTU(addr, true));
                }
            }
        } catch (SocketException se) {
             System.out.println("no interfaces");
        }
    }

    /** @since 0.9.57 */
    private static class IAComparator implements Comparator<InetAddress> {
        public int compare(InetAddress l, InetAddress r) {
             return (l.getHostAddress()).compareTo((r.getHostAddress()));
        }
    }
}
