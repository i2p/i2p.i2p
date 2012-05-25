/******************************************************************
*
*	CyberLink for Java
*
*	Copyright (C) Satoshi Konno 2002-2004
*
*	File: Device.java
*
*	Revision:
*
*	11/28/02
*		- first revision.
*	02/26/03
*		- URLBase is updated automatically.
* 		- Description of a root device is returned from the XML node tree.
*	05/13/03
*		- URLBase is updated when the request is received.
*		- Changed to create socket threads each local interfaces.
*		  (HTTP, SSDPSearch)
*	06/17/03
*		- Added notify all state variables when a new subscription is received.
*	06/18/03
*		- Fixed a announce bug when the bind address is null on J2SE v 1.4.1_02 and Redhat 9.
*	09/02/03
*		- Giordano Sassaroli <sassarol@cefriel.it>
*		- Problem : bad request response sent even with successful subscriptions
*		- Error : a return statement is missing in the httpRequestRecieved method
*	10/21/03
*		- Updated a udn field by a original uuid.
*	10/22/03
*		- Added setActionListener().
*		- Added setQueryListener().
*	12/12/03
*		- Added a static() to initialize UPnP class.
*	12/25/03
*		- Added advertiser functions.
*	01/05/04
*		- Added isExpired().
*	03/23/04
*		- Oliver Newell <newell@media-rush.com>
*		- Changed to update the UDN only when the field is null.
*	04/21/04
*		- Added isDeviceType().
*	06/18/04
*		- Added setNMPRMode() and isNMPRMode().
*		- Changed getDescriptionData() to update only when the NMPR mode is false.
*	06/21/04
*		- Changed start() to send a bye-bye before the announce.
*		- Changed annouce(), byebye() and deviceSearchReceived() to send the SSDP
*		  messsage four times when the NMPR and the Wireless mode are true.
*	07/02/04
*		- Fixed announce() and byebye() to send the upnp::rootdevice message despite embedded devices.
*		- Fixed getRootNode() to return the root node when the device is embedded.
*	07/24/04
*		- Thanks for Stefano Lenzi <kismet-sl@users.sourceforge.net>
*		- Added getParentDevice().
*	10/20/04 
*		- Brent Hills <bhills@openshores.com>
*		- Changed postSearchResponse() to add MYNAME header.
*	11/19/04
*		- Theo Beisch <theo.beisch@gmx.de>
*		- Added getStateVariable(String serviceType, String name).
*	03/22/05
*		- Changed httpPostRequestRecieved() to return the bad request when the post request isn't the soap action.
*	03/23/05
*		- Added loadDescription(String) to load the description from memory.
*	03/30/05
*		- Added getDeviceByDescriptionURI().
*		- Added getServiceBySCPDURL().
*	03/31/05
*		- Changed httpGetRequestRecieved() to return the description stream using
*		  Device::getDescriptionData() and Service::getSCPDData() at first.
*	04/25/05
*		- Thanks for Mikael Hakman <mhakman@dkab.net>
*		  Changed announce() and byebye() to close the socket after the posting.
*	04/25/05
*		- Thanks for Mikael Hakman <mhakman@dkab.net>
*		  Changed deviceSearchResponse() answer with USN:UDN::<device-type> when request ST is device type.
* 	04/25/05
*		- Thanks for Mikael Hakman <mhakman@dkab.net>
* 		- Changed getDescriptionData() to add a XML declaration at first line.
* 	04/25/05
*		- Thanks for Mikael Hakman <mhakman@dkab.net>
*		- Added a new setActionListener() and serQueryListner() to include the sub devices. 
* 
******************************************************************/

package org.cybergarage.upnp;

import java.net.*;
import java.io.*;
import java.util.*;

import org.cybergarage.net.*;
import org.cybergarage.http.*;
import org.cybergarage.util.*;
import org.cybergarage.xml.*;
import org.cybergarage.soap.*;

import org.cybergarage.upnp.ssdp.*;
import org.cybergarage.upnp.device.*;
import org.cybergarage.upnp.control.*;
import org.cybergarage.upnp.event.*;
import org.cybergarage.upnp.xml.*;

