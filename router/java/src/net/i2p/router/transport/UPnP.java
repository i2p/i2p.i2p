/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package net.i2p.router.transport;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.Translate;

import org.cybergarage.upnp.Action;
import org.cybergarage.upnp.ArgumentList;
import org.cybergarage.upnp.ControlPoint;
import org.cybergarage.upnp.Device;
import org.cybergarage.upnp.DeviceList;
import org.cybergarage.upnp.Service;
import org.cybergarage.upnp.ServiceList;
import org.cybergarage.upnp.UPnPStatus;
import org.cybergarage.upnp.device.DeviceChangeListener;
import org.cybergarage.upnp.event.EventListener;
import org.freenetproject.DetectedIP;
import org.freenetproject.ForwardPort;
import org.freenetproject.ForwardPortCallback;
import org.freenetproject.ForwardPortStatus;

/**
 * This (and all in org/freenet, org/cybergarage, org/xmlpull)
 * grabbed from freenet SVN, mid-February 2009 by zzz.
 * This file modded somewhat to remove freenet-specific stuff,
 * but most of the glue to I2P is in UPnPManager (which was written
 * from scratch and is not the Limewire one referred to below).
 *
 * ==================
 *
 * This plugin implements UP&P support on a Freenet node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 *
 *
 * some code has been borrowed from Limewire : @see com.limegroup.gnutella.UPnPManager
 *
 * @see "http://www.upnp.org/"
 * @see "http://en.wikipedia.org/wiki/Universal_Plug_and_Play"
 */

/* 
 * TODO: Support multiple IGDs ?
 * TODO: Advertise the node like the MDNS plugin does
 * TODO: Implement EventListener and react on ip-change
 */ 
class UPnP extends ControlPoint implements DeviceChangeListener, EventListener {
	private final Log _log;
	private final I2PAppContext _context;
	
	/** some schemas */
	private static final String ROUTER_DEVICE = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
	private static final String WAN_DEVICE = "urn:schemas-upnp-org:device:WANDevice:1";
	private static final String WANCON_DEVICE = "urn:schemas-upnp-org:device:WANConnectionDevice:1";
	private static final String WAN_IP_CONNECTION = "urn:schemas-upnp-org:service:WANIPConnection:1";
	private static final String WAN_PPP_CONNECTION = "urn:schemas-upnp-org:service:WANPPPConnection:1";

	private Device _router;
	private Service _service;
	private boolean isDisabled = false; // We disable the plugin if more than one IGD is found
	private final Object lock = new Object();
	// FIXME: detect it for real and deal with it! @see #2524
	private volatile boolean thinksWeAreDoubleNatted = false;
	
	/** List of ports we want to forward */
	private Set<ForwardPort> portsToForward;
	/** List of ports we have actually forwarded */
	private final Set<ForwardPort> portsForwarded;
	/** Callback to call when a forward fails or succeeds */
	private ForwardPortCallback forwardCallback;
	
	public UPnP(I2PAppContext context) {
		super();
		_context = context;
		_log = _context.logManager().getLog(UPnP.class);
		portsForwarded = new HashSet<ForwardPort>();
		addDeviceChangeListener(this);
	}
	
	public boolean runPlugin() {
		synchronized(lock) {
			portsToForward = null;
		}
		return super.start();
	}

