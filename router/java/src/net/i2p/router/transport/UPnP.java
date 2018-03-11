/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package net.i2p.router.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.Translate;

import org.cybergarage.upnp.Action;
import org.cybergarage.upnp.ActionList;
import org.cybergarage.upnp.Argument;
import org.cybergarage.upnp.ArgumentList;
import org.cybergarage.upnp.ControlPoint;
import org.cybergarage.upnp.Device;
import org.cybergarage.upnp.DeviceList;
import org.cybergarage.upnp.Service;
import org.cybergarage.upnp.ServiceList;
import org.cybergarage.upnp.ServiceStateTable;
import org.cybergarage.upnp.StateVariable;
import org.cybergarage.upnp.UPnPStatus;
import org.cybergarage.upnp.device.DeviceChangeListener;
import org.cybergarage.upnp.event.EventListener;
import org.cybergarage.upnp.ssdp.SSDPPacket;
import org.cybergarage.util.Debug;
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
 * This plugin implements UP&amp;P support on a Freenet node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 *
 *
 * some code has been borrowed from Limewire : @see com.limegroup.gnutella.UPnPManager
 *
 * Public only for command line usage. Not a public API, not for external use.
 *
 * @see "http://www.upnp.org/"
 * @see "http://en.wikipedia.org/wiki/Universal_Plug_and_Play"
 * @since 0.7.4
 */

/* 
 * TODO: Support multiple IGDs ?
 * TODO: Advertise the node like the MDNS plugin does
 * TODO: Implement EventListener and react on ip-change
 *
 * Public for CommandLine main()
 */ 
public class UPnP extends ControlPoint implements DeviceChangeListener, EventListener {
	private final Log _log;
	private final I2PAppContext _context;
	
	/** some schemas */
	private static final String ROUTER_DEVICE = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
	private static final String WAN_DEVICE = "urn:schemas-upnp-org:device:WANDevice:1";
	private static final String WANCON_DEVICE = "urn:schemas-upnp-org:device:WANConnectionDevice:1";
	private static final String WAN_IP_CONNECTION = "urn:schemas-upnp-org:service:WANIPConnection:1";
	private static final String WAN_PPP_CONNECTION = "urn:schemas-upnp-org:service:WANPPPConnection:1";
	/** IGD 2 flavors, since 0.9.34 */
	private static final String ROUTER_DEVICE_2 = "urn:schemas-upnp-org:device:InternetGatewayDevice:2";
	private static final String WAN_DEVICE_2 = "urn:schemas-upnp-org:device:WANDevice:2";
	private static final String WANCON_DEVICE_2 = "urn:schemas-upnp-org:device:WANConnectionDevice:2";
	private static final String WAN_IP_CONNECTION_2 = "urn:schemas-upnp-org:service:WANIPConnection:2";
	private static final String WAN_IPV6_CONNECTION = "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1";

	private Device _router;
	private Service _service;
	// UDN -> friendly name
	private final Map<String, String> _otherUDNs;
	private final Map<String, String> _eventVars;
	private boolean isDisabled = false; // We disable the plugin if more than one IGD is found
	private volatile boolean _serviceLacksAPM;
	private final Object lock = new Object();
	// FIXME: detect it for real and deal with it! @see #2524
	private volatile boolean thinksWeAreDoubleNatted = false;
	
	/** List of ports we want to forward */
	private final Set<ForwardPort> portsToForward;
	/** List of ports we have actually forwarded */
	private final Set<ForwardPort> portsForwarded;
	/** Callback to call when a forward fails or succeeds */
	private ForwardPortCallback forwardCallback;

	private static final String PROP_ADVANCED = "routerconsole.advanced";
	private static final String PROP_IGNORE = "i2np.upnp.ignore";
	/** set to true to talk to UPnP on the same host as us, probably for testing */
	private static final boolean ALLOW_SAME_HOST = false;
	
	public UPnP(I2PAppContext context) {
		super();
		_context = context;
		_log = _context.logManager().getLog(UPnP.class);
		portsToForward = new HashSet<ForwardPort>();
		portsForwarded = new HashSet<ForwardPort>();
		_otherUDNs = new HashMap<String, String>(4);
		_eventVars = new HashMap<String, String>(4);
	}
	
	public synchronized boolean runPlugin() {
		addDeviceChangeListener(this);
		addEventListener(this);
		synchronized(lock) {
			portsToForward.clear();
			portsForwarded.clear();
			_eventVars.clear();
		}
		return super.start();
	}

