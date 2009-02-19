/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: HTTPServerList.java
*
*	Revision;
*
*	05/11/03
*		- first revision.
*
******************************************************************/

package org.cybergarage.upnp.ssdp;

import java.util.*;

import org.cybergarage.net.*;

import org.cybergarage.upnp.*;

public class SSDPNotifySocketList extends Vector 
{
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = -7066290881503106399L;

	public SSDPNotifySocketList() 
	{
	}

	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public SSDPNotifySocket getSSDPNotifySocket(int n)
	{
		return (SSDPNotifySocket)get(n);
	}

	////////////////////////////////////////////////
	//	ControlPoint
	////////////////////////////////////////////////

	public void setControlPoint(ControlPoint ctrlPoint)
	{
		int nSockets = size();
		for (int n=0; n<nSockets; n++) {
			SSDPNotifySocket sock = getSSDPNotifySocket(n);
			sock.setControlPoint(ctrlPoint);
		}
	}

	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public boolean open() 
	{
		int nHostAddrs = HostInterface.getNHostAddresses();
		for (int n=0; n<nHostAddrs; n++) {
			String bindAddr = HostInterface.getHostAddress(n);
			SSDPNotifySocket ssdpNotifySocket = new SSDPNotifySocket(bindAddr);
			add(ssdpNotifySocket);
		}
		return true;
	}
	
	public void close()
	{
		int nSockets = size();
		for (int n=0; n<nSockets; n++) {
			SSDPNotifySocket sock = getSSDPNotifySocket(n);
			sock.close();
		}
		clear();
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public void start()
	{
		int nSockets = size();
		for (int n=0; n<nSockets; n++) {
			SSDPNotifySocket sock = getSSDPNotifySocket(n);
			sock.start();
		}
	}

	public void stop()
	{
		int nSockets = size();
		for (int n=0; n<nSockets; n++) {
			SSDPNotifySocket sock = getSSDPNotifySocket(n);
			sock.stop();
		}
	}
	
}

