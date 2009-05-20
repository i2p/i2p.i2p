/******************************************************************
*
*	CyberHTTP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: HTTPServer.java
*
*	Revision;
*
*	12/12/02
*		- first revision.
*	10/20/03
*		- Improved the HTTP server using multithreading.
*	08/27/04
*		- Changed accept() to set a default timeout, HTTP.DEFAULT_TIMEOUT, to the socket.
*	
******************************************************************/

package org.cybergarage.http;

import java.io.*;
import java.net.*;

import org.cybergarage.util.*;

public class HTTPServer implements Runnable
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////

	public final static String NAME = "CyberHTTP";
	public final static String VERSION = "1.0";

	public final static int DEFAULT_PORT = 80;

	public static String getName()
	{
		String osName = System.getProperty("os.name");
		String osVer = System.getProperty("os.version");
		return osName + "/"  + osVer + " " + NAME + "/" + VERSION;
	}
	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public HTTPServer()
	{
		serverSock = null;
	}

	////////////////////////////////////////////////
	//	ServerSocket
	////////////////////////////////////////////////

	private ServerSocket serverSock = null;
	private InetAddress bindAddr = null;
	private int bindPort = 0;
	
	public ServerSocket getServerSock()
	{
		return serverSock;
	}

	public String getBindAddress()
	{
			if (bindAddr == null)
				return "";
			return bindAddr.toString();
	}

	public int getBindPort()
	{
		return bindPort;
	}
	
	////////////////////////////////////////////////
	//	open/close
	////////////////////////////////////////////////
	
	public boolean open(String addr, int port)
	{
		if (serverSock != null)
			return true;
		try {
			bindAddr = InetAddress.getByName(addr);
			bindPort = port;
			serverSock = new ServerSocket(bindPort, 0, bindAddr);
			serverSock.setSoTimeout(10*1000);
		}
		catch (IOException e) {
			Debug.warning("HTTP server open failed " + addr + " " + port, e);
			return false;
		}
		return true;
	}

	public boolean close()
	{
		if (serverSock == null)
			return true;
		try {
			serverSock.close();
			serverSock = null;
			bindAddr = null;
			bindPort = 0;
		}
		catch (Exception e) {
			Debug.warning(e);
			return false;
		}
		return true;
	}

	public Socket accept()
	{
		if (serverSock == null)
			return null;
		try {
			Socket sock = serverSock.accept();
			sock.setSoTimeout(HTTP.DEFAULT_TIMEOUT * 1000);
			return sock;
		}
		catch (Exception e) {
			return null;
		}
	}

	public boolean isOpened()
	{
		return (serverSock != null) ? true : false;
	}

	////////////////////////////////////////////////
	//	httpRequest
	////////////////////////////////////////////////

	private ListenerList httpRequestListenerList = new ListenerList();
	 	
	public void addRequestListener(HTTPRequestListener listener)
	{
		httpRequestListenerList.add(listener);
	}		

	public void removeRequestListener(HTTPRequestListener listener)
	{
		httpRequestListenerList.remove(listener);
	}		

	public void performRequestListener(HTTPRequest httpReq)
	{
		int listenerSize = httpRequestListenerList.size();
		for (int n=0; n<listenerSize; n++) {
			HTTPRequestListener listener = (HTTPRequestListener)httpRequestListenerList.get(n);
			listener.httpRequestRecieved(httpReq);
		}
	}		
	
	////////////////////////////////////////////////
	//	run	
	////////////////////////////////////////////////

	private Thread httpServerThread = null;
		
	public void run()
	{
		if (isOpened() == false)
			return;
			
		Thread thisThread = Thread.currentThread();
		
		while (httpServerThread == thisThread) {
			Thread.yield();
			Socket sock;
			try {
				//Debug.message("accept ...");
				sock = accept();
				if (sock != null)
					Debug.message("sock = " + sock.getRemoteSocketAddress());
			}
			catch (Exception e){
				Debug.warning(e);
				break;
			}
			HTTPServerThread httpServThread = new HTTPServerThread(this, sock);
			httpServThread.start(); 
			//Debug.message("httpServThread ...");
		}
	}
	
	public boolean start()
	{
		httpServerThread = new Thread(this, "UPnP-HTTPServer");
		httpServerThread.start();
		return true;
	}
	
	public boolean stop()
	{
		httpServerThread = null;
		return true;
	}
}
