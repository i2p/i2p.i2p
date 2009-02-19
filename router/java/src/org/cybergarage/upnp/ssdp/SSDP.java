/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: SSDP.java
*
*	Revision;
*
*	11/18/02
*		- first revision.
*	05/13/03
*		- Added constants for IPv6.
*	
******************************************************************/

package org.cybergarage.upnp.ssdp;

public class SSDP
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////

	public static final int PORT = 1900;
	
	public static final String ADDRESS = "239.255.255.250";

	public static final String IPV6_LINK_LOCAL_ADDRESS = "FF02::C";
	public static final String IPV6_SUBNET_ADDRESS = "FF03::C";
	public static final String IPV6_ADMINISTRATIVE_ADDRESS = "FF04::C";
	public static final String IPV6_SITE_LOCAL_ADDRESS = "FF05::C";
	public static final String IPV6_GLOBAL_ADDRESS = "FF0E::C";
	
	private static String IPV6_ADDRESS;

	public static final void setIPv6Address(String addr)
	{
		IPV6_ADDRESS = addr;
	}

	public static final String getIPv6Address()
	{
		return IPV6_ADDRESS;
	}
	
	public static final int DEFAULT_MSEARCH_MX = 3;

	public static final int RECV_MESSAGE_BUFSIZE = 1024;

	////////////////////////////////////////////////
	//	Initialize
	////////////////////////////////////////////////

	static 
	{
		setIPv6Address(IPV6_LINK_LOCAL_ADDRESS);
	}
	
	////////////////////////////////////////////////
	//	LeaseTime
	////////////////////////////////////////////////
	
	public final static int getLeaseTime(String cacheCont)
	{
		int equIdx = cacheCont.indexOf('=');
		int mx = 0;
		try {
			String mxStr = new String(cacheCont.getBytes(), equIdx+1, cacheCont.length() - (equIdx+1));
			mx = Integer.parseInt(mxStr);
		}
		catch (Exception e) {}
		return mx;
	}
}

