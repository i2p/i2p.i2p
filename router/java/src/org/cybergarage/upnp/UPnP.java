/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: UPnP.java
*
*	Revision;
*
*	11/18/02
*		- first revision.
*	05/13/03
*		- Added support for IPv6 and loopback address.
*	12/26/03
*		- Added support for XML Parser
*	06/18/03
*		- Added INMPR03 and INMPR03_VERSION.
*	
******************************************************************/

package org.cybergarage.upnp;

import org.cybergarage.upnp.ssdp.*;
//import org.cybergarage.util.*;
import org.cybergarage.xml.*;
import org.cybergarage.xml.parser.*;
import org.cybergarage.soap.*;
import org.cybergarage.net.*;

public class UPnP
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	public final static String NAME = "CyberLink";
	public final static String VERSION = "1.7";

	public final static int SERVER_RETRY_COUNT = 100;
	public final static int DEFAULT_EXPIRED_DEVICE_EXTRA_TIME = 60;

	public final static String getServerName()
	{
		String osName = System.getProperty("os.name");
		String osVer = System.getProperty("os.version");
		return osName + "/"  + osVer + " UPnP/1.0 " + NAME + "/" + VERSION;
	}
	
	public final static String INMPR03 = "INMPR03";
	public final static String INMPR03_VERSION = "1.0";
	public final static int INMPR03_DISCOVERY_OVER_WIRELESS_COUNT = 4;

	public final static String XML_DECLARATION = "<?xml version=\"1.0\"?>";
	
	////////////////////////////////////////////////
	//	Enable / Disable
	////////////////////////////////////////////////
	
	public final static int USE_ONLY_IPV6_ADDR = 1;
	public final static int USE_LOOPBACK_ADDR = 2;
	public final static int USE_IPV6_LINK_LOCAL_SCOPE = 3;
	public final static int USE_IPV6_SUBNET_SCOPE = 4;
	public final static int USE_IPV6_ADMINISTRATIVE_SCOPE = 5;
	public final static int USE_IPV6_SITE_LOCAL_SCOPE = 6;
	public final static int USE_IPV6_GLOBAL_SCOPE = 7;
	public final static int USE_SSDP_SEARCHRESPONSE_MULTIPLE_INTERFACES = 8;
	public final static int USE_ONLY_IPV4_ADDR = 9;
	
	public final static void setEnable(int value)
	{
		switch (value) {
		case USE_ONLY_IPV6_ADDR:
			{
				HostInterface.USE_ONLY_IPV6_ADDR = true;
			}
			break;	
		case USE_ONLY_IPV4_ADDR:
			{
				HostInterface.USE_ONLY_IPV4_ADDR = true;
			}
			break;	
		case USE_LOOPBACK_ADDR:
			{
				HostInterface.USE_LOOPBACK_ADDR = true;
			}
			break;	
		case USE_IPV6_LINK_LOCAL_SCOPE:
			{
				SSDP.setIPv6Address(SSDP.IPV6_LINK_LOCAL_ADDRESS);
			}
			break;	
		case USE_IPV6_SUBNET_SCOPE:
			{
				SSDP.setIPv6Address(SSDP.IPV6_SUBNET_ADDRESS);
			}
			break;	
		case USE_IPV6_ADMINISTRATIVE_SCOPE:
			{
				SSDP.setIPv6Address(SSDP.IPV6_ADMINISTRATIVE_ADDRESS);
			}
			break;	
		case USE_IPV6_SITE_LOCAL_SCOPE:
			{
				SSDP.setIPv6Address(SSDP.IPV6_SITE_LOCAL_ADDRESS);
			}
			break;	
		case USE_IPV6_GLOBAL_SCOPE:
			{
				SSDP.setIPv6Address(SSDP.IPV6_GLOBAL_ADDRESS);
			}
			break;	
		}
	}

	public final static void setDisable(int value)
	{
		switch (value) {
		case USE_ONLY_IPV6_ADDR:
			{
				HostInterface.USE_ONLY_IPV6_ADDR = false;
			}
			break;	
		case USE_ONLY_IPV4_ADDR:
			{
				HostInterface.USE_ONLY_IPV4_ADDR = false;
			}
			break;	
		case USE_LOOPBACK_ADDR:
			{
				HostInterface.USE_LOOPBACK_ADDR = false;
			}
			break;	
		}
	}

	public final static boolean isEnabled(int value)
	{
		switch (value) {
		case USE_ONLY_IPV6_ADDR:
			{
				return HostInterface.USE_ONLY_IPV6_ADDR;
			}
		case USE_ONLY_IPV4_ADDR:
			{
				return HostInterface.USE_ONLY_IPV4_ADDR;
			}
		case USE_LOOPBACK_ADDR:
			{
				return HostInterface.USE_LOOPBACK_ADDR;
			}
		}
		return false;
	}

	////////////////////////////////////////////////
	//	UUID
	////////////////////////////////////////////////

	private static final String toUUID(int seed)
	{
		String id = Integer.toString((int)(seed & 0xFFFF), 16);
		int idLen = id.length();
		String uuid = "";
		for (int n=0; n<(4-idLen); n++)
			uuid += "0";
		uuid += id;
		return uuid;
	}
	
	public static final String createUUID()
	{
		long time1 = System.currentTimeMillis();
		long time2 = (long)((double)System.currentTimeMillis() * Math.random());
		return 
			toUUID((int)(time1 & 0xFFFF)) + "-" +
			toUUID((int)((time1 >> 32) | 0xA000) & 0xFFFF) + "-" +
			toUUID((int)(time2 & 0xFFFF)) + "-" +
			toUUID((int)((time2 >> 32) | 0xE000) & 0xFFFF);
	}

	////////////////////////////////////////////////
	// XML Parser
	////////////////////////////////////////////////

	private static Parser xmlParser;
	
	public final static void setXMLParser(Parser parser)
	{
		xmlParser = parser;
		SOAP.setXMLParser(parser);
	}
	
	public final static Parser getXMLParser()
	{
		return xmlParser;
	}
	
	////////////////////////////////////////////////
	//	Initialize
	////////////////////////////////////////////////
	
	static 
	{
		////////////////////////////
		// Interface Option
		////////////////////////////
		
		setXMLParser(new JaxpParser());
		//setXMLParser(new kXML2Parser());
		
		////////////////////////////
		// Interface Option
		////////////////////////////
		/*
		if (HostInterface.hasIPv6Addresses() == true)
			setEnable(USE_ONLY_IPV6_ADDR);
		*/
		
		////////////////////////////
		// Debug Option
		////////////////////////////
		
		//Debug.on();
	}
	
	public final static void initialize()
	{
		// Dummy function to call UPnP.static
	}

}
