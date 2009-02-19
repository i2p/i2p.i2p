/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package org.freenetproject;

import java.net.InetAddress;

/**
 * Class returned by a FredPluginIPDetector.
 * 
 * Indicates:
 * - Whether there is no UDP connectivity at all.
 * - Whether there is full inbound IP connectivity.
 * - A list of detected public IPs.
 */
public class DetectedIP {

	public final InetAddress publicAddress;
	public final short natType;
	/** The MTU as advertized by the JVM */
	public int mtu;
	// Constants
	/** The plugin does not support detecting the NAT type. */
	public static final short NOT_SUPPORTED = 1;
	/** Full internet access! */
	public static final short FULL_INTERNET = 2;
	/** Full cone NAT. Once we have sent a packet out on a port, any node anywhere can send us
	 * a packet on that port. The nicest option, but very rare unfortunately. */
	public static final short FULL_CONE_NAT = 3;
	/** Restricted cone NAT. Once we have sent a packet out to a specific IP, it can send us 
	 * packets on the port we just used. */
	public static final short RESTRICTED_CONE_NAT = 4;
	/** Port restricted cone NAT. Once we have sent a packet to a specific IP+Port, that IP+Port
	 * can send us packets on the port we just used. */
	public static final short PORT_RESTRICTED_NAT = 5;
	/** Symmetric NAT. Uses a separate port number for each IP+port ! Not much hope for symmetric
	 * to symmetric... */
	public static final short SYMMETRIC_NAT = 6;
	/** Symmetric UDP firewall. We are not NATed, but the firewall behaves as if we were. */
	public static final short SYMMETRIC_UDP_FIREWALL = 7;
	/** No UDP connectivity at all */
	public static final short NO_UDP = 8;
	
	public DetectedIP(InetAddress addr, short type) {
		this.publicAddress = addr;
		this.natType = type;
		this.mtu = 1500;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof DetectedIP)) {
			return false;
		}
		DetectedIP d = (DetectedIP)o;
		return ((d.natType == natType) && d.publicAddress.equals(publicAddress));
	}
	
	@Override
	public int hashCode() {
		return publicAddress.hashCode() ^ natType;
	}
	
	@Override
	public String toString() {
		return publicAddress.toString()+":"+natType+":"+mtu;
	}
}
