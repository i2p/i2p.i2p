/******************************************************************
*
*	CyberLink for Java
*
*	Copyright (C) Satoshi Konno 2002-2004
*
*	File: HTTPMU.java
*
*	Revision;
*
*	11/18/02
*		- first revision.
*	09/03/03
*		- Changed to open the socket using setReuseAddress().
*	12/10/03
*		- Fixed getLocalAddress() to return a valid interface address.
*	02/28/04
*		- Added getMulticastInetAddress(), getMulticastAddress().
*	11/19/04
*		- Theo Beisch <theo.beisch@gmx.de>
*		- Changed send() to set the TTL as 4.
*	
******************************************************************/

package org.cybergarage.upnp.ssdp;

import java.net.*;
import java.util.*;

import org.cybergarage.http.*;
import org.cybergarage.util.*;

public class HTTPMUSocket
{
	////////////////////////////////////////////////
	//	Member
	////////////////////////////////////////////////

	private InetSocketAddress ssdpMultiGroup = null;
	private MulticastSocket ssdpMultiSock = null;
	private NetworkInterface ssdpMultiIf = null;
		 	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public HTTPMUSocket()
	{
	}
	
	public HTTPMUSocket(String addr, int port, String bindAddr)
	{
		open(addr, port, bindAddr);
	}

	protected void finalize()
	{
		close();
	}

	////////////////////////////////////////////////
	//	bindAddr
	////////////////////////////////////////////////

	public String getLocalAddress()
	{
		InetAddress mcastAddr = ssdpMultiGroup.getAddress();
		Enumeration addrs = ssdpMultiIf.getInetAddresses();
		while (addrs.hasMoreElements()) {
			InetAddress addr = (InetAddress)addrs.nextElement();
			if (mcastAddr instanceof Inet6Address && addr instanceof Inet6Address)
				return addr.getHostAddress();
			if (mcastAddr instanceof Inet4Address && addr instanceof Inet4Address)
				return addr.getHostAddress();
		}
		return "";
	}

	////////////////////////////////////////////////
	//	MulticastAddr
	////////////////////////////////////////////////
	
	public InetAddress getMulticastInetAddress()
	{
		return ssdpMultiGroup.getAddress();
	}
	
	public String getMulticastAddress()
	{
		return getMulticastInetAddress().getHostAddress();
	}
	
	////////////////////////////////////////////////
	//	open/close
	////////////////////////////////////////////////

	public boolean open(String addr, int port, String bindAddr)
	{
		try {
			ssdpMultiSock = new MulticastSocket(null);
			ssdpMultiSock.setReuseAddress(true);
			InetSocketAddress bindSockAddr = new InetSocketAddress(port);
			ssdpMultiSock.bind(bindSockAddr);
			ssdpMultiGroup = new InetSocketAddress(InetAddress.getByName(addr), port);
			ssdpMultiIf = NetworkInterface.getByInetAddress(InetAddress.getByName(bindAddr));
			ssdpMultiSock.joinGroup(ssdpMultiGroup, ssdpMultiIf);
		}
		catch (Exception e) {
			Debug.warning(e);
			return false;
		}
		
		return true;
	}

	public boolean close()
	{
		if (ssdpMultiSock == null)
			return true;
			
		try {
			ssdpMultiSock.leaveGroup(ssdpMultiGroup, ssdpMultiIf);
			ssdpMultiSock = null;
		}
		catch (Exception e) {
			//Debug.warning(e);
			return false;
		}
		
		return true;
	}

	////////////////////////////////////////////////
	//	send
	////////////////////////////////////////////////

	public boolean send(String msg, String bindAddr, int bindPort)
	{
		try {
			MulticastSocket msock;
			if ((bindAddr) != null && (0 < bindPort)) {
				msock = new MulticastSocket(null);
				msock.bind(new InetSocketAddress(bindAddr, bindPort));
			}
			else 
				msock = new MulticastSocket();
			DatagramPacket dgmPacket = new DatagramPacket(msg.getBytes(), msg.length(), ssdpMultiGroup);
			// Thnaks for Tho Beisch (11/09/04)
			msock.setTimeToLive(4);
			msock.send(dgmPacket);
			msock.close();
		}
		catch (Exception e) {
			Debug.warning(e);
			return false;
		}
		return true;
	}

	public boolean send(String msg)
	{
		return send(msg, null, -1);
	}

	////////////////////////////////////////////////
	//	post (HTTPRequest)
	////////////////////////////////////////////////

	public boolean post(HTTPRequest req, String bindAddr, int bindPort)
	{
		return send(req.toString(), bindAddr, bindPort);
	}

	public boolean post(HTTPRequest req)
	{
		return send(req.toString(), null, -1);
	}

	////////////////////////////////////////////////
	//	reveive
	////////////////////////////////////////////////

	public SSDPPacket receive()
	{
		byte ssdvRecvBuf[] = new byte[SSDP.RECV_MESSAGE_BUFSIZE];
 		SSDPPacket recvPacket = new SSDPPacket(ssdvRecvBuf, ssdvRecvBuf.length);
		recvPacket.setLocalAddress(getLocalAddress());
 		try {
			ssdpMultiSock.receive(recvPacket.getDatagramPacket());
			recvPacket.setTimeStamp(System.currentTimeMillis());
		}
		catch (Exception e) {
			//Debug.warning(e);
		}
 		return recvPacket;
	}
}