	/**
	 *  WARNING - Blocking up to 2 seconds
	 */
	public synchronized void terminate() {
		removeDeviceChangeListener(this);
		removeEventListener(this);
		synchronized(lock) {
			portsToForward.clear();
			_eventVars.clear();
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
		synchronized(lock) {
			_router = null;
			_service = null;
			_serviceLacksAPM = false;
		}
	}
	
	/**
	 *  As we only support a single active IGD, and we don't currently have any way
	 *  to get any IPv6 addresses, this will return at most one IPv4 address.
	 *
	 *  Blocking!!!
	 *
	 *  @return array of length 1 containing an IPv4 address, or null
	 */
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
			thinksWeAreDoubleNatted = !TransportUtil.isPubliclyRoutable(detectedIP.getAddress(), false);
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
                String udn = dev.getUDN();
		if (udn == null)
			udn = "???";
                String name = dev.getFriendlyName();
		if (name == null)
			name = "???";
		String type = dev.getDeviceType();
		boolean isIGD = (ROUTER_DEVICE.equals(type) || ROUTER_DEVICE_2.equals(type)) && dev.isRootDevice();
		name += isIGD ? " IGD" : (' ' + type);
		String ip = getIP(dev);
		if (ip != null)
			name += ' ' + ip;
		synchronized (lock) {
			if(isDisabled) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("Plugin has been disabled previously, ignoring " + name + " UDN: " + udn);
				_otherUDNs.put(udn, name);
				return;
			}
		}
		if(!isIGD) {
			if (_log.shouldLog(Log.WARN))
				_log.warn("UP&P non-IGD device found, ignoring " + name + ' ' + dev.getDeviceType());
			synchronized (lock) {
				_otherUDNs.put(udn, name);
			}
			return; // ignore non-IGD devices
		} else if(isNATPresent()) {
                        // maybe we should see if the old one went away before ignoring the new one?
			// TODO if old one doesn't have an IP address but new one does, switch
			_log.logAlways(Log.WARN, "UP&P ignoring additional device " + name + " UDN: " + udn);
			synchronized (lock) {
				_otherUDNs.put(udn, name);
			}
			return;
		}
		
		boolean ignore = false;
		String toIgnore = _context.getProperty(PROP_IGNORE);
		if (toIgnore != null) {
			String[] ignores = DataHelper.split(toIgnore, "[,; \r\n\t]");
			for (int i = 0; i < ignores.length; i++) {
				if (ignores[i].equals(udn)) {
					ignore = true;
					_log.logAlways(Log.WARN, "Ignoring by config: " + name + " UDN: " + udn);
					break;
				}
			}
		}
		Set<String> myAddresses = Addresses.getAddresses(true, false);  // yes local, no IPv6
		if (!ignore && !ALLOW_SAME_HOST && ip != null && myAddresses.contains(ip)) {
			ignore = true;
			_log.logAlways(Log.WARN, "Ignoring UPnP on same host: " + name + " UDN: " + udn);
		}

		// IP check
		SSDPPacket pkt = dev.getSSDPPacket();
		if (!ignore && pkt != null) {
			String pktIP = pkt.getRemoteAddress();
			if (!stringEquals(ip, pktIP)) {
				ignore = true;
				_log.logAlways(Log.WARN, "Ignoring UPnP with IP mismatch: " + name + " UDN: " + udn);
			}
		}

		synchronized(lock) {
			if (ignore) {
				_otherUDNs.put(udn, name);
				return;
			} else {
				_router = dev;
			}
		}

		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P IGD found : " + name + " UDN: " + udn + " lease time: " + dev.getLeaseTime());
		
