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
*	05/08/03
*		- first revision.
*
******************************************************************/

package org.cybergarage.http;

import java.util.*;

import org.cybergarage.net.*;

public class HTTPServerList extends Vector 
{
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = 2379889735659369065L;

	public HTTPServerList() 
	{
	}

	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////

	public void addRequestListener(HTTPRequestListener listener)
	{
		int nServers = size();
		for (int n=0; n<nServers; n++) {
			HTTPServer server = getHTTPServer(n);
			server.addRequestListener(listener);
		}
	}		
	
	public HTTPServer getHTTPServer(int n)
	{
		return (HTTPServer)get(n);
	}

	////////////////////////////////////////////////
	//	open/close
	////////////////////////////////////////////////

	public void close()
	{
		int nServers = size();
		for (int n=0; n<nServers; n++) {
			HTTPServer server = getHTTPServer(n);
			server.close();
		}
	}

	public boolean open(int port) 
	{
		int nHostAddrs = HostInterface.getNHostAddresses();
		for (int n=0; n<nHostAddrs; n++) {
			String bindAddr = HostInterface.getHostAddress(n);
			HTTPServer httpServer = new HTTPServer();
			if (httpServer.open(bindAddr, port) == false) {
				close();
				clear();
				return false;
			}
			add(httpServer);
		}
		return true;
	}
	
	////////////////////////////////////////////////
	//	start/stop
	////////////////////////////////////////////////
	
	public void start()
	{
		int nServers = size();
		for (int n=0; n<nServers; n++) {
			HTTPServer server = getHTTPServer(n);
			server.start();
		}
	}

	public void stop()
	{
		int nServers = size();
		for (int n=0; n<nServers; n++) {
			HTTPServer server = getHTTPServer(n);
			server.stop();
		}
	}

}