	/**
	 *  WARNING - Blocking up to 2 seconds
	 */
	public void terminate() {
		synchronized(lock) {
			portsToForward = null;
		}
		// this gets spun off in a thread...
		unregisterPortMappings();
		// If we stop too early and we've forwarded multiple ports,
		// the later ones don't get unregistered
		int i = 0;
		while (i++ < 20 && !portsForwarded.isEmpty()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException ie) {}
		}
		super.stop();
		_router = null;
		_service = null;
	}
	
	public DetectedIP[] getAddress() {
		_log.info("UP&P.getAddress() is called \\o/");
		if(isDisabled) {
			if (_log.shouldLog(Log.WARN))
				_log.warn("Plugin has been disabled previously, ignoring request.");
			return null;
		} else if(!isNATPresent()) {
			if (_log.shouldLog(Log.WARN))
				_log.warn("No UP&P device found, detection of the external ip address using the plugin has failed");
			return null;
		}
		
		DetectedIP result = null;
		final String natAddress = getNATAddress();
                if (natAddress == null || natAddress.length() <= 0) {
			if (_log.shouldLog(Log.WARN))
				_log.warn("No external address returned");
			return null;
		}
		try {
			InetAddress detectedIP = InetAddress.getByName(natAddress);

			short status = DetectedIP.NOT_SUPPORTED;
			thinksWeAreDoubleNatted = !TransportImpl.isPubliclyRoutable(detectedIP.getAddress());
			// If we have forwarded a port AND we don't have a private address
			if (_log.shouldLog(Log.WARN))
				_log.warn("NATAddress: \"" + natAddress + "\" detectedIP: " + detectedIP + " double? " + thinksWeAreDoubleNatted);
			if((portsForwarded.size() > 1) && (!thinksWeAreDoubleNatted))
				status = DetectedIP.FULL_INTERNET;
			
			result = new DetectedIP(detectedIP, status);
			
			if (_log.shouldLog(Log.WARN))
				_log.warn("Successful UP&P discovery :" + result);
			
			return new DetectedIP[] { result };
		} catch (UnknownHostException e) {
			_log.error("Caught an UnknownHostException resolving " + natAddress, e);
			return null;
		}
	}
	
	/**
	 *  DeviceChangeListener
	 */
	public void deviceAdded(Device dev) {
		synchronized (lock) {
			if(isDisabled) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("Plugin has been disabled previously, ignoring new device.");
				return;
			}
		}
		if(!ROUTER_DEVICE.equals(dev.getDeviceType()) || !dev.isRootDevice()) {
			if (_log.shouldLog(Log.WARN))
				_log.warn("UP&P non-IGD device found, ignoring : " + dev.getFriendlyName());
			return; // ignore non-IGD devices
		} else if(isNATPresent()) {
                        // maybe we should see if the old one went away before ignoring the new one?
			if (_log.shouldLog(Log.WARN))
				_log.warn("UP&P ignoring additional IGD device found: " + dev.getFriendlyName() + " UDN: " + dev.getUDN());
			/********** seems a little drastic
			isDisabled = true;
			
			synchronized(lock) {
				_router = null;
				_service = null;
			}
			
			stop();
			**************/
			return;
		}
		
		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P IGD found : " + dev.getFriendlyName() + " UDN: " + dev.getUDN() + " lease time: " + dev.getLeaseTime());
		synchronized(lock) {
			_router = dev;
		}
		
		discoverService();
		// We have found the device we need: stop the listener thread
		/// No, let's stick around to get notifications
		//stop();
		synchronized(lock) {
			/// we should look for the next one
			if(_service == null) {
				_log.error("The IGD device we got isn't suiting our needs, let's disable the plugin");
				isDisabled = true;
				_router = null;
				return;
			}
			subscribe(_service);
		}
		registerPortMappings();
	}
	
	private void registerPortMappings() {
		Set ports;
		synchronized(lock) {
			ports = portsToForward;
		}
		if(ports == null) return;
		registerPorts(ports);
	}

	/**
	 * Traverses the structure of the router device looking for the port mapping service.
	 */
	private void discoverService() {
		synchronized (lock) {
			for (Iterator iter = _router.getDeviceList().iterator();iter.hasNext();) {
				Device current = (Device)iter.next();
				if (!current.getDeviceType().equals(WAN_DEVICE))
					continue;

				DeviceList l = current.getDeviceList();
				for (int i=0;i<current.getDeviceList().size();i++) {
					Device current2 = l.getDevice(i);
					if (!current2.getDeviceType().equals(WANCON_DEVICE))
						continue;
					
					_service = current2.getService(WAN_PPP_CONNECTION);
					if(_service == null) {
						if (_log.shouldLog(Log.WARN))
							_log.warn(_router.getFriendlyName()+ " doesn't seems to be using PPP; we won't be able to extract bandwidth-related informations out of it.");
						_service = current2.getService(WAN_IP_CONNECTION);
						if(_service == null)
							_log.error(_router.getFriendlyName()+ " doesn't export WAN_IP_CONNECTION either: we won't be able to use it!");
					}
					
					return;
				}
			}
		}
	}
	
	public boolean tryAddMapping(String protocol, int port, String description, ForwardPort fp) {
		if (_log.shouldLog(Log.WARN))
			_log.warn("Registering a port mapping for " + port + "/" + protocol);
		int nbOfTries = 0;
		boolean isPortForwarded = false;
		while(nbOfTries++ < 5) {
			isPortForwarded = addMapping(protocol, port, "I2P " + description, fp);
			if(isPortForwarded)
				break;
			try {
				Thread.sleep(5000);	
			} catch (InterruptedException e) {}
		}
		if (_log.shouldLog(Log.WARN))
			_log.warn((isPortForwarded ? "Mapping is successful!" : "Mapping has failed!") + " ("+ nbOfTries + " tries)");
		return isPortForwarded;
	}
	
	public void unregisterPortMappings() {
		Set ports;
		synchronized(lock) {
			ports = new HashSet(portsForwarded);
		}
		this.unregisterPorts(ports);
	}
	
	/**
	 *  DeviceChangeListener
	 */
	public void deviceRemoved(Device dev ){
		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P device removed : " + dev.getFriendlyName() + " UDN: " + dev.getUDN());
		synchronized (lock) {
			if(_router == null) return;
			// I2P this wasn't working
			//if(_router.equals(dev)) {
		        if(ROUTER_DEVICE.equals(dev.getDeviceType()) &&
			   dev.isRootDevice() &&
			   stringEquals(_router.getFriendlyName(), dev.getFriendlyName()) &&
			   stringEquals(_router.getUDN(), dev.getUDN())) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("UP&P IGD device removed : " + dev.getFriendlyName());
				_router = null;
				_service = null;
			}
		}
	}
	
	/**
	 *  EventListener callback -
	 *  unused for now - how many devices support events?
	 */
	public void eventNotifyReceived(String uuid, long seq, String varName, String value) {
		if (_log.shouldLog(Log.WARN))
			_log.warn("Event: " + uuid + ' ' + seq + ' ' + varName + '=' + value);
	}

	/** compare two strings, either of which could be null */
	private static boolean stringEquals(String a, String b) {
		if (a != null)
			return a.equals(b);
		return b == null;
	}

	/**
	 * @return whether we are behind an UPnP-enabled NAT/router
	 */
	public boolean isNATPresent() {
	    return _router != null && _service != null;
	}

	/**
	 * @return the external address the NAT thinks we have.  Blocking.
	 * null if we can't find it.
	 */
	public String getNATAddress() {
		if(!isNATPresent())
			return null;

		Action getIP = _service.getAction("GetExternalIPAddress");
		if(getIP == null || !getIP.postControlAction())
			return null;

		String rv = (getIP.getOutputArgumentList().getArgument("NewExternalIPAddress")).getValue();
		// I2P some devices return 0.0.0.0 when not connected
		if ("0.0.0.0".equals(rv))
			return null;
		return rv;
	}

	/**
	 * @return the reported upstream bit rate in bits per second. -1 if it's not available. Blocking.
	 */
	public int getUpstreamMaxBitRate() {
		if(!isNATPresent() || thinksWeAreDoubleNatted)
			return -1;

		Action getIP = _service.getAction("GetLinkLayerMaxBitRates");
		if(getIP == null || !getIP.postControlAction())
			return -1;

		return Integer.valueOf(getIP.getOutputArgumentList().getArgument("NewUpstreamMaxBitRate").getValue());
	}
	
	/**
	 * @return the reported downstream bit rate in bits per second. -1 if it's not available. Blocking.
	 */
	public int getDownstreamMaxBitRate() {
		if(!isNATPresent() || thinksWeAreDoubleNatted)
			return -1;

		Action getIP = _service.getAction("GetLinkLayerMaxBitRates");
		if(getIP == null || !getIP.postControlAction())
			return -1;

		return Integer.valueOf(getIP.getOutputArgumentList().getArgument("NewDownstreamMaxBitRate").getValue());
	}
	
