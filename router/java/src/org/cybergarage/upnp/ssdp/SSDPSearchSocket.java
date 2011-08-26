/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: SSDPSearchSocket.java
*
*	Revision;
*
*	12/30/02
*		- first revision.
*	05/13/03
*		- Added support for IPv6.
*	05/28/03
*		- Moved post() for SSDPSearchRequest to SSDPResponseSocketList.
*	04/20/05
*		- Mikael Hakman <mhakman@dkab.net>
*		- Added close() in stop().
*		- Added test for null return from receive() in run().
*	
******************************************************************/

package org.cybergarage.upnp.ssdp;

import org.cybergarage.net.*;
import org.cybergarage.util.*;

import org.cybergarage.upnp.device.*;

public class SSDPSearchSocket extends HTTPMUSocket implements Runnable
{
	public SSDPSearchSocket()
	{
	}
	
	public SSDPSearchSocket(String bindAddr)
	{
		open(bindAddr);
		Debug.message("Opened SSDP search socket at " + bindAddr + ':' + SSDP.PORT);
	}

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////

	public boolean open(String bindAddr)
	{
		String addr = SSDP.ADDRESS;
		if (HostInterface.isIPv6Address(bindAddr) == true) {
			addr = SSDP.getIPv6Address();
		}
		return open(addr, SSDP.PORT, bindAddr);
	}
	
	////////////////////////////////////////////////
	//	deviceSearch
	////////////////////////////////////////////////

	private ListenerList deviceSearchListenerList = new ListenerList();
	 	
	public void addSearchListener(SearchListener listener)
	{
		deviceSearchListenerList.add(listener);
	}		

	public void removeSearchListener(SearchListener listener)
	{
		deviceSearchListenerList.remove(listener);
	}		

	public void performSearchListener(SSDPPacket ssdpPacket)
	{
		int listenerSize = deviceSearchListenerList.size();
		for (int n=0; n<listenerSize; n++) {
			SearchListener listener = (SearchListener)deviceSearchListenerList.get(n);
			listener.deviceSearchReceived(ssdpPacket);
		}
	}		
	
	////////////////////////////////////////////////
	//	run	
	////////////////////////////////////////////////

	private Thread deviceSearchThread = null;
		
	public void run()
	{
		Thread thisThread = Thread.currentThread();
		
		while (deviceSearchThread == thisThread) {
			Thread.yield();
			SSDPPacket packet = receive();
			
			// Thanks for Mikael Hakman (04/20/05)
			if (packet == null)
				continue;
				
			if (packet.isDiscover() == true)
				performSearchListener(packet);
		}
	}
	
	public void start()
	{
		deviceSearchThread = new Thread(this,"UPnP-SSDPSearchSocket");
		deviceSearchThread.setDaemon(true);
		deviceSearchThread.start();
	}
	
	public void stop()
	{
		// Thanks for Mikael Hakman (04/20/05)
		close();
		
		deviceSearchThread = null;
	}
}

