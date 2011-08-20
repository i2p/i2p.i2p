/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: SSDPNotifySocket.java
*
*	Revision;
*
*	11/20/02
*		- first revision.
*	05/13/03
*		- Added support for IPv6.
*	02/20/04
*		- Inma Marin Lopez <inma@dif.um.es>
*		- Added a multicast filter using the SSDP pakcet.
*	04/20/05
*		- Mikael Hakman <mhakman@dkab.net>
*		- Handle receive() returning null.
*		- Added close() in stop().
*	
******************************************************************/

package org.cybergarage.upnp.ssdp;

import java.net.*;

import org.cybergarage.net.*;
import org.cybergarage.http.*;
import org.cybergarage.upnp.*;
import org.cybergarage.util.Debug;

public class SSDPNotifySocket extends HTTPMUSocket implements Runnable
{
	private boolean useIPv6Address;
	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public SSDPNotifySocket(String bindAddr)
	{
		String addr = SSDP.ADDRESS;
		useIPv6Address = false;
		if (HostInterface.isIPv6Address(bindAddr) == true) {
			addr = SSDP.getIPv6Address();
			useIPv6Address = true;
		}
		open(addr, SSDP.PORT, bindAddr);
		Debug.warning("Opened SSDP notify socket at " + bindAddr + ':' + SSDP.PORT);
		setControlPoint(null);
	}

	////////////////////////////////////////////////
	//	ControlPoint	
	////////////////////////////////////////////////

	private ControlPoint controlPoint = null;
	
	public void setControlPoint(ControlPoint ctrlp)
	{
		this.controlPoint = ctrlp;
	}

	public ControlPoint getControlPoint()
	{
		return controlPoint;
	}

	////////////////////////////////////////////////
	//	post (SSDPNotifySocket)
	////////////////////////////////////////////////

	public boolean post(SSDPNotifyRequest req)
	{
		String ssdpAddr = SSDP.ADDRESS;
		if (useIPv6Address == true)
			ssdpAddr = SSDP.getIPv6Address();
		req.setHost(ssdpAddr, SSDP.PORT);
		return post((HTTPRequest)req);
	}

	////////////////////////////////////////////////
	//	run	
	////////////////////////////////////////////////

	private Thread deviceNotifyThread = null;
		
	public void run()
	{
		Thread thisThread = Thread.currentThread();
		
		ControlPoint ctrlPoint = getControlPoint();
		
		while (deviceNotifyThread == thisThread) {
			Thread.yield();
			SSDPPacket packet = receive();
			
			// Thanks for Mikael Hakman (04/20/05)
			if (packet == null)
				continue;
			
			// Thanks for Inma (02/20/04)
			InetAddress maddr = getMulticastInetAddress();
			InetAddress pmaddr = packet.getHostInetAddress();
			if (maddr.equals(pmaddr) == false) {
				// I2P
				//Debug.warning("Invalidate Multicast Recieved : " + maddr + "," + pmaddr);
				continue;
			}
												
			if (ctrlPoint != null)
				ctrlPoint.notifyReceived(packet); 
		}
	}
	
	public void start()
	{
		deviceNotifyThread = new Thread(this, "UPnP-SSDPNotifySocket");
		deviceNotifyThread.setDaemon(true);
		deviceNotifyThread.start();
	}
	
	public void stop()
	{
		// Thanks for Mikael Hakman (04/20/05)
		close();
		
		deviceNotifyThread = null;
	}
}