/***
	private void listStateTable(Service serv, StringBuilder sb) {
		ServiceStateTable table = serv.getServiceStateTable();
		sb.append("<div><small>");
		for(int i=0; i<table.size(); i++) {
			StateVariable current = table.getStateVariable(i);
			sb.append(current.getName() + " : " + current.getValue() + "<br>");
		}
		sb.append("</small></div>");
	}

	private void listActionsArguments(Action action, StringBuilder sb) {
		ArgumentList ar = action.getArgumentList();
		for(int i=0; i<ar.size(); i++) {
			Argument argument = ar.getArgument(i);
			if(argument == null ) continue;
			sb.append("<div><small>argument ("+i+") :" + argument.getName()+"</small></div>");
		}
	}
	
	private void listActions(Service service, StringBuilder sb) {
		ActionList al = service.getActionList();
		for(int i=0; i<al.size(); i++) {
			Action action = al.getAction(i);
			if(action == null ) continue;
			sb.append("<div>action ("+i+") :" + action.getName());
			listActionsArguments(action, sb);
			sb.append("</div>");
		}
	}
***/
	
	/**
	 * A blocking toString(). That's interesting.
         * Cache the last ArgumentList to speed it up some.
	 * Count on listSubServices() to call multiple combinations of arguments
         * so we don't get old data.
         */
	private String _lastAction;
	private Service _lastService;
	private ArgumentList _lastArgumentList;
	private final Object toStringLock = new Object();
	private String toString(String action, String arg, Service serv) {
		synchronized(toStringLock) {
			if ((!action.equals(_lastAction)) ||
			    (!serv.equals(_lastService)) ||
			    _lastArgumentList == null) {
				Action getIP = serv.getAction(action);
				if(getIP == null || !getIP.postControlAction()) {
					_lastAction = null;
					return null;
				}
				_lastAction = action;
				_lastService = serv;
				_lastArgumentList = getIP.getOutputArgumentList();
			}
			return _lastArgumentList.getArgument(arg).getValue();
		}
	}
	
	// TODO: extend it! RTFM
	private void listSubServices(Device dev, StringBuilder sb) {
		ServiceList sl = dev.getServiceList();
		if (sl.isEmpty())
			return;
		sb.append("<ul>\n");
		for(int i=0; i<sl.size(); i++) {
			Service serv = sl.getService(i);
			if(serv == null) continue;
			sb.append("<li>").append(_("Service")).append(": ");
			if("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1".equals(serv.getServiceType())){
				sb.append(_("WAN Common Interface Configuration"));
				sb.append("<ul><li>").append(_("Status")).append(": " + toString("GetCommonLinkProperties", "NewPhysicalLinkStatus", serv));
				sb.append("<li>").append(_("Type")).append(": " + toString("GetCommonLinkProperties", "NewWANAccessType", serv));
				sb.append("<li>").append(_("Upstream")).append(": " + toString("GetCommonLinkProperties", "NewLayer1UpstreamMaxBitRate", serv));
				sb.append("<li>").append(_("Downstream")).append(": " + toString("GetCommonLinkProperties", "NewLayer1DownstreamMaxBitRate", serv) + "<br>");
			}else if("urn:schemas-upnp-org:service:WANPPPConnection:1".equals(serv.getServiceType())){
				sb.append(_("WAN PPP Connection"));
				sb.append("<ul><li>").append(_("Status")).append(": " + toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append("<li>").append(_("Type")).append(": " + toString("GetConnectionTypeInfo", "NewConnectionType", serv));
				sb.append("<li>").append(_("Upstream")).append(": " + toString("GetLinkLayerMaxBitRates", "NewUpstreamMaxBitRate", serv));
				sb.append("<li>").append(_("Downstream")).append(": " + toString("GetLinkLayerMaxBitRates", "NewDownstreamMaxBitRate", serv) + "<br>");
				sb.append("<li>").append(_("External IP")).append(": " + toString("GetExternalIPAddress", "NewExternalIPAddress", serv) + "<br>");
			}else if("urn:schemas-upnp-org:service:Layer3Forwarding:1".equals(serv.getServiceType())){
				sb.append(_("Layer 3 Forwarding"));
				sb.append("<ul><li>").append(_("Default Connection Service")).append(": " + toString("GetDefaultConnectionService", "NewDefaultConnectionService", serv));
			}else if(WAN_IP_CONNECTION.equals(serv.getServiceType())){
				sb.append(_("WAN IP Connection"));
				sb.append("<ul><li>").append(_("Status")).append(": " + toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append("<li>").append(_("Type")).append(": " + toString("GetConnectionTypeInfo", "NewConnectionType", serv));
				sb.append("<li>").append(_("External IP")).append(": " + toString("GetExternalIPAddress", "NewExternalIPAddress", serv) + "<br>");
			}else if("urn:schemas-upnp-org:service:WANEthernetLinkConfig:1".equals(serv.getServiceType())){
				sb.append(_("WAN Ethernet Link Configuration"));
				sb.append("<ul><li>").append(_("Status")).append(": " + toString("GetEthernetLinkStatus", "NewEthernetLinkStatus", serv) + "<br>");
			}else
				sb.append("~~~~~~~ "+serv.getServiceType() + "<ul>");
			//listActions(serv, sb);
			//listStateTable(serv, sb);
			sb.append("</ul>\n");
		}
		sb.append("</ul>\n");
	}
	
	private void listSubDev(String prefix, Device dev, StringBuilder sb){
                if (prefix == null)
			sb.append("<p>").append(_("Found Device")).append(": ");
		else
			sb.append("<li>").append(_("Subdevice")).append(": ");
		sb.append(dev.getFriendlyName());
                if (prefix == null)
			sb.append("</p>");
		listSubServices(dev, sb);
		
		DeviceList dl = dev.getDeviceList();
		if (dl.isEmpty())
			return;
		sb.append("<ul>\n");
		for(int j=0; j<dl.size(); j++) {
			Device subDev = dl.getDevice(j);
			if(subDev == null) continue;
			listSubDev(dev.getFriendlyName(), subDev, sb);
		}
		sb.append("</ul>\n");
	}
	
	/** warning - slow */
	public String renderStatusHTML() {
		final StringBuilder sb = new StringBuilder();
		sb.append("<h3><a name=\"upnp\"></a>").append(_("UPnP Status")).append("</h3>");
		
		if(isDisabled) {
			sb.append(_("UPnP has been disabled; Do you have more than one UPnP Internet Gateway Device on your LAN ?"));
			return sb.toString();
		} else if(!isNATPresent()) {
			sb.append(_("UPnP has not found any UPnP-aware, compatible device on your LAN."));
			return sb.toString();
		}
		
		listSubDev(null, _router, sb);
		String addr = getNATAddress();
		sb.append("<p>");
		if (addr != null)
		    sb.append(_("The current external IP address reported by UPnP is {0}", addr));
		else
		    sb.append(_("The current external IP address is not available."));
		int downstreamMaxBitRate = getDownstreamMaxBitRate();
		int upstreamMaxBitRate = getUpstreamMaxBitRate();
		if(downstreamMaxBitRate > 0)
			sb.append("<br>").append(_("UPnP reports the maximum downstream bit rate is {0}bits/sec", DataHelper.formatSize2(downstreamMaxBitRate)));
		if(upstreamMaxBitRate > 0)
			sb.append("<br>").append(_("UPnP reports the maximum upstream bit rate is {0}bits/sec", DataHelper.formatSize2(upstreamMaxBitRate)));
		synchronized(lock) {
			if(portsToForward != null) {
				for(ForwardPort port : portsToForward) {
					sb.append("<br>");
					if(portsForwarded.contains(port))
						// {0} is TCP or UDP
						// {1,number,#####} prevents 12345 from being output as 12,345 in the English locale.
						// If you want the digit separator in your locale, translate as {1}.
						sb.append(_("{0} port {1,number,#####} was successfully forwarded by UPnP.", protoToString(port.protocol), port.portNumber));
					else
						sb.append(_("{0} port {1,number,#####} was not forwarded by UPnP.", protoToString(port.protocol), port.portNumber));
				}
			}
		}
		
		sb.append("</p>");
		return sb.toString();
	}
	
	/**
	 *  This always requests that the external port == the internal port, for now.
	 *  Blocking!
	 */
	private boolean addMapping(String protocol, int port, String description, ForwardPort fp) {
		if(isDisabled || !isNATPresent() || _router == null) {
                        _log.error("Can't addMapping: " + isDisabled + " " + isNATPresent() + " " + _router);
			return false;
                }
		
		// Just in case...
                // this confuses my linksys? - zzz
		//removeMapping(protocol, port, fp, true);
		
		Action add = _service.getAction("AddPortMapping");
		if(add == null) {
		    _log.error("Couldn't find AddPortMapping action!");
		    return false;
		}
		    
		
		add.setArgumentValue("NewRemoteHost", "");
		add.setArgumentValue("NewExternalPort", port);
		// bugfix, see below for details
		String intf = _router.getInterfaceAddress();
		String us = getOurAddress(intf);
		if (_log.shouldLog(Log.WARN) && !us.equals(intf))
			_log.warn("Requesting port forward to " + us + ':' + port +
			          " when cybergarage wanted " + intf);
		add.setArgumentValue("NewInternalClient", us);
		add.setArgumentValue("NewInternalPort", port);
		add.setArgumentValue("NewProtocol", protocol);
		add.setArgumentValue("NewPortMappingDescription", description);
		add.setArgumentValue("NewEnabled","1");
		add.setArgumentValue("NewLeaseDuration", 0);
		
		boolean rv = add.postControlAction();
		if(rv) {
			synchronized(lock) {
				portsForwarded.add(fp);
			}
		}

		int level = rv ? Log.INFO : Log.WARN;
		if (_log.shouldLog(level)) {
			StringBuilder buf = new StringBuilder();
			buf.append("AddPortMapping result for ").append(protocol).append(" port ").append(port);
			// Not sure which of these has the good info
			UPnPStatus status = add.getStatus();
			if (status != null)
			    buf.append(" Status: ").append(status.getCode()).append(' ').append(status.getDescription());
			status = add.getControlStatus();
			if (status != null)
			    buf.append(" ControlStatus: ").append(status.getCode()).append(' ').append(status.getDescription());
			_log.log(level, buf.toString());
		}

		// TODO if port is busy, retry with wildcard external port ??
		// from spec:
		// 402 Invalid Args See UPnP Device Architecture section on Control.
		// 501 Action Failed See UPnP Device Architecture section on Control.
		// 715 WildCardNotPermittedInSrcIP The source IP address cannot be wild-carded
		// 716 WildCardNotPermittedInExtPort The external port cannot be wild-carded
		// 718 ConflictInMappingEntry The port mapping entry specified conflicts with a mapping assigned previously to another client
		// 724 SamePortValuesRequired Internal and External port values must be the same
		// 725 OnlyPermanentLeasesSupported The NAT implementation only supports permanent lease times on port mappings
		// 726 RemoteHostOnlySupportsWildcard RemoteHost must be a wildcard and cannot be a specific IP address or DNS name
		// 727 ExternalPortOnlySupportsWildcard ExternalPort must be a wildcard and cannot be a specific port value

		// TODO return error code and description for display

		return rv;
	}

	/**
	 * Bug fix:
	 * If the SSDP notify or search response sockets listen on more than one interface,
	 * cybergarage can get our IP address wrong, and then we send the wrong one
	 * to the UPnP device, which will reject it if it enforces strict addressing.
	 *
	 * For example, if we have interfaces 192.168.1.1 and 192.168.2.1, we could
	 * get a response from 192.168.1.99 on the 192.168.2.1 interface, but when
	 * we send something to 192.168.1.99 it will go out the 192.168.1.1 interface
	 * with a request to forward to 192.168.2.1.
	 *
	 * So return the address of ours that is closest to his.
	 *
	 * @since 0.8.8
	 */
	private String getOurAddress(String deflt) {
		String rv = deflt;
		String hisIP = null;
		// see ControlRequest.setRequestHost()
		String him = _router.getURLBase();
		if (him != null && him.length() > 0) {
			try {
				URL url = new URL(him);
				hisIP = url.getHost();
			} catch (MalformedURLException mue) {}
		}
		if (hisIP == null) {
			him = _router.getLocation();
			if (him != null && him.length() > 0) {
				try {
					URL url = new URL(him);
					hisIP = url.getHost();
				} catch (MalformedURLException mue) {}
			}
		}
		if (hisIP == null)
			return rv;
		try {
			byte[] hisBytes = InetAddress.getByName(hisIP).getAddress();
			if (hisBytes.length != 4)
				return deflt;
			long hisLong = DataHelper.fromLong(hisBytes, 0, 4);
			long distance = Long.MAX_VALUE;

			// loop through all our IP addresses, including the default, and
			// return the one closest to the router's IP
			Set<String> myAddresses = Addresses.getAddresses(true, false);  // yes local, no IPv6
			myAddresses.add(deflt);
			for (String me : myAddresses) {
				if (me.startsWith("127.") || me.equals("0.0.0.0"))
					continue;
				try {
					byte[] myBytes = InetAddress.getByName(me).getAddress();
					long myLong = DataHelper.fromLong(myBytes, 0, 4);
					long newDistance = myLong ^ hisLong;
					if (newDistance < distance) {
						rv = me;
						distance = newDistance;
					}
				} catch (UnknownHostException uhe) {}
			}
		} catch (UnknownHostException uhe) {}
		return rv;
	}

	/** blocking */
	private boolean removeMapping(String protocol, int port, ForwardPort fp, boolean noLog) {
		if(isDisabled || !isNATPresent())
			return false;
		
		Action remove = _service.getAction("DeletePortMapping");
		if(remove == null) {
			 _log.error("Couldn't find DeletePortMapping action!");
		    return false;
	    }
		
		// remove.setArgumentValue("NewRemoteHost", "");
		remove.setArgumentValue("NewExternalPort", port);
		remove.setArgumentValue("NewProtocol", protocol);
		
		boolean retval = remove.postControlAction();
		synchronized(lock) {
			portsForwarded.remove(fp);
		}
		
		if(_log.shouldLog(Log.WARN) && !noLog)
			_log.warn("UPnP: Removed mapping for "+fp.name+" "+port+" / "+protocol);
		return retval;
	}

	/**
	 *  Registers a callback when the given ports change.
	 *  non-blocking
	 *  @param ports non-null
	 *  @param cb in UPnPManager
	 */
	public void onChangePublicPorts(Set<ForwardPort> ports, ForwardPortCallback cb) {
		Set<ForwardPort> portsToDumpNow = null;
		Set<ForwardPort> portsToForwardNow = null;
		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P Forwarding "+ports.size()+" ports...");
		synchronized(lock) {
			if(forwardCallback != null && forwardCallback != cb && cb != null) {
				_log.error("ForwardPortCallback changed from "+forwardCallback+" to "+cb+" - using new value, but this is very strange!");
			}
			forwardCallback = cb;
			if(portsToForward == null || portsToForward.isEmpty()) {
				portsToForward = ports;
				portsToForwardNow = ports;
				portsToDumpNow = null;
			} else if(ports.isEmpty()) {
				portsToDumpNow = portsToForward;
				portsToForward = ports;
				portsToForwardNow = null;
			} else {
				// Some ports to keep, some ports to dump
				// Ports in ports but not in portsToForwardNow we must forward
				// Ports in portsToForwardNow but not in ports we must dump
				for(ForwardPort port: ports) {
					//if(portsToForward.contains(port)) {
					// If not in portsForwarded, it wasn't successful, try again
					if(portsForwarded.contains(port)) {
						// We have forwarded it, and it should be forwarded, cool.
						// Big problem here, if firewall resets, we don't know it.
						// Do we need to re-forward anyway? or poll the router?
					} else {
						// Needs forwarding
						if(portsToForwardNow == null) portsToForwardNow = new HashSet<ForwardPort>();
						portsToForwardNow.add(port);
					}
				}
				for(ForwardPort port : portsToForward) {
					if(ports.contains(port)) {
						// Should be forwarded, has been forwarded, cool.
					} else {
						// Needs dropping
						if(portsToDumpNow == null) portsToDumpNow = new HashSet<ForwardPort>();
						portsToDumpNow.add(port);
					}
				}
				portsToForward = ports;
			}
			if(_router == null) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("No UPnP router available to update");
				return; // When one is found, we will do the forwards
			}
		}
		if(portsToDumpNow != null)
			unregisterPorts(portsToDumpNow);
		if(portsToForwardNow != null)
			registerPorts(portsToForwardNow);
	}

        private static String protoToString(int p) {
		if(p == ForwardPort.PROTOCOL_UDP_IPV4)
			return "UDP";
		if(p == ForwardPort.PROTOCOL_TCP_IPV4)
			return "TCP";
		return "?";
	}

	private static int __id = 0;

	/**
	 *  postControlAction() can take many seconds, especially if it's failing,
         *  and onChangePublicPorts() may be called from threads we don't want to slow down,
         *  so throw this in a thread.
         */
	private void registerPorts(Set<ForwardPort> portsToForwardNow) {
		if (_log.shouldLog(Log.INFO))
			_log.info("Starting thread to forward " + portsToForwardNow.size() + " ports");
	        Thread t = new Thread(new RegisterPortsThread(portsToForwardNow));
		t.setName("UPnP Port Opener " + (++__id));
		t.setDaemon(true);
		t.start();
	}

	private class RegisterPortsThread implements Runnable {
		private Set<ForwardPort> portsToForwardNow;

		public RegisterPortsThread(Set<ForwardPort> ports) {
			portsToForwardNow = ports;
		}

		public void run() {
			for(ForwardPort port : portsToForwardNow) {
				String proto = protoToString(port.protocol);
				ForwardPortStatus fps;
				if (proto.length() <= 1) {
					fps = new ForwardPortStatus(ForwardPortStatus.DEFINITE_FAILURE, "Protocol not supported", port.portNumber);
				} else if(tryAddMapping(proto, port.portNumber, port.name, port)) {
					fps = new ForwardPortStatus(ForwardPortStatus.MAYBE_SUCCESS, "Port apparently forwarded by UPnP", port.portNumber);
				} else {
					fps = new ForwardPortStatus(ForwardPortStatus.PROBABLE_FAILURE, "UPnP port forwarding apparently failed", port.portNumber);
				}
				Map map = Collections.singletonMap(port, fps);
				forwardCallback.portForwardStatus(map);
			}
		}
	}

	/**
	 *  postControlAction() can take many seconds, especially if it's failing,
         *  and onChangePublicPorts() may be called from threads we don't want to slow down,
         *  so throw this in a thread.
         */
	private void unregisterPorts(Set<ForwardPort> portsToForwardNow) {
		if (_log.shouldLog(Log.INFO))
			_log.info("Starting thread to un-forward " + portsToForwardNow.size() + " ports");
	        Thread t = new Thread(new UnregisterPortsThread(portsToForwardNow));
		t.setName("UPnP Port Closer " + (++__id));
		t.setDaemon(true);
		t.start();
	}

	private class UnregisterPortsThread implements Runnable {
		private Set<ForwardPort> portsToForwardNow;

		public UnregisterPortsThread(Set<ForwardPort> ports) {
			portsToForwardNow = ports;
		}

		public void run() {
			for(ForwardPort port : portsToForwardNow) {
				String proto = protoToString(port.protocol);
				if (proto.length() <= 1)
					// Ignore, we've already complained about it
					continue;
				removeMapping(proto, port.portNumber, port, false);
			}
		}
	}

	public static void main(String[] args) throws Exception {
		UPnP upnp = new UPnP(I2PAppContext.getGlobalContext());
		ControlPoint cp = new ControlPoint();
		System.out.println("Searching for up&p devices:");
		cp.start();
		cp.search();
		while(true) {
			DeviceList list = cp.getDeviceList();
			System.out.println("Found " + list.size() + " devices!");
			StringBuilder sb = new StringBuilder();
			Iterator<Device> it = list.iterator();
			while(it.hasNext()) {
				Device device = it.next();
				upnp.listSubDev(device.toString(), device, sb);
				System.out.println("Here is the listing for " + device.toString() + " :");
				System.out.println(sb.toString());
				sb = new StringBuilder();
			}
			System.out.println("End");
			Thread.sleep(2000);
		}
	}

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     *  Translate
     */
    private final String _(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     */
    private final String _(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     */
    private final String _(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }
}
