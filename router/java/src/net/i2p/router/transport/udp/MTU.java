package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import net.i2p.util.VersionComparator;

/**
 * Get the MTU for the network interface of an address.
 * Not available until Java 6 / Android API 9.
 * @since 0.9.2
 */
abstract class MTU {

    private static final boolean hasMTU =
        (new VersionComparator()).compare(System.getProperty("java.version"), "1.6") >= 0;
    
    /**
     * The MTU for the socket interface, if available.
     * Not available for Java 5.
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
                            return rectify(ifc.getMTU());
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
     *         rectifyed so rv % 16 == 12
     */
    public static int rectify(int mtu) {
        int rv = mtu;
        int mod = rv % 16;
        if (mod > 12)
            rv -= mod - 12;
        else if (mod < 12)
            rv -= mod + 4;
        return Math.max(PeerState.MIN_MTU, Math.min(PeerState.LARGE_MTU, rv));
    }

/****
    public static void main(String args[]) {
        try {
            InetAddress test = InetAddress.getByName(args[0]);
            System.out.println("MTU is " + getMTU(test));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
****/
}