public class Device implements org.cybergarage.http.HTTPRequestListener, SearchListener
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	public final static String ELEM_NAME = "device";
	public final static String UPNP_ROOTDEVICE = "upnp:rootdevice";

	public final static int DEFAULT_STARTUP_WAIT_TIME = 1000;
	public final static int DEFAULT_DISCOVERY_WAIT_TIME = 300;
	public final static int DEFAULT_LEASE_TIME = 30 * 60;

	public final static int HTTP_DEFAULT_PORT = 4004;

	public final static String DEFAULT_DESCRIPTION_URI = "/description.xml";
	
	////////////////////////////////////////////////
	//	Member
	////////////////////////////////////////////////

	private Node rootNode;
	private Node deviceNode;

	public Node getRootNode()
	{
		if (rootNode != null)
			return rootNode;
		if (deviceNode == null)
			return null;
		return deviceNode.getRootNode();
	}

	public Node getDeviceNode()
	{
		return deviceNode;
	}

	public void setRootNode(Node node)
	{
		rootNode = node;
	}

	public void setDeviceNode(Node node)
	{
		deviceNode = node;
	}
				
	////////////////////////////////////////////////
	//	Initialize
	////////////////////////////////////////////////
	
	static 
	{
		UPnP.initialize();
	}
	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////

	public Device(Node root, Node device)
	{
		rootNode = root;
		deviceNode = device;
		setUUID(UPnP.createUUID());
		setWirelessMode(false);
	}

	public Device()
	{
		this(null, null);
	}
	
	public Device(Node device)
	{
		this(null, device);
	}

	public Device(File descriptionFile) throws InvalidDescriptionException
	{
		this(null, null);
		loadDescription(descriptionFile);
	}

	public Device(String descriptionFileName) throws InvalidDescriptionException
	{
		this(new File(descriptionFileName));
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
	//	NMPR
	////////////////////////////////////////////////
	
	public void setNMPRMode(boolean flag)
	{
		Node devNode = getDeviceNode();
		if (devNode == null)
			return;
		if (flag == true) {
			devNode.setNode(UPnP.INMPR03, UPnP.INMPR03_VERSION);
			devNode.removeNode(Device.URLBASE_NAME);
		}
		else {
			devNode.removeNode(UPnP.INMPR03);
		}
	}

	public boolean isNMPRMode()
	{
		Node devNode = getDeviceNode();
		if (devNode == null)
			return false;
		return (devNode.getNode(UPnP.INMPR03) != null) ? true : false;
	}
	
	////////////////////////////////////////////////
	//	Wireless
	////////////////////////////////////////////////
	
	private boolean wirelessMode;
	
	public void setWirelessMode(boolean flag)
	{
		wirelessMode = flag;
	}

	public boolean isWirelessMode()
	{
		return wirelessMode;
	}

	public int getSSDPAnnounceCount()
	{
		if (isNMPRMode() == true && isWirelessMode() == true)
			return UPnP.INMPR03_DISCOVERY_OVER_WIRELESS_COUNT;
		return 1;
	}

	////////////////////////////////////////////////
	//	Device UUID
	////////////////////////////////////////////////

	private String devUUID;
	
	private void setUUID(String uuid)
	{
		devUUID = uuid;
	}
	
	private String getUUID() 
	{
		return devUUID;
	}
	
	private void updateUDN()
	{
		setUDN("uuid:" + getUUID());	
	}
	
	////////////////////////////////////////////////
	//	Root Device
	////////////////////////////////////////////////
	
	public Device getRootDevice()
	{
		Node rootNode = getRootNode();
		if (rootNode == null)
			return null;
		Node devNode = rootNode.getNode(Device.ELEM_NAME);
		if (devNode == null)
			return null;
		return new Device(rootNode, devNode);
	}

	////////////////////////////////////////////////
	//	Parent Device
	////////////////////////////////////////////////
	
	// Thanks for Stefano Lenzi (07/24/04)

	public Device getParentDevice()
	{ 
		if(isRootDevice())
			return null;
		Node devNode = getDeviceNode();
		//<device><deviceList><device>
		devNode = devNode.getParentNode().getParentNode().getNode(Device.ELEM_NAME);
		return new Device(devNode);
	}

	////////////////////////////////////////////////
	//	UserData
	////////////////////////////////////////////////

	private DeviceData getDeviceData()
	{
		Node node = getDeviceNode();
		DeviceData userData = (DeviceData)node.getUserData();
		if (userData == null) {
			userData = new DeviceData();
			node.setUserData(userData);
			userData.setNode(node);
		}
		return userData;
	}
	
	////////////////////////////////////////////////
	//	Description
	////////////////////////////////////////////////

	private void setDescriptionFile(File file)
	{
		getDeviceData().setDescriptionFile(file);
	}

	public File getDescriptionFile()
	{
		return getDeviceData().getDescriptionFile();
	}

	private void setDescriptionURI(String uri)
	{
		getDeviceData().setDescriptionURI(uri);
	}

	private String getDescriptionURI()
	{
		return getDeviceData().getDescriptionURI();
	}

	private boolean isDescriptionURI(String uri)
	{
		String descriptionURI = getDescriptionURI();
		if (uri == null || descriptionURI == null)
			return false;
		return descriptionURI.equals(uri);
	}

	public String getDescriptionFilePath()
	{
		File descriptionFile = getDescriptionFile();
		if (descriptionFile == null)
			return "";
		return descriptionFile.getAbsoluteFile().getParent();
	}

	public boolean loadDescription(String descString) throws InvalidDescriptionException
	{
		try {
			Parser parser = UPnP.getXMLParser();
			rootNode = parser.parse(descString);
			if (rootNode == null)
				throw new InvalidDescriptionException(Description.NOROOT_EXCEPTION);
			deviceNode = rootNode.getNode(Device.ELEM_NAME);
			if (deviceNode == null)
				throw new InvalidDescriptionException(Description.NOROOTDEVICE_EXCEPTION);
		}
		catch (ParserException e) {
			throw new InvalidDescriptionException(e);
		}
		
		if (initializeLoadedDescription() == false)
			return false;

		setDescriptionFile(null);
				
		return true;
	}
	
	public boolean loadDescription(File file) throws InvalidDescriptionException
	{
		try {
			Parser parser = UPnP.getXMLParser();
			rootNode = parser.parse(file);
			if (rootNode == null)
				throw new InvalidDescriptionException(Description.NOROOT_EXCEPTION, file);
			deviceNode = rootNode.getNode(Device.ELEM_NAME);
			if (deviceNode == null)
				throw new InvalidDescriptionException(Description.NOROOTDEVICE_EXCEPTION, file);
		}
		catch (ParserException e) {
			throw new InvalidDescriptionException(e);
		}
		
		if (initializeLoadedDescription() == false)
			return false;

		setDescriptionFile(file);
				
		return true;
	}

	private boolean initializeLoadedDescription()
	{
		setDescriptionURI(DEFAULT_DESCRIPTION_URI);
		setLeaseTime(DEFAULT_LEASE_TIME);
		setHTTPPort(HTTP_DEFAULT_PORT);

		// Thanks for Oliver Newell (03/23/04)
		if (hasUDN() == false)
			updateUDN();
				
		return true;
	}
	
	////////////////////////////////////////////////
	//	isDeviceNode
	////////////////////////////////////////////////

	public static boolean isDeviceNode(Node node)
	{
		return Device.ELEM_NAME.equals(node.getName());
	}
	
	////////////////////////////////////////////////
	//	Root Device
	////////////////////////////////////////////////

	public boolean isRootDevice()
	{
		return (getRootNode() != null) ? true : false;
	}
	
	////////////////////////////////////////////////
	//	Root Device
	////////////////////////////////////////////////

	public void setSSDPPacket(SSDPPacket packet)
	{
		getDeviceData().setSSDPPacket(packet);
	}

	public SSDPPacket getSSDPPacket()
	{
		if (isRootDevice() == false)
			return null;
		return getDeviceData().getSSDPPacket();
	}
	
	////////////////////////////////////////////////
	//	Location 
	////////////////////////////////////////////////

	public void setLocation(String value)
	{
		getDeviceData().setLocation(value);
	}

	public String getLocation()
	{
		SSDPPacket packet = getSSDPPacket();
		if (packet != null)
			return packet.getLocation();
		return getDeviceData().getLocation();
	}

	////////////////////////////////////////////////
	//	LeaseTime 
	////////////////////////////////////////////////

	public void setLeaseTime(int value)
	{
		getDeviceData().setLeaseTime(value);
		Advertiser adv = getAdvertiser();
		if (adv != null) {
			announce();
			adv.restart();
		}
	}

	public int getLeaseTime()
	{
		SSDPPacket packet = getSSDPPacket();
		if (packet != null)
			return packet.getLeaseTime();	
		return getDeviceData().getLeaseTime();
	}

	////////////////////////////////////////////////
	//	TimeStamp 
	////////////////////////////////////////////////

	public long getTimeStamp()
	{
		SSDPPacket packet = getSSDPPacket();
		if (packet != null)
			return packet.getTimeStamp();		
		return 0;
	}

	public long getElapsedTime()
	{
		return (System.currentTimeMillis() - getTimeStamp()) / 1000;
	}

	public boolean isExpired()
	{
		long elipsedTime = getElapsedTime();
		long leaseTime = getLeaseTime() + UPnP.DEFAULT_EXPIRED_DEVICE_EXTRA_TIME;
		if (leaseTime < elipsedTime)
			return true;
		return false;
	}
	
	////////////////////////////////////////////////
	//	URL Base
	////////////////////////////////////////////////

	private final static String URLBASE_NAME = "URLBase";
	
	private void setURLBase(String value)
	{
		if (isRootDevice() == true) {
			Node node = getRootNode().getNode(URLBASE_NAME);
			if (node != null) {
				node.setValue(value);
				return;
			}
			node = new Node(URLBASE_NAME);
			node.setValue(value);
			int index = 1;
			if (getRootNode().hasNodes() == false)
				index = 1;
			getRootNode().insertNode(node, index);
		}
	}

	private void updateURLBase(String host)
	{
		String urlBase = HostInterface.getHostURL(host, getHTTPPort(), "");
		setURLBase(urlBase);
	}
  
	public String getURLBase()
	{
		if (isRootDevice() == true)
			return getRootNode().getNodeValue(URLBASE_NAME);
		return "";
	}

	////////////////////////////////////////////////
	//	deviceType
	////////////////////////////////////////////////

	private final static String DEVICE_TYPE = "deviceType";
	
	public void setDeviceType(String value)
	{
		getDeviceNode().setNode(DEVICE_TYPE, value);
	}

	public String getDeviceType()
	{
		return getDeviceNode().getNodeValue(DEVICE_TYPE);
	}

	public boolean isDeviceType(String value)
	{
		if (value == null)
			return false;
		return value.equals(getDeviceType());
	}

	////////////////////////////////////////////////
	//	friendlyName
	////////////////////////////////////////////////

	private final static String FRIENDLY_NAME = "friendlyName";
	
	public void setFriendlyName(String value)
	{
		getDeviceNode().setNode(FRIENDLY_NAME, value);
	}

	public String getFriendlyName()
	{
		return getDeviceNode().getNodeValue(FRIENDLY_NAME);
	}

	////////////////////////////////////////////////
	//	manufacture
	////////////////////////////////////////////////

	private final static String MANUFACTURE = "manufacture";
	
	public void setManufacture(String value)
	{
		getDeviceNode().setNode(MANUFACTURE, value);
	}

	public String getManufacture()
	{
		return getDeviceNode().getNodeValue(MANUFACTURE);
	}

	////////////////////////////////////////////////
	//	manufactureURL
	////////////////////////////////////////////////

	private final static String MANUFACTURE_URL = "manufactureURL";
	
	public void setManufactureURL(String value)
	{
		getDeviceNode().setNode(MANUFACTURE_URL, value);
	}

	public String getManufactureURL()
	{
		return getDeviceNode().getNodeValue(MANUFACTURE_URL);
	}

	////////////////////////////////////////////////
	//	modelDescription
	////////////////////////////////////////////////

	private final static String MODEL_DESCRIPTION = "modelDescription";
	
	public void setModelDescription(String value)
	{
		getDeviceNode().setNode(MODEL_DESCRIPTION, value);
	}

	public String getModelDescription()
	{
		return getDeviceNode().getNodeValue(MODEL_DESCRIPTION);
	}

	////////////////////////////////////////////////
	//	modelName
	////////////////////////////////////////////////

	private final static String MODEL_NAME = "modelName";
	
	public void setModelName(String value)
	{
		getDeviceNode().setNode(MODEL_NAME, value);
	}

	public String getModelName()
	{
		return getDeviceNode().getNodeValue(MODEL_NAME);
	}

	////////////////////////////////////////////////
	//	modelNumber
	////////////////////////////////////////////////

	private final static String MODEL_NUMBER = "modelNumber";
	
	public void setModelNumber(String value)
	{
		getDeviceNode().setNode(MODEL_NUMBER, value);
	}

	public String getModelNumber()
	{
		return getDeviceNode().getNodeValue(MODEL_NUMBER);
	}

	////////////////////////////////////////////////
	//	modelURL
	////////////////////////////////////////////////

	private final static String MODEL_URL = "modelURL";
	
	public void setModelURL(String value)
	{
		getDeviceNode().setNode(MODEL_URL, value);
	}

	public String getModelURL()
	{
		return getDeviceNode().getNodeValue(MODEL_URL);
	}

	////////////////////////////////////////////////
	//	serialNumber
	////////////////////////////////////////////////

	private final static String SERIAL_NUMBER = "serialNumber";
	
	public void setSerialNumber(String value)
	{
		getDeviceNode().setNode(SERIAL_NUMBER, value);
	}

	public String getSerialNumber()
	{
		return getDeviceNode().getNodeValue(SERIAL_NUMBER);
	}

	////////////////////////////////////////////////
	//	UDN
	////////////////////////////////////////////////

	private final static String UDN = "UDN";
	
	public void setUDN(String value)
	{
		getDeviceNode().setNode(UDN, value);
	}

	public String getUDN()
	{
		return getDeviceNode().getNodeValue(UDN);
	}

	public boolean hasUDN()
	{
		String udn = getUDN();
		if (udn == null || udn.length() <= 0)
			return false;
		return true;
	}
	
	////////////////////////////////////////////////
	//	UPC
	////////////////////////////////////////////////

	private final static String UPC = "UPC";
	
	public void setUPC(String value)
	{
		getDeviceNode().setNode(UPC, value);
	}

	public String getUPC()
	{
		return getDeviceNode().getNodeValue(UPC);
	}

	////////////////////////////////////////////////
	//	presentationURL
	////////////////////////////////////////////////

	private final static String presentationURL = "presentationURL";
	
	public void setPresentationURL(String value)
	{
		getDeviceNode().setNode(presentationURL, value);
	}

	public String getPresentationURL()
	{
		return getDeviceNode().getNodeValue(presentationURL);
	}

	////////////////////////////////////////////////
	//	deviceList
	////////////////////////////////////////////////

	public DeviceList getDeviceList()
	{
		DeviceList devList = new DeviceList();
		Node devListNode = getDeviceNode().getNode(DeviceList.ELEM_NAME);
		if (devListNode == null)
			return devList;
		int nNode = devListNode.getNNodes();
		for (int n=0; n<nNode; n++) {
			Node node = devListNode.getNode(n);
			if (Device.isDeviceNode(node) == false)
				continue;
			Device dev = new Device(node);
			devList.add(dev);
		} 
		return devList;
	}

	public boolean isDevice(String name)
	{
		if (name == null)
			return false;
		if (name.endsWith(getUDN()) == true)
			return true;
		if (name.equals(getFriendlyName()) == true)
			return true;
		if (name.endsWith(getDeviceType()) == true)
			return true;
		return false;
	}
	
	public Device getDevice(String name)
	{
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			if (dev.isDevice(name) == true)
				return dev;
			Device cdev = dev.getDevice(name);
			if (cdev != null)
				return cdev;
		}
		return null;
	}
	
	public Device getDeviceByDescriptionURI(String uri)
	{
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			if (dev.isDescriptionURI(uri) == true)
				return dev;
			Device cdev = dev.getDeviceByDescriptionURI(uri);
			if (cdev != null)
				return cdev;
		}
		return null;
	}
	
	////////////////////////////////////////////////
	//	serviceList
	////////////////////////////////////////////////

	public ServiceList getServiceList()
	{
		ServiceList serviceList = new ServiceList();
		Node serviceListNode = getDeviceNode().getNode(ServiceList.ELEM_NAME);
		if (serviceListNode == null)
			return serviceList;
		int nNode = serviceListNode.getNNodes();
		for (int n=0; n<nNode; n++) {
			Node node = serviceListNode.getNode(n);
			if (Service.isServiceNode(node) == false)
				continue;
			Service service = new Service(node);
			serviceList.add(service);
		} 
		return serviceList;
	}

	public Service getService(String name)
	{
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			if (service.isService(name) == true)
				return service;
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			Service service = dev.getService(name);
			if (service != null)
				return service;
		}
		
		return null;
	}

	public Service getServiceBySCPDURL(String searchUrl)
	{
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			if (service.isSCPDURL(searchUrl) == true)
				return service;
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			Service service = dev.getServiceBySCPDURL(searchUrl);
			if (service != null)
				return service;
		}
		
		return null;
	}

	public Service getServiceByControlURL(String searchUrl)
	{
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			if (service.isControlURL(searchUrl) == true)
				return service;
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			Service service = dev.getServiceByControlURL(searchUrl);
			if (service != null)
				return service;
		}
		
		return null;
	}

	public Service getServiceByEventSubURL(String searchUrl)
	{
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			if (service.isEventSubURL(searchUrl) == true)
				return service;
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			Service service = dev.getServiceByEventSubURL(searchUrl);
			if (service != null)
				return service;
		}
		
		return null;
	}

	public Service getSubscriberService(String uuid)
	{
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			String sid = service.getSID();
			if (uuid.equals(sid) == true)
				return service;
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			Service service = dev.getSubscriberService(uuid);
			if (service != null)
				return service;
		}
		
		return null;
	}

	////////////////////////////////////////////////
	//	StateVariable
	////////////////////////////////////////////////

	public StateVariable getStateVariable(String serviceType, String name)
	{
		if (serviceType == null && name == null)
			return null;
		
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			// Thanks for Theo Beisch (11/09/04)
			if (serviceType != null) {
				if (service.getServiceType().equals(serviceType) == false)
					continue;
			}
			StateVariable stateVar = service.getStateVariable(name);
			if (stateVar != null)
				return stateVar;
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			StateVariable stateVar = dev.getStateVariable(serviceType, name);
			if (stateVar != null)
				return stateVar;
		}
		
		return null;
	}

	public StateVariable getStateVariable(String name)
	{
		return getStateVariable(null, name);
	}
	
	////////////////////////////////////////////////
	//	Action
	////////////////////////////////////////////////

	public Action getAction(String name)
	{
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			ActionList actionList = service.getActionList();
			int actionCnt = actionList.size();
			for (int i=0; i<actionCnt; i++) {
				Action action = (Action)actionList.getAction(i);
				String actionName = action.getName();
				if (actionName == null)
					continue;
				if (actionName.equals(name) == true)
					return action;
			}
		}
		
		DeviceList devList = getDeviceList();
		int devCnt = devList.size();
		for (int n=0; n<devCnt; n++) {
			Device dev = devList.getDevice(n);
			Action action = dev.getAction(name);
			if (action != null)
				return action;
		}
		
		return null;
	}

	////////////////////////////////////////////////
	//	iconList
	////////////////////////////////////////////////

	public IconList getIconList()
	{
		IconList iconList = new IconList();
		Node iconListNode = getDeviceNode().getNode(IconList.ELEM_NAME);
		if (iconListNode == null)
			return iconList;
		int nNode = iconListNode.getNNodes();
		for (int n=0; n<nNode; n++) {
			Node node = iconListNode.getNode(n);
			if (Icon.isIconNode(node) == false)
				continue;
			Icon icon = new Icon(node);
			iconList.add(icon);
		} 
		return iconList;
	}
	
	public Icon getIcon(int n)
	{
		IconList iconList = getIconList();
		if (n < 0 && (iconList.size()-1) < n)
			return null;
		return iconList.getIcon(n);
	}

	////////////////////////////////////////////////
	//	Notify
	////////////////////////////////////////////////

	public String getLocationURL(String host)
	{
		return HostInterface.getHostURL(host, getHTTPPort(), getDescriptionURI());
	}

	private String getNotifyDeviceNT()
	{
		if (isRootDevice() == false)
			return getUDN();			
		return UPNP_ROOTDEVICE;
	}

	private String getNotifyDeviceUSN()
	{
		if (isRootDevice() == false)
			return getUDN();			
		return getUDN() + "::" + UPNP_ROOTDEVICE;
	}

	private String getNotifyDeviceTypeNT()
	{
		return getDeviceType();
	}

	private String getNotifyDeviceTypeUSN()
	{
		return getUDN() + "::" + getDeviceType();
	}
	
	public final static void notifyWait()
	{
		TimerUtil.waitRandom(DEFAULT_DISCOVERY_WAIT_TIME);
	}
		
	public void announce(String bindAddr)
	{
		String devLocation = getLocationURL(bindAddr);
		
		SSDPNotifySocket ssdpSock = new SSDPNotifySocket(bindAddr);

		SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
		ssdpReq.setServer(UPnP.getServerName());
		ssdpReq.setLeaseTime(getLeaseTime());
		ssdpReq.setLocation(devLocation);
		ssdpReq.setNTS(NTS.ALIVE);
		
		// uuid:device-UUID(::upnp:rootdevice)* 
		if (isRootDevice() == true) {
			String devNT = getNotifyDeviceNT();			
			String devUSN = getNotifyDeviceUSN();
			ssdpReq.setNT(devNT);
			ssdpReq.setUSN(devUSN);
			ssdpSock.post(ssdpReq);
		}
		
		// uuid:device-UUID::urn:schemas-upnp-org:device:deviceType:v 
		String devNT = getNotifyDeviceTypeNT();			
		String devUSN = getNotifyDeviceTypeUSN();
		ssdpReq.setNT(devNT);
		ssdpReq.setUSN(devUSN);
		ssdpSock.post(ssdpReq);
		
		// Thanks for Mikael Hakman (04/25/05)
		ssdpSock.close();
		
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			service.announce(bindAddr);
		}

		DeviceList childDeviceList = getDeviceList();
		int childDeviceCnt = childDeviceList.size();
		for (int n=0; n<childDeviceCnt; n++) {
			Device childDevice = childDeviceList.getDevice(n);
			childDevice.announce(bindAddr);
		}
	}

	public void announce()
	{
		notifyWait();
		
		int nHostAddrs = HostInterface.getNHostAddresses();
		for (int n=0; n<nHostAddrs; n++) {
			String bindAddr = HostInterface.getHostAddress(n);
			if (bindAddr == null || bindAddr.length() <= 0)
				continue;
			int ssdpCount = getSSDPAnnounceCount();
			for (int i=0; i<ssdpCount; i++)
				announce(bindAddr);
		}
	}
	
	public void byebye(String bindAddr)
	{
		SSDPNotifySocket ssdpSock = new SSDPNotifySocket(bindAddr);
		
		SSDPNotifyRequest ssdpReq = new SSDPNotifyRequest();
		ssdpReq.setNTS(NTS.BYEBYE);
		
		// uuid:device-UUID(::upnp:rootdevice)* 
		if (isRootDevice() == true) {
			String devNT = getNotifyDeviceNT();			
			String devUSN = getNotifyDeviceUSN();
			ssdpReq.setNT(devNT);
			ssdpReq.setUSN(devUSN);
			ssdpSock.post(ssdpReq);
		}
		
		// uuid:device-UUID::urn:schemas-upnp-org:device:deviceType:v 
		String devNT = getNotifyDeviceTypeNT();			
		String devUSN = getNotifyDeviceTypeUSN();
		ssdpReq.setNT(devNT);
		ssdpReq.setUSN(devUSN);
		ssdpSock.post(ssdpReq);

		// Thanks for Mikael Hakman (04/25/05)
		ssdpSock.close();
		
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			service.byebye(bindAddr);
		}

		DeviceList childDeviceList = getDeviceList();
		int childDeviceCnt = childDeviceList.size();
		for (int n=0; n<childDeviceCnt; n++) {
			Device childDevice = childDeviceList.getDevice(n);
			childDevice.byebye(bindAddr);
		}
	}

	public void byebye()
	{
		int nHostAddrs = HostInterface.getNHostAddresses();
		for (int n=0; n<nHostAddrs; n++) {
			String bindAddr = HostInterface.getHostAddress(n);
			if (bindAddr == null || bindAddr.length() <= 0)
				continue;
			int ssdpCount = getSSDPAnnounceCount();
			for (int i=0; i<ssdpCount; i++)
				byebye(bindAddr);
		}
	}

	////////////////////////////////////////////////
	//	Search
	////////////////////////////////////////////////

	public boolean postSearchResponse(SSDPPacket ssdpPacket, String st, String usn)
	{
		String localAddr = ssdpPacket.getLocalAddress();
		Device rootDev = getRootDevice();
		String rootDevLocation = rootDev.getLocationURL(localAddr);
		
		SSDPSearchResponse ssdpRes = new SSDPSearchResponse();
		ssdpRes.setLeaseTime(getLeaseTime());
		ssdpRes.setDate(Calendar.getInstance());
		ssdpRes.setST(st);
		ssdpRes.setUSN(usn);
		ssdpRes.setLocation(rootDevLocation);
		// Thanks for Brent Hills (10/20/04)
		ssdpRes.setMYNAME(getFriendlyName());

		int mx = ssdpPacket.getMX();
		TimerUtil.waitRandom(mx * 1000);
		
		String remoteAddr = ssdpPacket.getRemoteAddress();
		int remotePort = ssdpPacket.getRemotePort();
		SSDPSearchResponseSocket ssdpResSock = new SSDPSearchResponseSocket();
		if (Debug.isOn() == true)
			ssdpRes.print();
		int ssdpCount = getSSDPAnnounceCount();
		for (int i=0; i<ssdpCount; i++)
			ssdpResSock.post(remoteAddr, remotePort, ssdpRes);
			
		return true;
	}
	
	public void deviceSearchResponse(SSDPPacket ssdpPacket)
	{
		String ssdpST = ssdpPacket.getST();

		if (ssdpST == null)
			return;

		boolean isRootDevice = isRootDevice();
		
		String devUSN = getUDN();
		if (isRootDevice == true)
			devUSN += "::" + USN.ROOTDEVICE;
			
		if (ST.isAllDevice(ssdpST) == true) {
			String devNT = getNotifyDeviceNT();			
			int repeatCnt = (isRootDevice == true) ? 3 : 2;
			for (int n=0; n<repeatCnt; n++)
				postSearchResponse(ssdpPacket, devNT, devUSN);
		}
		else if (ST.isRootDevice(ssdpST) == true) {
			if (isRootDevice == true)
				postSearchResponse(ssdpPacket, ST.ROOT_DEVICE, devUSN);
		}
		else if (ST.isUUIDDevice(ssdpST) == true) {
			String devUDN = getUDN();
			if (ssdpST.equals(devUDN) == true)
				postSearchResponse(ssdpPacket, devUDN, devUSN);
		}
		else if (ST.isURNDevice(ssdpST) == true) {
			String devType= getDeviceType();
			if (ssdpST.equals(devType) == true) {
				// Thanks for Mikael Hakman (04/25/05)
				devUSN = getUDN() + "::" + devType;
				postSearchResponse(ssdpPacket, devType, devUSN);
			}
		}
		
		ServiceList serviceList = getServiceList();
		int serviceCnt = serviceList.size();
		for (int n=0; n<serviceCnt; n++) {
			Service service = serviceList.getService(n);
			service.serviceSearchResponse(ssdpPacket);
		}
		
		DeviceList childDeviceList = getDeviceList();
		int childDeviceCnt = childDeviceList.size();
		for (int n=0; n<childDeviceCnt; n++) {
			Device childDevice = childDeviceList.getDevice(n);
			childDevice.deviceSearchResponse(ssdpPacket);
		}
	}
	
	public void deviceSearchReceived(SSDPPacket ssdpPacket)
	{
		deviceSearchResponse(ssdpPacket);
	}
	
	////////////////////////////////////////////////
	//	HTTP Server	
	////////////////////////////////////////////////

	public void setHTTPPort(int port)
	{
		getDeviceData().setHTTPPort(port);
	}
	
	public int getHTTPPort()
	{
		return getDeviceData().getHTTPPort();
	}

	public void httpRequestRecieved(HTTPRequest httpReq)
	{
		if (Debug.isOn() == true)
			httpReq.print();
	
		if (httpReq.isGetRequest() == true) {
			httpGetRequestRecieved(httpReq);
			return;
		}
		if (httpReq.isPostRequest() == true) {
			httpPostRequestRecieved(httpReq);
			return;
		}

		if (httpReq.isSubscribeRequest() == true || httpReq.isUnsubscribeRequest() == true) {
			SubscriptionRequest subReq = new SubscriptionRequest(httpReq);
			deviceEventSubscriptionRecieved(subReq);
			return;
		}

		httpReq.returnBadRequest();
	}

	private synchronized byte[] getDescriptionData(String host)
	{
		if (isNMPRMode() == false)
			updateURLBase(host);
		Node rootNode = getRootNode();
		if (rootNode == null)
			return new byte[0];
		// Thanks for Mikael Hakman (04/25/05)
		String desc = new String();
		desc += UPnP.XML_DECLARATION;
		desc += "\n";
		desc += rootNode.toString();
		return desc.getBytes();
	}
	
	private void httpGetRequestRecieved(HTTPRequest httpReq)
	{
		String uri = httpReq.getURI();
		Debug.message("httpGetRequestRecieved = " + uri);
		if (uri == null) {
			httpReq.returnBadRequest();
			return;
		}
					
		Device embDev;
		Service embService;
		
		byte fileByte[] = new byte[0];
		if (isDescriptionURI(uri) == true) {
			String localAddr = httpReq.getLocalAddress();
			fileByte = getDescriptionData(localAddr);
		}
		else if ((embDev = getDeviceByDescriptionURI(uri)) != null) {
			String localAddr = httpReq.getLocalAddress();
			fileByte = embDev.getDescriptionData(localAddr);
		}
		else if ((embService = getServiceBySCPDURL(uri)) != null) {
			fileByte = embService.getSCPDData();
		}
		else {
			httpReq.returnBadRequest();
			return;
		}
		
		HTTPResponse httpRes = new HTTPResponse();
		if (FileUtil.isXMLFileName(uri) == true)
			httpRes.setContentType(XML.CONTENT_TYPE);
		httpRes.setStatusCode(HTTPStatus.OK);
		httpRes.setContent(fileByte);

		httpReq.post(httpRes);
	}

	private void httpPostRequestRecieved(HTTPRequest httpReq)
	{
		if (httpReq.isSOAPAction() == true) {
			//SOAPRequest soapReq = new SOAPRequest(httpReq);
			soapActionRecieved(httpReq);
			return;
		}
		httpReq.returnBadRequest();
	}

	////////////////////////////////////////////////
	//	SOAP
	////////////////////////////////////////////////

	private void soapBadActionRecieved(HTTPRequest soapReq)
	{
		SOAPResponse soapRes = new SOAPResponse();
		soapRes.setStatusCode(HTTPStatus.BAD_REQUEST);
		soapReq.post(soapRes);
	}

	private void soapActionRecieved(HTTPRequest soapReq)
	{
		String uri = soapReq.getURI();
		Service ctlService = getServiceByControlURL(uri);
		if (ctlService != null)  {
			ActionRequest crlReq = new ActionRequest(soapReq);
			deviceControlRequestRecieved(crlReq, ctlService);
			return;
		}
		soapBadActionRecieved(soapReq);
	}

	////////////////////////////////////////////////
	//	controlAction
	////////////////////////////////////////////////

	private void deviceControlRequestRecieved(ControlRequest ctlReq, Service service)
	{
		if (ctlReq.isQueryControl() == true)
			deviceQueryControlRecieved(new QueryRequest(ctlReq), service);
		else
			deviceActionControlRecieved(new ActionRequest(ctlReq), service);
	}

	private void invalidActionControlRecieved(ControlRequest ctlReq)
	{
		ControlResponse actRes = new ActionResponse();
		actRes.setFaultResponse(UPnPStatus.INVALID_ACTION);
		ctlReq.post(actRes);
	}

	private void deviceActionControlRecieved(ActionRequest ctlReq, Service service)
	{
		if (Debug.isOn() == true)
			ctlReq.print();
			
		String actionName = ctlReq.getActionName();
		Action action = service.getAction(actionName);
		if (action == null) {
			invalidActionControlRecieved(ctlReq);
			return;
		}
		ArgumentList actionArgList = action.getArgumentList();
		ArgumentList reqArgList = ctlReq.getArgumentList();
		actionArgList.set(reqArgList);
		if (action.performActionListener(ctlReq) == false)
			invalidActionControlRecieved(ctlReq);
	}

	private void deviceQueryControlRecieved(QueryRequest ctlReq, Service service)
	{
		if (Debug.isOn() == true)
			ctlReq.print();
		String varName = ctlReq.getVarName();
		if (service.hasStateVariable(varName) == false) {
			invalidActionControlRecieved(ctlReq);
			return;
		}
		StateVariable stateVar = getStateVariable(varName);
		if (stateVar.performQueryListener(ctlReq) == false)
			invalidActionControlRecieved(ctlReq);
	}

	////////////////////////////////////////////////
	//	eventSubscribe
	////////////////////////////////////////////////

	private void upnpBadSubscriptionRecieved(SubscriptionRequest subReq, int code)
	{
		SubscriptionResponse subRes = new SubscriptionResponse();
		subRes.setErrorResponse(code);
		subReq.post(subRes);
	}

	private void deviceEventSubscriptionRecieved(SubscriptionRequest subReq)
	{
		String uri = subReq.getURI();
		Service service = getServiceByEventSubURL(uri);
		if (service == null) {
			subReq.returnBadRequest();
			return;
		}
		if (subReq.hasCallback() == false && subReq.hasSID() == false) {
			upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
			return;
		}

		// UNSUBSCRIBE
		if (subReq.isUnsubscribeRequest() == true) {
			deviceEventUnsubscriptionRecieved(service, subReq);
			return;
		}

		// SUBSCRIBE (NEW)
		if (subReq.hasCallback() == true) {
			deviceEventNewSubscriptionRecieved(service, subReq);
			return;
		}
		
		// SUBSCRIBE (RENEW)
		if (subReq.hasSID() == true) {
			deviceEventRenewSubscriptionRecieved(service, subReq);
			return;
		}
		
		upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
	}

	private void deviceEventNewSubscriptionRecieved(Service service, SubscriptionRequest subReq)
	{
		String callback = subReq.getCallback();
		try {
			new URL(callback);
		}
		catch (Exception e) {
			upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
			return;
		}

		long timeOut = subReq.getTimeout();
		String sid = Subscription.createSID();
			
		Subscriber sub = new Subscriber();
		sub.setDeliveryURL(callback);
		sub.setTimeOut(timeOut);
		sub.setSID(sid);
		service.addSubscriber(sub);
			
		SubscriptionResponse subRes = new SubscriptionResponse();
		subRes.setStatusCode(HTTPStatus.OK);
		subRes.setSID(sid);
		subRes.setTimeout(timeOut);
		if (Debug.isOn() == true)
			subRes.print();
		subReq.post(subRes);

		if (Debug.isOn() == true)
			subRes.print();
		
		service.notifyAllStateVariables();
	}

	private void deviceEventRenewSubscriptionRecieved(Service service, SubscriptionRequest subReq)
	{
		String sid = subReq.getSID();
		Subscriber sub = service.getSubscriber(sid);

		if (sub == null) {
			upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
			return;
		}

		long timeOut = subReq.getTimeout();
		sub.setTimeOut(timeOut);
		sub.renew();
				
		SubscriptionResponse subRes = new SubscriptionResponse();
		subRes.setStatusCode(HTTPStatus.OK);
		subRes.setSID(sid);
		subRes.setTimeout(timeOut);
		subReq.post(subRes);
		
		if (Debug.isOn() == true)
			subRes.print();
	}		

	private void deviceEventUnsubscriptionRecieved(Service service, SubscriptionRequest subReq)
	{
		String sid = subReq.getSID();
		Subscriber sub = service.getSubscriber(sid);

		if (sub == null) {
			upnpBadSubscriptionRecieved(subReq, HTTPStatus.PRECONDITION_FAILED);
			return;
		}

		service.removeSubscriber(sub);
						
		SubscriptionResponse subRes = new SubscriptionResponse();
		subRes.setStatusCode(HTTPStatus.OK);
		subReq.post(subRes);
		
		if (Debug.isOn() == true)
			subRes.print();
	}		
	
	////////////////////////////////////////////////
	//	Thread	
	////////////////////////////////////////////////

	private HTTPServerList getHTTPServerList() 
	{
		return getDeviceData().getHTTPServerList();
	}

	private SSDPSearchSocketList getSSDPSearchSocketList() 
	{
		return getDeviceData().getSSDPSearchSocketList();
	}

	private void setAdvertiser(Advertiser adv) 
	{
		getDeviceData().setAdvertiser(adv);
	}
	
	private Advertiser getAdvertiser() 
	{
		return getDeviceData().getAdvertiser();
	}

	public boolean start()
	{
		stop(true);
		
		////////////////////////////////////////
		// HTTP Server
		////////////////////////////////////////
		
		int retryCnt = 0;
		int bindPort = getHTTPPort();
		HTTPServerList httpServerList = getHTTPServerList();
		while (httpServerList.open(bindPort) == false) {
			retryCnt++;
			if (UPnP.SERVER_RETRY_COUNT < retryCnt)
				return false;
			setHTTPPort(bindPort + 1);
			bindPort = getHTTPPort();
		}
		httpServerList.addRequestListener(this);
		httpServerList.start();

		////////////////////////////////////////
		// SSDP Seach Socket
		////////////////////////////////////////
		
		SSDPSearchSocketList ssdpSearchSockList = getSSDPSearchSocketList();
		if (ssdpSearchSockList.open() == false)
			return false;
		ssdpSearchSockList.addSearchListener(this);
		ssdpSearchSockList.start();

		////////////////////////////////////////
		// Announce
		////////////////////////////////////////
		
		announce();
		
		////////////////////////////////////////
		// Advertiser
		////////////////////////////////////////

		Advertiser adv = new Advertiser(this);
		setAdvertiser(adv);
		adv.start();
		
		return true;
	}

	private boolean stop(boolean doByeBye)
	{
		if (doByeBye == true)
			byebye();
		
		HTTPServerList httpServerList = getHTTPServerList();
		httpServerList.stop();
		httpServerList.close();
		httpServerList.clear();
		
		SSDPSearchSocketList ssdpSearchSockList = getSSDPSearchSocketList();
		ssdpSearchSockList.stop();
		ssdpSearchSockList.close();
		ssdpSearchSockList.clear();
		
		Advertiser adv = getAdvertiser();
		if (adv != null) {
			adv.stop();
			setAdvertiser(null);
		}

		return true;
	}
	
	public boolean stop()
	{
		return stop(true);
	}

	////////////////////////////////////////////////
	// Interface Address
	////////////////////////////////////////////////
	
	public String getInterfaceAddress() 
	{
		SSDPPacket ssdpPacket = getSSDPPacket();
		if (ssdpPacket == null)
			return "";
		return ssdpPacket.getLocalAddress();
	}

	////////////////////////////////////////////////
	// Acion/QueryListener
	////////////////////////////////////////////////
	
	public void setActionListener(ActionListener listener)
	{
		ServiceList serviceList = getServiceList();
		int nServices = serviceList.size();
		for (int n=0; n<nServices; n++) {
			Service service = serviceList.getService(n);
			service.setActionListener(listener);
		}
	}

	public void setQueryListener(QueryListener listener)
	{
		ServiceList serviceList = getServiceList();
		int nServices = serviceList.size();
		for (int n=0; n<nServices; n++) {
			Service service = serviceList.getService(n);
			service.setQueryListener(listener);
		}
	}

	////////////////////////////////////////////////
	// Acion/QueryListener (includeSubDevices)
	////////////////////////////////////////////////

	// Thanks for Mikael Hakman (04/25/05)
	public void setActionListener(ActionListener listener, boolean includeSubDevices) 
	{
		setActionListener(listener);
		if (includeSubDevices == true) {
			DeviceList devList = getDeviceList();
			int devCnt = devList.size();
			for (int n = 0; n < devCnt; n++) {
				Device dev = devList.getDevice(n);
				dev.setActionListener(listener, true);
			}
		}
	}
		
	// Thanks for Mikael Hakman (04/25/05)
	public void setQueryListener(QueryListener listener, boolean includeSubDevices) 
	{
		setQueryListener(listener);
		if (includeSubDevices == true) {
			DeviceList devList = getDeviceList();
			int devCnt = devList.size();
			for (int n = 0; n < devCnt; n++) {
				Device dev = devList.getDevice(n);
				dev.setQueryListener(listener, true);
			}
		}
	}
	
	////////////////////////////////////////////////
	//	output
	////////////////////////////////////////////////

/*
	public void output(PrintWriter ps) 
	{
		ps.println("deviceType = " + getDeviceType());
		ps.println("freindlyName = " + getFriendlyName());
		ps.println("presentationURL = " + getPresentationURL());

		DeviceList devList = getDeviceList();
		ps.println("devList = " + devList.size());
		
		ServiceList serviceList = getServiceList();
		ps.println("serviceList = " + serviceList.size());

		IconList iconList = getIconList();
		ps.println("iconList = " + iconList.size());
	}

	public void print()
	{
		PrintWriter pr = new PrintWriter(System.out);
		output(pr);
		pr.flush();
	}
*/

}

