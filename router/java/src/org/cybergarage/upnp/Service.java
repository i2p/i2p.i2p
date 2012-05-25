/******************************************************************
*
*	CyberLink for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: Service.java
*
*	Revision;
*
*	11/28/02
*		- first revision.
*	04/12/02
*		- Holmes, Arran C <acholm@essex.ac.uk>
*		- Fixed SERVICE_ID constant instead of "serviceId".
*	06/17/03
*		- Added notifyAllStateVariables().
*	09/03/03
*		- Giordano Sassaroli <sassarol@cefriel.it>
*		- Problem : The device does not accepts request for services when control or subscription urls are absolute
*		- Error : device methods, when requests are received, search for services that have a controlUrl (or eventSubUrl) equal to the request URI
*		          but request URI must be relative, so they cannot equal absolute urls
*	09/03/03
*		- Steven Yen
*		- description: to retrieve service information based on information in URLBase and SCPDURL
*		- problem: not able to retrieve service information when URLBase is missing and SCPDURL is relative
*		- fix: modify to retrieve host information from Header's Location (required) field and update the
*		       BaseURL tag in the xml so subsequent information retrieval can be done (Steven Yen, 8.27.2003)
*		- note: 1. in the case that Header's Location field combine with SCPDURL is not able to retrieve proper 
*		          information, updating BaseURL would not hurt, since exception will be thrown with or without update.
*		        2. this problem was discovered when using PC running MS win XP with ICS enabled (gateway). 
*		          It seems that  root device xml file does not have BaseURL and SCPDURL are all relative.
*		        3. UPnP device architecture states that BaseURL is optional and SCPDURL may be relative as 
*		          specified by UPnP vendor, so MS does not seem to violate the rule.
*	10/22/03
*		- Added setActionListener().
*	01/04/04
*		- Changed about new QueryListener interface.
*	01/06/04
*		- Moved the following methods to StateVariable class.
*		  getQueryListener() 
*		  setQueryListener() 
*		  performQueryListener()
*		- Added new setQueryListener() to set a listner to all state variables.
*	07/02/04
*		- Added serviceSearchResponse().
*		- Deleted getLocationURL().
*		- Fixed announce() to set the root device URL to the LOCATION field.
*	07/31/04
*		- Changed notify() to remove the expired subscribers and not to remove the invalid response subscribers for NMPR.
*	10/29/04
*		- Fixed a bug when notify() removes the expired devices().
*	03/23/05
*		- Added loadSCPD() to load the description from memory.
*	03/30/05
*		- Added isSCPDURL().
*		- Removed setDescriptionURL() and getDescriptionURL()
*	03/31/05
*		- Added getSCPDData().
* 	04/25/05
*		- Thanks for Mikael Hakman <mhakman@dkab.net>
* 		- Changed getSCPDData() to add a XML declaration at first line.
*
******************************************************************/

package org.cybergarage.upnp;

import java.io.*;
import java.net.*;

import org.cybergarage.http.*;
import org.cybergarage.xml.*;
import org.cybergarage.util.*;

import org.cybergarage.upnp.ssdp.*;
import org.cybergarage.upnp.xml.*;
import org.cybergarage.upnp.device.*;
import org.cybergarage.upnp.control.*;
import org.cybergarage.upnp.event.*;

