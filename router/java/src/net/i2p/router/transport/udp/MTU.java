package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

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
     * @param ia null ok
     * @return 0 if Java 5, or if not bound to an address;
     *         limited to range MIN_MTU to LARGE_MTU.
     */
    public static int getMTU(InetAddress ia) {
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
            while (ifcs.hasMoreElements()) {
                NetworkInterface ifc = ifcs.nextElement();
                for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress addr = addrs.nextElement();
                    if (ia.equals(addr)) {
                        try {
                            // testing
                            //return ifc.getMTU();
                            boolean isIPv6 = addr instanceof Inet6Address;
                            int mtu = ifc.getMTU();
                            if ((isIPv6 && mtu < PeerState.MIN_IPV6_MTU) ||
                                (!isIPv6 && mtu < PeerState.MIN_MTU)) {
                                Log log = I2PAppContext.getGlobalContext().logManager().getLog(MTU.class);
                                log.logAlways(Log.WARN, "Unusually low MTU " + mtu + " for interface " + ia +
                                                        ", consider disabling");
                            }
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
/****
        System.out.println("Cmd line interfaces:");
        for (int i = 0; i < args.length; i++) {
            try {
                InetAddress test = InetAddress.getByName(args[i]);
                System.out.println("MTU of " + args[i] + " is " + getMTU(test));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println("All interfaces:");
****/
        try {
            Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces();
            if (ifcs != null) {
                while (ifcs.hasMoreElements()) {
                    NetworkInterface ifc = ifcs.nextElement();
                    for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                        InetAddress addr = addrs.nextElement();
                        System.out.println("I2P MTU for " + addr.getHostAddress() + " is " + getMTU(addr));
                    }
                }
            }
        } catch (SocketException se) {
             System.out.println("no interfaces");
        }
    }
}