		discoverService();
		// We have found the device we need: stop the listener thread
		/// No, let's stick around to get notifications
		//stop();
		synchronized(lock) {
			/// we should look for the next one
			if(_service == null) {
				_log.error("The IGD device we got isn't suiting our needs, let's disable the plugin");
				//isDisabled = true;
				_router = null;
				return;
			}
			subscribe(_service);
		}
		registerPortMappings();
	}
	
	private void registerPortMappings() {
		Set<ForwardPort> ports;
		synchronized(lock) {
			ports = new HashSet<ForwardPort>(portsForwarded);
		}
		if (ports.isEmpty())
			return;
		registerPorts(ports);
	}

	/**
	 * Traverses the structure of the router device looking for the port mapping service.
	 */
	private void discoverService() {
		synchronized (lock) {
			for (Device current : _router.getDeviceList()) {
				String type = current.getDeviceType();
				if (!(WAN_DEVICE.equals(type) || WAN_DEVICE_2.equals(type)))
					continue;

				DeviceList l = current.getDeviceList();
				for (int i=0;i<current.getDeviceList().size();i++) {
					Device current2 = l.getDevice(i);
					type = current2.getDeviceType();
					if (!(WANCON_DEVICE.equals(type) || WANCON_DEVICE_2.equals(type)))
						continue;
					
					_service = current2.getService(WAN_IP_CONNECTION_2);
					if (_service == null) {
						_service = current2.getService(WAN_IP_CONNECTION);
						if (_service == null) {
							_service = current2.getService(WAN_PPP_CONNECTION);
							if (_service == null) {
								if (_log.shouldWarn())
									_log.warn(_router.getFriendlyName() + " doesn't have any recognized connection type; we won't be able to use it!");
							}
						}
					}
					if (_log.shouldWarn()) {
						Service svc2 = current2.getService(WAN_IPV6_CONNECTION);
						if (svc2 != null)
							_log.warn(_router.getFriendlyName() + " supports WANIPv6Connection, but we don't");
					}
					_serviceLacksAPM = false;
					return;
				}
			}
		}
	}
	
	private boolean tryAddMapping(String protocol, int port, String description, ForwardPort fp) {
		if (_log.shouldLog(Log.WARN))
			_log.warn("Registering a port mapping for " + port + "/" + protocol);
		int nbOfTries = 0;
		boolean isPortForwarded = false;
		while ((!_serviceLacksAPM) && nbOfTries++ < 5) {
			//isPortForwarded = addMapping(protocol, port, "I2P " + description, fp);
			isPortForwarded = addMapping(protocol, port, description, fp);
			if(isPortForwarded || _serviceLacksAPM)
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
		Set<ForwardPort> ports;
		synchronized(lock) {
			ports = new HashSet<ForwardPort>(portsForwarded);
		}
		if (ports.isEmpty())
			return;
		this.unregisterPorts(ports);
	}
	
	/**
	 *  DeviceChangeListener
	 */
	public void deviceRemoved(Device dev ){
                String udn = dev.getUDN();
		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P device removed : " + dev.getFriendlyName() + " UDN: " + udn);
		ForwardPortCallback fpc = null;
		Map<ForwardPort, ForwardPortStatus> removeMap = null;
		synchronized (lock) {
			if (udn != null)
				_otherUDNs.remove(udn);
			else
				_otherUDNs.remove("???");
			if (_router == null) return;
			// I2P this wasn't working
			//if(_router.equals(dev)) {
			String type = dev.getDeviceType();
		        if ((ROUTER_DEVICE.equals(type) || ROUTER_DEVICE_2.equals(type)) &&
			   dev.isRootDevice() &&
			   stringEquals(_router.getFriendlyName(), dev.getFriendlyName()) &&
			   stringEquals(_router.getUDN(), udn)) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("UP&P IGD device removed : " + dev.getFriendlyName());
				// TODO promote an IGD from _otherUDNs ??
				// For now, just clear the others so they can be promoted later
				// after a rescan.
				_otherUDNs.clear();
				_router = null;
				_service = null;
				_eventVars.clear();
				_serviceLacksAPM = false;
				if (!portsForwarded.isEmpty()) {
					fpc = forwardCallback;
					removeMap = new HashMap<ForwardPort, ForwardPortStatus>(portsForwarded.size());
					for (ForwardPort port : portsForwarded) {
						ForwardPortStatus fps = new ForwardPortStatus(ForwardPortStatus.DEFINITE_FAILURE,
                                                                      "UPnP device removed",
                                                                      port.portNumber);
					}
				}
				portsForwarded.clear();
			}
		}
		if (fpc != null) {
			fpc.portForwardStatus(removeMap);
		}
	}
	
	/**
	 *  EventListener callback -
	 *  unused for now - supported in miniupnpd as of 1.1
	 */
	public void eventNotifyReceived(String uuid, long seq, String varName, String value) {
		if (uuid == null || varName == null || value == null)
			return;
		if (varName.length() > 128 || value.length() > 128)
			return;
		String old = null;
		synchronized(lock) {
			if (_service == null || !uuid.equals(_service.getSID()))
				return;
			if (_eventVars.size() >= 20 && !_eventVars.containsKey(varName))
				return;
			old = _eventVars.put(varName, value);
		}
		// The following four variables are "evented":
		// PossibleConnectionTypes: {Unconfigured IP_Routed IP_Bridged}
		// ConnectionStatus: {Unconfigured Connecting Connected PendingDisconnect Disconnecting Disconnected}
		// ExternalIPAddress: string
		// PortMappingNumberOfEntries: int
		if (!value.equals(old)) {
			if (_log.shouldDebug())
				_log.debug("Event: " + varName + " changed from " + old + " to " + value);
		}
		// call callback...
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
	private boolean isNATPresent() {
		synchronized(lock) {
			return _router != null && _service != null;
		}
	}

	/**
	 * @return the external IPv4 address the NAT thinks we have.  Blocking.
	 * null if we can't find it.
	 */
	private String getNATAddress() {
		Service service;
		synchronized(lock) {
			if(!isNATPresent())
				return null;
			service = _service;
		}

		Action getIP = service.getAction("GetExternalIPAddress");
		if(getIP == null || !getIP.postControlAction())
			return null;

		Argument a = getIP.getOutputArgumentList().getArgument("NewExternalIPAddress");
		if (a == null)
			return null;
		String rv = a.getValue();
		// I2P some devices return 0.0.0.0 when not connected
		if ("0.0.0.0".equals(rv) || rv == null || rv.length() <= 0)
			return null;
		return rv;
	}

	/**
	 * @return the reported upstream bit rate in bits per second. -1 if it's not available. Blocking.
	 */
	private int getUpstreamMaxBitRate() {
		Service service;
		synchronized(lock) {
			if(!isNATPresent() || thinksWeAreDoubleNatted)
				return -1;
			service = _service;
		}

		Action getIP = service.getAction("GetLinkLayerMaxBitRates");
		if(getIP == null || !getIP.postControlAction())
			return -1;

		Argument a = getIP.getOutputArgumentList().getArgument("NewUpstreamMaxBitRate");
		if (a == null)
			return -1;
		try {
		    return Integer.parseInt(a.getValue());
		} catch (NumberFormatException nfe) {
		    return -1;
		}
	}
	
	/**
	 * @return the reported downstream bit rate in bits per second. -1 if it's not available. Blocking.
	 */
	private int getDownstreamMaxBitRate() {
		Service service;
		synchronized(lock) {
			if(!isNATPresent() || thinksWeAreDoubleNatted)
				return -1;
			service = _service;
		}

		Action getIP = service.getAction("GetLinkLayerMaxBitRates");
		if(getIP == null || !getIP.postControlAction())
			return -1;

		Argument a = getIP.getOutputArgumentList().getArgument("NewDownstreamMaxBitRate");
		if (a == null)
			return -1;
		try {
		    return Integer.parseInt(a.getValue());
		} catch (NumberFormatException nfe) {
		    return -1;
		}
	}
	
	/** debug only */
	private static void listStateTable(Service serv, StringBuilder sb) {
		ServiceStateTable table;
		try {
			table = serv.getServiceStateTable();
		} catch (RuntimeException e) {
			// getSCPDNode() returns null,
			// NPE at org.cybergarage.upnp.Service.getServiceStateTable(Service.java:526)
			sb.append(" : no state");
			return;
		}
		sb.append("<ul><small>");
		for(int i=0; i<table.size(); i++) {
			StateVariable current = table.getStateVariable(i);
			sb.append("<li>").append(DataHelper.escapeHTML(current.getName()))
			  .append(" : \"").append(DataHelper.escapeHTML(current.getValue()))
			  .append("\"</li>");
		}
		sb.append("</small></ul>");
	}

	/** debug only */
	private static void listActionsArguments(Action action, StringBuilder sb) {
		ArgumentList ar = action.getArgumentList();
		sb.append("<ol>");
		for(int i=0; i<ar.size(); i++) {
			Argument argument = ar.getArgument(i);
			if(argument == null ) continue;
			sb.append("<li><small>argument : ").append(DataHelper.escapeHTML(argument.getName()))
			  .append("</small></li>");
		}
		sb.append("</ol>");
	}
	
	/** debug only */
	private static void listActions(Service service, StringBuilder sb) {
		ActionList al = service.getActionList();
		sb.append("<ul>");
		for(int i=0; i<al.size(); i++) {
			Action action = al.getAction(i);
			if(action == null ) continue;
			sb.append("<li>").append(DataHelper.escapeHTML(action.getName()));
			listActionsArguments(action, sb);
			sb.append("</li>");
		}
		sb.append("</ul>");
	}
	
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
			Argument a = _lastArgumentList.getArgument(arg);
			if (a == null)
				return "";
			String rv = a.getValue();
			return DataHelper.escapeHTML(rv);
		}
	}

        private static final long UINT_MAX = (1L << 32) - 1;

	/**
	 *  @since 0.9.34
	 */
	private String toLong(String action, String arg, Service serv) {
		String rv = toString(action, arg, serv);
		if (rv != null && rv.length() > 0) {
			try {
				long l = Long.parseLong(rv);
				rv = DataHelper.formatSize2Decimal(l);
                                // spec says roll over to 0 but mine doesn't
                                if (l == UINT_MAX)
                                    rv = "&gt; " + rv;
			} catch (NumberFormatException nfe) {}
		}
		return rv;
	}

	/**
	 *  @since 0.9.34
	 */
	private String toTime(String action, String arg, Service serv) {
		String rv = toString(action, arg, serv);
		if (rv != null && rv.length() > 0) {
			try {
				long l = Long.parseLong(rv);
				rv = DataHelper.formatDuration2(l * 1000);
			} catch (NumberFormatException nfe) {}
		}
		return rv;
	}

	/**
	 *  @since 0.9.34
	 */
	private String toBoolean(String action, String arg, Service serv) {
		String rv = toString(action, arg, serv);
		return Boolean.toString("1".equals(rv));
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
			sb.append("<li>").append(_t("Service")).append(": ");
			// NOTE: Group all toString() of common actions together
			// to avoid excess fetches, since toString() caches.
			String type = serv.getServiceType();
			if("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1".equals(type)){
				sb.append(_t("WAN Common Interface Configuration"));
				sb.append("<ul><li>").append(_t("Status")).append(": ")
				  .append(toString("GetCommonLinkProperties", "NewPhysicalLinkStatus", serv));
				sb.append("<li>").append(_t("Type")).append(": ")
				  .append(toString("GetCommonLinkProperties", "NewWANAccessType", serv));
				sb.append("<li>").append(_t("Upstream")).append(": ")
				  .append(toLong("GetCommonLinkProperties", "NewLayer1UpstreamMaxBitRate", serv)).append("bps");
				sb.append("<li>").append(_t("Downstream")).append(": ")
				  .append(toLong("GetCommonLinkProperties", "NewLayer1DownstreamMaxBitRate", serv)).append("bps");
				if (_context.getBooleanProperty(PROP_ADVANCED)) {
					// don't bother translating
					sb.append("<li>").append("Sent: ")
					  .append(toLong("GetTotalBytesSent", "NewTotalBytesSent", serv)).append('B');
					sb.append("<li>").append("Received: ")
					  .append(toLong("GetTotalBytesReceived", "NewTotalBytesReceived", serv)).append('B');
					sb.append("<li>").append("Sent packets: ")
					  .append(toLong("GetTotalPacketsSent", "NewTotalPacketsSent", serv));
					sb.append("<li>").append("Received packets: ")
					  .append(toLong("GetTotalPacketsReceived", "NewTotalPacketsReceived", serv));
				}
			}else if(WAN_PPP_CONNECTION.equals(type)){
				sb.append(_t("WAN PPP Connection"));
				sb.append("<ul><li>").append(_t("Status")).append(": ")
				  .append(toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append("<li>").append(_t("Uptime")).append(": ")
				   .append(toTime("GetStatusInfo", "NewUptime", serv));
				sb.append("<li>").append(_t("Type")).append(": ")
				  .append(toString("GetConnectionTypeInfo", "NewConnectionType", serv));
				sb.append("<li>").append(_t("Upstream")).append(": ")
				  .append(toLong("GetLinkLayerMaxBitRates", "NewUpstreamMaxBitRate", serv)).append("bps");
				sb.append("<li>").append(_t("Downstream")).append(": ")
				  .append(toLong("GetLinkLayerMaxBitRates", "NewDownstreamMaxBitRate", serv)).append("bps");
				sb.append("<li>").append(_t("External IP")).append(": ")
				  .append(toString("GetExternalIPAddress", "NewExternalIPAddress", serv));
			}else if("urn:schemas-upnp-org:service:Layer3Forwarding:1".equals(type)){
				sb.append(_t("Layer 3 Forwarding"));
				sb.append("<ul><li>").append(_t("Default Connection Service")).append(": ")
				  .append(toString("GetDefaultConnectionService", "NewDefaultConnectionService", serv));
			} else if(WAN_IP_CONNECTION.equals(type) || WAN_IP_CONNECTION_2.equals(type)) {
				sb.append(_t("WAN IP Connection"));
				sb.append("<ul><li>").append(_t("Status")).append(": ")
				  .append(toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append("<li>").append(_t("Uptime")).append(": ")
				   .append(toTime("GetStatusInfo", "NewUptime", serv));
				String error = toString("GetStatusInfo", "NewLastConnectionError", serv);
				if (error != null && error.length() > 0 && !error.equals("ERROR_NONE"))
					sb.append("<li>").append("Last Error").append(": ").append(error);
				sb.append("<li>").append(_t("Type")).append(": ")
				  .append(toString("GetConnectionTypeInfo", "NewConnectionType", serv));
				sb.append("<li>").append(_t("External IP")).append(": ")
				  .append(toString("GetExternalIPAddress", "NewExternalIPAddress", serv));
			} else if(WAN_IPV6_CONNECTION.equals(type)) {
				sb.append("WAN IPv6 Connection");
				sb.append("<ul><li>").append("Firewall Enabled").append(": ")
				  .append(toBoolean("GetFirewallStatus", "FirewallEnabled", serv));
				sb.append("<li>").append("Pinhole Allowed").append(": ")
				   .append(toBoolean("GetFirewallStatus", "InboundPinholeAllowed", serv));
			}else if("urn:schemas-upnp-org:service:WANEthernetLinkConfig:1".equals(type)){
				sb.append(_t("WAN Ethernet Link Configuration"));
				sb.append("<ul><li>").append(_t("Status")).append(": ")
				  .append(toString("GetEthernetLinkStatus", "NewEthernetLinkStatus", serv));
			} else {
				sb.append(DataHelper.escapeHTML(type)).append("<ul>");
			}
			if (_context.getBooleanProperty(PROP_ADVANCED)) {
				sb.append("<li>Actions");
				listActions(serv, sb);
				sb.append("</li><li>States");
				listStateTable(serv, sb);
				sb.append("</li>");
			}
			sb.append("</ul>\n");
		}
		sb.append("</ul>\n");
	}
	
	private void listSubDev(String prefix, Device dev, StringBuilder sb){
                if (prefix == null)
			sb.append("<p>").append(_t("Found Device")).append(": ");
		else
			sb.append("<li>").append(_t("Subdevice")).append(": ");
		sb.append(DataHelper.escapeHTML(dev.getFriendlyName()));
                if (prefix == null) {
			String ip = getIP(dev);
			if (ip != null)
				sb.append("<br>IP: ").append(ip);
			String udn = dev.getUDN();
			if (udn != null)
				sb.append("<br>UDN: ").append(DataHelper.escapeHTML(udn));
		}
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
		sb.append("<h3 id=\"upnp\">").append(_t("UPnP Status")).append("</h3><div id=\"upnpscan\">");
		
		synchronized(_otherUDNs) {
			if (!_otherUDNs.isEmpty()) {
				sb.append(_t("Disabled UPnP Devices"));
				sb.append("<ul>");
				for (Map.Entry<String, String> e : _otherUDNs.entrySet()) {
					String udn = e.getKey();
					String name = e.getValue();
					sb.append("<li>").append(DataHelper.escapeHTML(name));
					sb.append("<br>UDN: ").append(DataHelper.escapeHTML(udn))
					  .append("</li>");
				}
				sb.append("</ul>");
			}
		}

		if(isDisabled) {
			sb.append("<p>");
			sb.append(_t("UPnP has been disabled; Do you have more than one UPnP Internet Gateway Device on your LAN ?"));
			return sb.toString();
		} else if(!isNATPresent()) {
			sb.append("<p>");
			sb.append(_t("UPnP has not found any UPnP-aware, compatible device on your LAN."));
			return sb.toString();
		}
		
		Device router;
		synchronized(lock) {
			router = _router;
		}
		if (router != null)
			listSubDev(null, router, sb);
		String addr = getNATAddress();
		sb.append("<p>");
		if (addr != null)
		    sb.append(_t("The current external IP address reported by UPnP is {0}", DataHelper.escapeHTML(addr)));
		else
		    sb.append(_t("The current external IP address is not available."));
		int downstreamMaxBitRate = getDownstreamMaxBitRate();
		int upstreamMaxBitRate = getUpstreamMaxBitRate();
		if(downstreamMaxBitRate > 0)
			sb.append("<br>").append(_t("UPnP reports the maximum downstream bit rate is {0}bits/sec", DataHelper.formatSize2Decimal(downstreamMaxBitRate)));
		if(upstreamMaxBitRate > 0)
			sb.append("<br>").append(_t("UPnP reports the maximum upstream bit rate is {0}bits/sec", DataHelper.formatSize2Decimal(upstreamMaxBitRate)));
		synchronized(lock) {
			for(ForwardPort port : portsToForward) {
				sb.append("<br>");
				if(portsForwarded.contains(port))
					// {0} is TCP or UDP
					// {1,number,#####} prevents 12345 from being output as 12,345 in the English locale.
					// If you want the digit separator in your locale, translate as {1}.
					sb.append(_t("{0} port {1,number,#####} was successfully forwarded by UPnP.", protoToString(port.protocol), port.portNumber));
				else
					sb.append(_t("{0} port {1,number,#####} was not forwarded by UPnP.", protoToString(port.protocol), port.portNumber));
			}
		}
		
		sb.append("</p></div>");
		return sb.toString();
	}
	
	/**
	 *  This always requests that the external port == the internal port, for now.
	 *  Blocking!
	 */
	private boolean addMapping(String protocol, int port, String description, ForwardPort fp) {
		Service service;
		synchronized(lock) {
			if(isDisabled || !isNATPresent() || _router == null) {
				_log.error("Can't addMapping: " + isDisabled + " " + isNATPresent() + " " + _router);
				return false;
			}
			service = _service;
		}
		
		// Just in case...
                // this confuses my linksys? - zzz
		//removeMapping(protocol, port, fp, true);
		
		Action add = service.getAction("AddPortMapping");
		if(add == null) {
                    if (_serviceLacksAPM) {
			if (_log.shouldLog(Log.WARN))
			    _log.warn("Couldn't find AddPortMapping action!");
		    } else {	
			_serviceLacksAPM = true;
			_log.logAlways(Log.WARN, "UPnP device does not support port forwarding");
		    }
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
	*  @return IP or null
	 * @since 0.9.34
	 */
	private static String getIP(Device dev) {
		// see ControlRequest.setRequestHost()
		String rv = null;
		String him = dev.getURLBase();
		if (him != null && him.length() > 0) {
			try {
				URI url = new URI(him);
				rv = url.getHost();
			} catch (URISyntaxException use) {}
		}
		if (rv == null) {
			him = dev.getLocation();
			if (him != null && him.length() > 0) {
				try {
					URI url = new URI(him);
					rv = url.getHost();
				} catch (URISyntaxException use) {}
			}
		}
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
		String hisIP = getIP(_router);
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
		Service service;
		synchronized(lock) {
			if(isDisabled || !isNATPresent()) {
				_log.error("Can't removeMapping: " + isDisabled + " " + isNATPresent() + " " + _router);
				return false;
			}
			service = _service;
		}
		
		Action remove = service.getAction("DeletePortMapping");
		if(remove == null) {
		    if (_log.shouldLog(Log.WARN))
			_log.warn("Couldn't find DeletePortMapping action!");
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
		if (_log.shouldLog(Log.INFO))
			_log.info("UP&P Forwarding "+ports.size()+" ports...", new Exception());
		synchronized(lock) {
			if(forwardCallback != null && forwardCallback != cb && cb != null) {
				_log.error("ForwardPortCallback changed from "+forwardCallback+" to "+cb+" - using new value, but this is very strange!");
			}
			forwardCallback = cb;
			if (portsToForward.isEmpty()) {
				portsToForward.addAll(ports);
				portsToForwardNow = ports;
				portsToDumpNow = null;
			} else if(ports.isEmpty()) {
				portsToDumpNow = portsToForward;
				portsToForward.clear();
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
				portsToForward.clear();
				portsToForward.addAll(ports);
			}
			if(_router == null) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("No UPnP router available to update");
				return; // When one is found, we will do the forwards
			}
		}
		if(portsToDumpNow != null && !portsToDumpNow.isEmpty())
			unregisterPorts(portsToDumpNow);
		if(portsToForwardNow != null && !portsToForwardNow.isEmpty())
			registerPorts(portsToForwardNow);
	}

        private static String protoToString(int p) {
		if(p == ForwardPort.PROTOCOL_UDP_IPV4)
			return "UDP";
		if(p == ForwardPort.PROTOCOL_TCP_IPV4)
			return "TCP";
		return "?";
	}

	private static final AtomicInteger __id = new AtomicInteger();

	/**
	 *  postControlAction() can take many seconds, especially if it's failing,
         *  and onChangePublicPorts() may be called from threads we don't want to slow down,
         *  so throw this in a thread.
         */
	private void registerPorts(Set<ForwardPort> portsToForwardNow) {
		if (_serviceLacksAPM) {
                    if (_log.shouldLog(Log.WARN))
			_log.warn("UPnP device does not support port forwarding");
		    Map<ForwardPort, ForwardPortStatus> map =
			new HashMap<ForwardPort, ForwardPortStatus>(portsToForwardNow.size());
		    for (ForwardPort port : portsToForwardNow) {
			ForwardPortStatus fps = new ForwardPortStatus(ForwardPortStatus.DEFINITE_FAILURE,
                                                                      "UPnP device does not support port forwarding",
                                                                      port.portNumber);
			map.put(port, fps);
		    }
		    forwardCallback.portForwardStatus(map);
		    return;
		}
		if (_log.shouldLog(Log.INFO))
			_log.info("Starting thread to forward " + portsToForwardNow.size() + " ports");
	        Thread t = new I2PThread(new RegisterPortsThread(portsToForwardNow));
		t.setName("UPnP Port Opener " + __id.incrementAndGet());
		t.setDaemon(true);
		t.start();
	}

	private class RegisterPortsThread implements Runnable {
		private Set<ForwardPort> portsToForwardNow;

		public RegisterPortsThread(Set<ForwardPort> ports) {
			portsToForwardNow = ports;
		}

		public void run() {
			Map<ForwardPort, ForwardPortStatus> map =
				new HashMap<ForwardPort, ForwardPortStatus>(portsToForwardNow.size());
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
				map.put(port, fps);
			}
			forwardCallback.portForwardStatus(map);
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
	        Thread t = new I2PThread(new UnregisterPortsThread(portsToForwardNow));
		t.setName("UPnP Port Closer " + __id.incrementAndGet());
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

	/**
	 *  Dumps out device info in semi-HTML format
	 */
	public static void main(String[] args) throws Exception {
		Properties props = new Properties();
                props.setProperty(PROP_ADVANCED, "true");
		I2PAppContext ctx = new I2PAppContext(props);
		UPnP cp = new UPnP(ctx);
		org.cybergarage.upnp.UPnP.setEnable(org.cybergarage.upnp.UPnP.USE_ONLY_IPV4_ADDR);
		Debug.initialize(ctx);
		cp.setHTTPPort(49152 + ctx.random().nextInt(5000));
		cp.setSSDPPort(54152 + ctx.random().nextInt(5000));
		long start = System.currentTimeMillis();
		cp.start();
		long s2 = System.currentTimeMillis();
		System.err.println("Start took " + (s2 - start) + "ms");
		System.err.println("Searching for UPnP devices");
		start = System.currentTimeMillis();
		cp.search();
		s2 = System.currentTimeMillis();
		System.err.println("Search kickoff took " + (s2 - start) + "ms");
		System.err.println("Waiting 10 seconds for responses");
		Thread.sleep(10000);

			DeviceList list = cp.getDeviceList();
			if (list.isEmpty()) {
				System.err.println("No UPnP devices found");
				System.exit(1);
			}
			System.err.println("Found " + list.size() + " devices.");
			System.err.println("Redirect the following output to an html file and view in a browser.");
			StringBuilder sb = new StringBuilder();
			Iterator<Device> it = list.iterator();
			int i = 0;
			while(it.hasNext()) {
				Device device = it.next();
				cp.listSubDev(device.toString(), device, sb);
				System.out.println("<h3>Device " + (++i) +
				                   ": " + DataHelper.escapeHTML(device.getFriendlyName()) + "</h3>");
				System.out.println("<p>UDN: " + DataHelper.escapeHTML(device.getUDN()));
				System.out.println("<br>IP: " + getIP(device));
				System.out.println(sb.toString());
				sb.setLength(0);
			}

		System.exit(0);
	}

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     *  Translate
     */
    private final String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     */
    private final String _t(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     */
    private final String _t(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }
}