public class Service
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	public final static String ELEM_NAME = "service";

	////////////////////////////////////////////////
	//	Member
	////////////////////////////////////////////////

	private Node serviceNode;

	public Node getServiceNode()
	{
		return serviceNode;
	}

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////

	public Service(Node node)
	{
		serviceNode = node;
	}

	////////////////////////////////////////////////
	// Mutex
	////////////////////////////////////////////////
	
	private Mutex mutex = new Mutex();
	
	public void lock()
	{
		mutex.lock();
	}
	
	public void unlock()
	{
		mutex.unlock();
	}
	
	////////////////////////////////////////////////
	//	isServiceNode
	////////////////////////////////////////////////

	public static boolean isServiceNode(Node node)
	{
		return Service.ELEM_NAME.equals(node.getName());
	}
	
	////////////////////////////////////////////////
	//	Device/Root Node
	////////////////////////////////////////////////

	private Node getDeviceNode()
	{
		Node node = getServiceNode().getParentNode();
		if (node == null)
			return null;
		return node.getParentNode();
	}

	private Node getRootNode()
	{
		return getServiceNode().getRootNode();
	}

	////////////////////////////////////////////////
	//	Device
	////////////////////////////////////////////////

	public Device getDevice()
	{
		return new Device(getRootNode(), getDeviceNode());
	}

	public Device getRootDevice()
	{
		return getDevice().getRootDevice();
	}

	////////////////////////////////////////////////
	//	serviceType
	////////////////////////////////////////////////

	private final static String SERVICE_TYPE = "serviceType";
	
	public void setServiceType(String value)
	{
		getServiceNode().setNode(SERVICE_TYPE, value);
	}

	public String getServiceType()
	{
		return getServiceNode().getNodeValue(SERVICE_TYPE);
	}

	////////////////////////////////////////////////
	//	serviceID
	////////////////////////////////////////////////

	private final static String SERVICE_ID = "serviceId";
	
	public void setServiceID(String value)
	{
		getServiceNode().setNode(SERVICE_ID, value);
	}

	public String getServiceID()
	{
		return getServiceNode().getNodeValue(SERVICE_ID);
	}

	////////////////////////////////////////////////
	//	isURL
	////////////////////////////////////////////////
	
	// Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/03/03)
	private boolean isURL(String referenceUrl, String url)
	{
		if (referenceUrl ==null || url == null)
			return false;
		boolean ret = url.equals(referenceUrl);
		if (ret == true)
			return true;
		String relativeRefUrl = HTTP.toRelativeURL(referenceUrl, false);
		ret = url.equals(relativeRefUrl);
		if (ret == true)
			return true;
		return false;
	}
	
	////////////////////////////////////////////////
	//	SCPDURL
	////////////////////////////////////////////////

	private final static String SCPDURL = "SCPDURL";
	
	public void setSCPDURL(String value)
	{
		getServiceNode().setNode(SCPDURL, value);
	}

	public String getSCPDURL()
	{
		return getServiceNode().getNodeValue(SCPDURL);
	}

	public boolean isSCPDURL(String url)
	{
		return isURL(getSCPDURL(), url);
	}
	
	////////////////////////////////////////////////
	//	controlURL
	////////////////////////////////////////////////

	private final static String CONTROL_URL = "controlURL";
	
	public void setControlURL(String value)
	{
		getServiceNode().setNode(CONTROL_URL, value);
	}

	public String getControlURL()
	{
		return getServiceNode().getNodeValue(CONTROL_URL);
	}

	public boolean isControlURL(String url)
	{
		return isURL(getControlURL(), url);
	}

	////////////////////////////////////////////////
	//	eventSubURL
	////////////////////////////////////////////////

	private final static String EVENT_SUB_URL = "eventSubURL";
	
	public void setEventSubURL(String value)
	{
		getServiceNode().setNode(EVENT_SUB_URL, value);
	}

	public String getEventSubURL()
	{
		return getServiceNode().getNodeValue(EVENT_SUB_URL);
	}

	public boolean isEventSubURL(String url)
	{
		return isURL(getEventSubURL(), url);
	}
	
	////////////////////////////////////////////////
	//	SCPD node
	////////////////////////////////////////////////

	public boolean loadSCPD(String scpdStr) throws InvalidDescriptionException
	{
		try {
			Parser parser = UPnP.getXMLParser();
			Node scpdNode = parser.parse(scpdStr);
			if (scpdNode == null)
				return false;
			ServiceData data = getServiceData();
			data.setSCPDNode(scpdNode);
		}
		catch (ParserException e) {
			throw new InvalidDescriptionException(e);
		}
		return true;
	}

	public boolean loadSCPD(File file) throws ParserException
	{
		Parser parser = UPnP.getXMLParser();
		Node scpdNode = parser.parse(file);
		if (scpdNode == null)
			return false;
		ServiceData data = getServiceData();
		data.setSCPDNode(scpdNode);
		return true;
	}
	
	private Node getSCPDNode(URL scpdUrl) throws ParserException
	{
		Parser parser = UPnP.getXMLParser();
		return parser.parse(scpdUrl);
	}
	
	private Node getSCPDNode(File scpdFile) throws ParserException
	{
		Parser parser = UPnP.getXMLParser();
		return parser.parse(scpdFile);
	}

	private Node getSCPDNode()
	{
		ServiceData data = getServiceData();
		Node scpdNode = data.getSCPDNode();
		if (scpdNode != null)
			return scpdNode;
		
		String scpdURLStr = getSCPDURL();
		try {
			URL scpdUrl = new URL(scpdURLStr);
			scpdNode = getSCPDNode(scpdUrl);
		}
		catch (Exception e1) {
			Device rootDev = getRootDevice();
			String urlBaseStr = rootDev.getURLBase();
			// Thanks for Steven Yen (2003/09/03)
			if (urlBaseStr == null || urlBaseStr.length() <= 0) {
				String location = rootDev.getLocation();
				String locationHost = HTTP.getHost(location);
				int locationPort = HTTP.getPort(location);
				urlBaseStr = HTTP.getRequestHostURL(locationHost, locationPort);
			}
			scpdURLStr = HTTP.toRelativeURL(scpdURLStr);
			String newScpdURLStr = urlBaseStr + scpdURLStr;
			try {
				URL newScpdURL = new URL(newScpdURLStr);
				scpdNode = getSCPDNode(newScpdURL);
			}
			catch (Exception e2) {
				newScpdURLStr = HTTP.getAbsoluteURL(urlBaseStr, scpdURLStr);
				try {
					URL newScpdURL = new URL(newScpdURLStr);
					scpdNode = getSCPDNode(newScpdURL);
				}
				catch (Exception e3) {
					newScpdURLStr = rootDev.getDescriptionFilePath() + scpdURLStr;
					try {
						scpdNode = getSCPDNode(new File(newScpdURLStr));
					}
					catch (Exception e4) {
						Debug.warning(e4);
					}
				}
			}
		}

		data.setSCPDNode(scpdNode);
		
		return scpdNode;
	}

	public byte[] getSCPDData()
	{
		Node scpdNode = getSCPDNode();
		if (scpdNode == null)
			return new byte[0];
		// Thanks for Mikael Hakman (04/25/05)
		String desc = new String();
		desc += UPnP.XML_DECLARATION;
		desc += "\n";
		desc += scpdNode.toString();
		return desc.getBytes();
	}
	
	////////////////////////////////////////////////
	//	actionList
	////////////////////////////////////////////////

	public ActionList getActionList()
	{
		ActionList actionList = new ActionList();
		Node scdpNode = getSCPDNode();
		if (scdpNode == null)
			return actionList;
		Node actionListNode = scdpNode.getNode(ActionList.ELEM_NAME);
		if (actionListNode == null)
			return actionList;
		Node serviceNode = getServiceNode();
		int nNode = actionListNode.getNNodes();
		for (int n=0; n<nNode; n++) {
			Node node = actionListNode.getNode(n);
			if (Action.isActionNode(node) == false)
				continue;
			Action action = new Action(serviceNode, node);
			actionList.add(action);
		} 
		return actionList;
	}

	public Action getAction(String actionName)
	{
		ActionList actionList = getActionList();
		int nActions = actionList.size();
		for (int n=0; n<nActions; n++) {
			Action action = actionList.getAction(n);
			String name = action.getName();
			if (name == null)
				continue;
			if (name.equals(actionName) == true)
				return action;
		}
		return null;
	}
	
	////////////////////////////////////////////////
	//	serviceStateTable
	////////////////////////////////////////////////

	public ServiceStateTable getServiceStateTable()
	{
		ServiceStateTable stateTable = new ServiceStateTable();
		Node stateTableNode = getSCPDNode().getNode(ServiceStateTable.ELEM_NAME);
		if (stateTableNode == null)
			return stateTable;
		Node serviceNode = getServiceNode();
		int nNode = stateTableNode.getNNodes();
		for (int n=0; n<nNode; n++) {
			Node node = stateTableNode.getNode(n);
			if (StateVariable.isStateVariableNode(node) == false)
				continue;
			StateVariable serviceVar = new StateVariable(serviceNode, node);
			stateTable.add(serviceVar);
		} 
		return stateTable;
	}

	public StateVariable getStateVariable(String name)
	{
		ServiceStateTable stateTable = getServiceStateTable();
		int tableSize = stateTable.size();
		for (int n=0; n<tableSize; n++) {
			StateVariable var = stateTable.getStateVariable(n);
			String varName = var.getName();
			if (varName == null)
				continue;
			if (varName.equals(name) == true)
				return var;
		}
		return null;
	}
	
	public boolean hasStateVariable(String name)
	{
		return (getStateVariable(name) != null ) ? true : false;
	}

	////////////////////////////////////////////////
	//	UserData
	////////////////////////////////////////////////
	
	public boolean isService(String name)
	{
		if (name == null)
			return false;
		if (name.endsWith(getServiceType()) == true)
			return true;
		if (name.endsWith(getServiceID()) == true)
			return true;
		return false;
	}
	 
	////////////////////////////////////////////////
	//	UserData
	////////////////////////////////////////////////

	private ServiceData getServiceData()
	{
		Node node = getServiceNode();
		ServiceData userData = (ServiceData)node.getUserData();
		if (userData == null) {
			userData = new ServiceData();
			node.setUserData(userData);
			userData.setNode(node);
		}
		return userData;
	}

	////////////////////////////////////////////////
	//	Notify
	////////////////////////////////////////////////

	private String getNotifyServiceTypeNT()
	{
		return getServiceType();
	}

	private String getNotifyServiceTypeUSN()
	{
		return getDevice().getUDN() + "::" + getServiceType();
	}
		
	public void announce(String bindAddr)
	{
		// uuid:device-UUID::urn:schemas-upnp-org:service:serviceType:v 
		Device rootDev = getRootDevice();
		String devLocation = rootDev.getLocationURL(bindAddr);
		String serviceNT = getNotifyServiceTypeNT();			
		String serviceUSN = getNotifyServiceTypeUSN();

		Device dev = getDevice();
		
		SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
		ssdpReq.setServer(UPnP.getServerName());
		ssdpReq.setLeaseTime(dev.getLeaseTime());
		ssdpReq.setLocation(devLocation);
		ssdpReq.setNTS(NTS.ALIVE);
		ssdpReq.setNT(serviceNT);
		ssdpReq.setUSN(serviceUSN);

		SSDPNotifySocket ssdpSock = new SSDPNotifySocket(bindAddr);
		Device.notifyWait();
		ssdpSock.post(ssdpReq);
	}

	public void byebye(String bindAddr)
	{
		// uuid:device-UUID::urn:schemas-upnp-org:service:serviceType:v 
		
		String devNT = getNotifyServiceTypeNT();			
		String devUSN = getNotifyServiceTypeUSN();
		
		SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
		ssdpReq.setNTS(NTS.BYEBYE);
		ssdpReq.setNT(devNT);
		ssdpReq.setUSN(devUSN);

		SSDPNotifySocket ssdpSock = new SSDPNotifySocket(bindAddr);
		Device.notifyWait();
		ssdpSock.post(ssdpReq);
	}

	public boolean serviceSearchResponse(SSDPPacket ssdpPacket)
	{
		String ssdpST = ssdpPacket.getST();

		if (ssdpST == null)
			return false;
			
		Device dev = getDevice();
			
		String serviceNT = getNotifyServiceTypeNT();			
		String serviceUSN = getNotifyServiceTypeUSN();
		
		if (ST.isAllDevice(ssdpST) == true) {
			dev.postSearchResponse(ssdpPacket, serviceNT, serviceUSN);
		}
		else if (ST.isURNService(ssdpST) == true) {
			String serviceType = getServiceType();
			if (ssdpST.equals(serviceType) == true)
				dev.postSearchResponse(ssdpPacket, serviceType, serviceUSN);
		}
		
		return true;
	}
	
	////////////////////////////////////////////////
	// QueryListener
	////////////////////////////////////////////////

	public void setQueryListener(QueryListener queryListener) 
	{
		ServiceStateTable stateTable = getServiceStateTable();
		int tableSize = stateTable.size();
		for (int n=0; n<tableSize; n++) {
			StateVariable var = stateTable.getStateVariable(n);
			var.setQueryListener(queryListener);
		}
	}
	
	////////////////////////////////////////////////
	//	Subscription
	////////////////////////////////////////////////

	public SubscriberList getSubscriberList() 
	{
		return getServiceData().getSubscriberList();
	}

	public void addSubscriber(Subscriber sub) 
	{
		getSubscriberList().add(sub);
	}

	public void removeSubscriber(Subscriber sub) 
	{
		getSubscriberList().remove(sub);
	}

	public Subscriber getSubscriber(String name) 
	{
		SubscriberList subList = getSubscriberList();
		int subListCnt = subList.size();
		for (int n=0; n<subListCnt; n++) {
			Subscriber sub = subList.getSubscriber(n);
			if (sub == null)
				continue;
			String sid = sub.getSID();
			if (sid == null)
				continue;
			if (sid.equals(name) == true)
				return sub;
		}
		return null;
	}

	private boolean notify(Subscriber sub, StateVariable stateVar)
	{
		String varName = stateVar.getName();
		String value = stateVar.getValue();
		
		String host = sub.getDeliveryHost();
		int port = sub.getDeliveryPort();
		
		NotifyRequest notifyReq = new NotifyRequest();
		notifyReq.setRequest(sub, varName, value);
		
		HTTPResponse res = notifyReq.post(host, port);
		if (res.isSuccessful() == false)
			return false;
			
		sub.incrementNotifyCount();		
		
		return true;
	}

	public void notify(StateVariable stateVar)
	{
		SubscriberList subList = getSubscriberList();
		int subListCnt;
		Subscriber subs[];
		
		// Remove expired subscribers.
		subListCnt = subList.size();
		subs = new Subscriber[subListCnt];
		for (int n=0; n<subListCnt; n++)
			subs[n] = subList.getSubscriber(n);
		for (int n=0; n<subListCnt; n++) {
			Subscriber sub = subs[n];
			if (sub.isExpired() == true)
				removeSubscriber(sub);
		}
		
		// Notify to subscribers.
		subListCnt = subList.size();
		subs = new Subscriber[subListCnt];
		for (int n=0; n<subListCnt; n++)
			subs[n] = subList.getSubscriber(n);
		for (int n=0; n<subListCnt; n++) {
			Subscriber sub = subs[n];
			if (notify(sub, stateVar) == false) {
				/* Don't remove for NMPR specification.
				removeSubscriber(sub);
				*/
			}
		}
	}

	public void notifyAllStateVariables()
	{
		ServiceStateTable stateTable = getServiceStateTable();
		int tableSize = stateTable.size();
		for (int n=0; n<tableSize; n++) {
			StateVariable var = stateTable.getStateVariable(n);
			if (var.isSendEvents() == true)
				notify(var);
		}
	}

	////////////////////////////////////////////////
	// SID
	////////////////////////////////////////////////

	public String getSID() 
	{
		return getServiceData().getSID();
	}

	public void setSID(String id) 
	{
		getServiceData().setSID(id);
	}

	public void clearSID()
	{
		setSID("");
		setTimeout(0);
	}
	
	public boolean hasSID()
	{
		return StringUtil.hasData(getSID());
	}		

	public boolean isSubscribed()
	{
		return hasSID();
	}
	
	////////////////////////////////////////////////
	// Timeout
	////////////////////////////////////////////////

	public long getTimeout() 
	{
		return getServiceData().getTimeout();
	}

	public void setTimeout(long value) 
	{
		getServiceData().setTimeout(value);
	}

	////////////////////////////////////////////////
	// AcionListener
	////////////////////////////////////////////////
	
	public void setActionListener(ActionListener listener)
	{
		ActionList actionList = getActionList();
		int nActions = actionList.size();
		for (int n=0; n<nActions; n++) {
			Action action = actionList.getAction(n);
			action.setActionListener(listener);
		}
	}
}
