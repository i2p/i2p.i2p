/******************************************************************
*
*	CyberHTTP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: HostInterface.java
*
*	Revision;
*
*	05/12/03
*		- first revision.
*	05/13/03
*		- Added support for IPv6 and loopback address.
*	02/15/04
*		- Added the following methods to set only a interface.
*		- setInterface(), getInterfaces(), hasAssignedInterface()
*	06/30/04
*		- Moved the package from org.cybergarage.http to org.cybergarage.net.
*	06/30/04
*		- Theo Beisch <theo.beisch@gmx.de>
*		- Changed isUseAddress() to isUsableAddress().
*	
******************************************************************/

package org.cybergarage.net;

import java.net.*;
import java.util.*;

public class HostInterface
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	public static boolean USE_LOOPBACK_ADDR = false;
	public static boolean USE_ONLY_IPV4_ADDR = false;
	public static boolean USE_ONLY_IPV6_ADDR = false;
	 
	////////////////////////////////////////////////
	//	Network Interfaces
	////////////////////////////////////////////////
	
	private static String ifAddress = "";

	public final static void setInterface(String ifaddr)
	{
		ifAddress = ifaddr;
	}
	
	public final static String getInterface()
	{
		return ifAddress;
	}
	
	private final static boolean hasAssignedInterface()
	{
		return (0 < ifAddress.length()) ? true : false;
	}
	
	////////////////////////////////////////////////
	//	Network Interfaces
	////////////////////////////////////////////////

	// Thanks for Theo Beisch (10/27/04)
	
	private final static boolean isUsableAddress(InetAddress addr)
	{
		if (USE_LOOPBACK_ADDR == false) {
			if (addr.isLoopbackAddress() == true)
				return false;
		}
		if (USE_ONLY_IPV4_ADDR == true) {
			if (addr instanceof Inet6Address)
				return false;
		}
		if (USE_ONLY_IPV6_ADDR == true) {
			if (addr instanceof Inet4Address)
				return false;
		}
		return true;
	}
	
	public final static int getNHostAddresses()
	{
		if (hasAssignedInterface() == true)
			return 1;
			
		int nHostAddrs = 0;
		try {
			Enumeration nis = NetworkInterface.getNetworkInterfaces();
			while (nis.hasMoreElements()){
				NetworkInterface ni = (NetworkInterface)nis.nextElement();
				Enumeration addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress addr = (InetAddress)addrs.nextElement();
					if (isUsableAddress(addr) == false)
						continue;
					nHostAddrs++;
				}
			}
		}
		catch(Exception e){};
		return nHostAddrs;
	}

	public final static String getHostAddress(int n)
	{
		if (hasAssignedInterface() == true)
			return getInterface();
			
		int hostAddrCnt = 0;
		try {
			Enumeration nis = NetworkInterface.getNetworkInterfaces();
			while (nis.hasMoreElements()){
				NetworkInterface ni = (NetworkInterface)nis.nextElement();
				Enumeration addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress addr = (InetAddress)addrs.nextElement();
					if (isUsableAddress(addr) == false)
						continue;
					if (hostAddrCnt < n) {
						hostAddrCnt++;
						continue;
					}
					String host = addr.getHostAddress();
					//if (addr instanceof Inet6Address)
					//	host = "[" + host + "]";
					return host;
				}
			}
		}
		catch(Exception e){};
		return "";
	}

	////////////////////////////////////////////////
	//	isIPv?Address
	////////////////////////////////////////////////
	
	public final static boolean isIPv6Address(String host)
	{
		try {
			InetAddress addr = InetAddress.getByName(host);
			return (addr instanceof Inet6Address);
		}
		catch (Exception e) {}
		return false;
	}

	public final static boolean isIPv4Address(String host)
	{
		try {
			InetAddress addr = InetAddress.getByName(host);
			return (addr instanceof Inet4Address);
		}
		catch (Exception e) {}
		return false;
	}

	////////////////////////////////////////////////
	//	hasIPv?Interfaces
	////////////////////////////////////////////////

	public final static boolean hasIPv4Addresses()
	{
		int addrCnt = getNHostAddresses();
		for (int n=0; n<addrCnt; n++) {
			String addr = getHostAddress(n);
			if (isIPv4Address(addr) == true)
				return true;
		}
		return false;
	}

	public final static boolean hasIPv6Addresses()
	{
		int addrCnt = getNHostAddresses();
		for (int n=0; n<addrCnt; n++) {
			String addr = getHostAddress(n);
			if (isIPv6Address(addr) == true)
				return true;
		}
		return false;
	}

	////////////////////////////////////////////////
	//	hasIPv?Interfaces
	////////////////////////////////////////////////

	public final static String getIPv4Address()
	{
		int addrCnt = getNHostAddresses();
		for (int n=0; n<addrCnt; n++) {
			String addr = getHostAddress(n);
			if (isIPv4Address(addr) == true)
				return addr;
		}
		return "";
	}

	public final static String getIPv6Address()
	{
		int addrCnt = getNHostAddresses();
		for (int n=0; n<addrCnt; n++) {
			String addr = getHostAddress(n);
			if (isIPv6Address(addr) == true)
				return addr;
		}
		return "";
	}

	////////////////////////////////////////////////
	//	getHostURL
	////////////////////////////////////////////////
	
	public final static String getHostURL(String host, int port, String uri)
	{
		String hostAddr = host;
		if (isIPv6Address(host) == true)
			hostAddr = "[" + host + "]";
		return 
			"http://" +
			hostAddr + 
			":" + Integer.toString(port) +
			uri;
	}
	
}
