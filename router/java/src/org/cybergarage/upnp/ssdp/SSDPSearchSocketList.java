/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: SSDPSearchSocketList.java
*
*	Revision;
*
*	05/08/03
*		- first revision.
*	05/28/03
*		- Moved post() for SSDPSearchRequest to SSDPResponseSocket.
*		- Removed open(int).
*
******************************************************************/

package org.cybergarage.upnp.ssdp;

import java.util.*;

import org.cybergarage.net.*;

import org.cybergarage.upnp.device.*;

public class SSDPSearchSocketList extends Vector 
{
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = 4071292828166415028L;

	public SSDPSearchSocketList() 
	{
	}

	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public SSDPSearchSocket getSSDPSearchSocket(int n)
	{
		return (SSDPSearchSocket)get(n);
	}
	
	public void addSearchListener(SearchListener listener)
	{
		int nServers = size();
		for (int n=0; n<nServers; n++) {
			SSDPSearchSocket sock = getSSDPSearchSocket(n);
			sock.addSearchListener(listener);
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
			SSDPSearchSocket ssdpSearchSocket = new SSDPSearchSocket(bindAddr);
			add(ssdpSearchSocket);
		}
		return true;
	}
		
	public void close()
	{
		int nSockets = size();
		for (int n=0; n<nSockets; n++) {
			SSDPSearchSocket sock = getSSDPSearchSocket(n);
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
			SSDPSearchSocket sock = getSSDPSearchSocket(n);
			sock.start();
		}
	}

	public void stop()
	{
		int nSockets = size();
		for (int n=0; n<nSockets; n++) {
			SSDPSearchSocket sock = getSSDPSearchSocket(n);
			sock.stop();
		}
	}

}

