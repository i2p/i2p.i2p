/******************************************************************
*
*	CyberHTTP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: HTTPServerThread.java
*
*	Revision;
*
*	10/10/03
*		- first revision.
*	
******************************************************************/

package org.cybergarage.http;

import java.net.*;

public class HTTPServerThread extends Thread
{
	private HTTPServer httpServer;
	private Socket sock;
	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public HTTPServerThread(HTTPServer httpServer, Socket sock)
	{
		this.httpServer = httpServer;
		this.sock = sock;
		this.setDaemon(true);
	}

	////////////////////////////////////////////////
	//	run	
	////////////////////////////////////////////////

	public void run()
	{
		HTTPSocket httpSock = new HTTPSocket(sock);
		if (httpSock.open() == false)
			return;
		HTTPRequest httpReq = new HTTPRequest();
		httpReq.setSocket(httpSock);
		while (httpReq.read() == true) {
			httpServer.performRequestListener(httpReq);
			if (httpReq.isKeepAlive() == false)
				break;
		}
		httpSock.close();
	}
}
