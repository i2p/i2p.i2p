/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: DeviceData.java
*
*	Revision;
*
*	03/28/03
*		- first revision.
*	12/25/03
*		- Added Advertiser functions.
*
******************************************************************/

package org.cybergarage.upnp.xml;

import java.io.*;

import org.cybergarage.util.*;
import org.cybergarage.http.*;

import org.cybergarage.upnp.*;
import org.cybergarage.upnp.ssdp.*;
import org.cybergarage.upnp.device.*;

public class DeviceData extends NodeData
{
	public DeviceData() 
	{
	}

	////////////////////////////////////////////////
	// description
	////////////////////////////////////////////////

	private String descriptionURI = null; 
	private File descriptionFile = null;
	
	public File getDescriptionFile() {
		return descriptionFile;
	}

	public String getDescriptionURI() {
		return descriptionURI;
	}

	public void setDescriptionFile(File descriptionFile) {
		this.descriptionFile = descriptionFile;
	}

	public void setDescriptionURI(String descriptionURI) {
		this.descriptionURI = descriptionURI;
	}

	////////////////////////////////////////////////
	// description
	////////////////////////////////////////////////

	private String location = "";
	
	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	////////////////////////////////////////////////
	//	LeaseTime 
	////////////////////////////////////////////////

	private int leaseTime = Device.DEFAULT_LEASE_TIME;
	
	public int getLeaseTime() 
	{
		return leaseTime;
	}

	public void setLeaseTime(int val) 
	{
		leaseTime = val;
	}

	////////////////////////////////////////////////
	//	HTTPServer 
	////////////////////////////////////////////////

	private HTTPServerList httpServerList = new HTTPServerList();		

	public HTTPServerList getHTTPServerList() {
		return httpServerList;
	}

	////////////////////////////////////////////////
	//	httpPort 
	////////////////////////////////////////////////

	private int httpPort = Device.HTTP_DEFAULT_PORT;

	public int getHTTPPort() {
		return httpPort;
	}

	public void setHTTPPort(int port) {
		httpPort = port;
	}

	////////////////////////////////////////////////
	// controlActionListenerList
	////////////////////////////////////////////////

	private ListenerList controlActionListenerList = new ListenerList();

	public ListenerList getControlActionListenerList() {
		return controlActionListenerList;
	}

/*
	public void setControlActionListenerList(ListenerList controlActionListenerList) {
		this.controlActionListenerList = controlActionListenerList;
	}
*/

	////////////////////////////////////////////////
	// SSDPSearchSocket
	////////////////////////////////////////////////
	
	private SSDPSearchSocketList ssdpSearchSocketList = new SSDPSearchSocketList();
	
	public SSDPSearchSocketList getSSDPSearchSocketList() {
		return ssdpSearchSocketList;
	}

	////////////////////////////////////////////////
	// SSDPPacket
	////////////////////////////////////////////////
	
	private SSDPPacket ssdpPacket = null;
	
	public SSDPPacket getSSDPPacket() {
		return ssdpPacket;
	}

	public void setSSDPPacket(SSDPPacket packet) {
		ssdpPacket = packet;
	}

	////////////////////////////////////////////////
	// Advertiser
	////////////////////////////////////////////////

	private Advertiser advertiser = null;
	
	public void setAdvertiser(Advertiser adv) 
	{
		advertiser = adv;
	}
	
	public Advertiser getAdvertiser() 
	{
		return advertiser;
	}


}

