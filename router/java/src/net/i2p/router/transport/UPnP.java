/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package net.i2p.router.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.util.Log;
import net.i2p.I2PAppContext;

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
public class UPnP extends ControlPoint implements DeviceChangeListener, EventListener {
	private Log _log;
	private I2PAppContext _context;
	
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
	private Set<ForwardPort> portsForwarded;
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
		return super.start();
	}

	public void terminate() {
		unregisterPortMappings();
		super.stop();
		_router = null;
		_service = null;
	}
	
	public DetectedIP[] getAddress() {
		_log.info("UP&P.getAddress() is called \\o/");
		if(isDisabled) {
			_log.warn("Plugin has been disabled previously, ignoring request.");
			return null;
		} else if(!isNATPresent()) {
			_log.warn("No UP&P device found, detection of the external ip address using the plugin has failed");
			return null;
		}
		
		DetectedIP result = null;
		final String natAddress = getNATAddress();
                if (natAddress == null || natAddress.length() <= 0) {
			_log.warn("No external address returned");
			return null;
		}
		try {
			InetAddress detectedIP = InetAddress.getByName(natAddress);

			short status = DetectedIP.NOT_SUPPORTED;
			thinksWeAreDoubleNatted = !TransportImpl.isPubliclyRoutable(detectedIP.getAddress());
			// If we have forwarded a port AND we don't have a private address
			_log.warn("NATAddress: \"" + natAddress + "\" detectedIP: " + detectedIP + " double? " + thinksWeAreDoubleNatted);
			if((portsForwarded.size() > 1) && (!thinksWeAreDoubleNatted))
				status = DetectedIP.FULL_INTERNET;
			
			result = new DetectedIP(detectedIP, status);
			
			_log.warn("Successful UP&P discovery :" + result);
			
			return new DetectedIP[] { result };
		} catch (UnknownHostException e) {
			_log.error("Caught an UnknownHostException resolving " + natAddress, e);
			return null;
		}
	}
	
	public void deviceAdded(Device dev) {
		synchronized (lock) {
			if(isDisabled) {
				_log.warn("Plugin has been disabled previously, ignoring new device.");
				return;
			}
		}
		if(!ROUTER_DEVICE.equals(dev.getDeviceType()) || !dev.isRootDevice()) {
			_log.warn("UP&P non-IGD device found, ignoring : " + dev.getFriendlyName());
			return; // ignore non-IGD devices
		} else if(isNATPresent()) {
                        // maybe we should see if the old one went away before ignoring the new one?
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
	
	public void deviceRemoved(Device dev ){
		_log.warn("UP&P device removed : " + dev.getFriendlyName() + " UDN: " + dev.getUDN());
		synchronized (lock) {
			if(_router == null) return;
			// I2P this wasn't working
			//if(_router.equals(dev)) {
		        if(ROUTER_DEVICE.equals(dev.getDeviceType()) &&
			   dev.isRootDevice() &&
			   stringEquals(_router.getFriendlyName(), dev.getFriendlyName()) &&
			   stringEquals(_router.getUDN(), dev.getUDN())) {
				_log.warn("UP&P IGD device removed : " + dev.getFriendlyName());
				_router = null;
				_service = null;
			}
		}
	}
	
	/** event callback - unused for now - how many devices support events? */
	public void eventNotifyReceived(String uuid, long seq, String varName, String value) {
		if (_log.shouldLog(Log.WARN))
			_log.error("Event: " + uuid + ' ' + seq + ' ' + varName + '=' + value);
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
	private Object toStringLock = new Object();
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
		if (sl.size() <= 0)
			return;
		sb.append("<ul>\n");
		for(int i=0; i<sl.size(); i++) {
			Service serv = sl.getService(i);
			if(serv == null) continue;
			sb.append("<li>Service: ");
			if("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1".equals(serv.getServiceType())){
				sb.append("WAN Common Interface Config<ul>");
				sb.append("<li>Status: " + toString("GetCommonLinkProperties", "NewPhysicalLinkStatus", serv));
				sb.append("<li>Type: " + toString("GetCommonLinkProperties", "NewWANAccessType", serv));
				sb.append("<li>Upstream: " + toString("GetCommonLinkProperties", "NewLayer1UpstreamMaxBitRate", serv));
				sb.append("<li>Downstream: " + toString("GetCommonLinkProperties", "NewLayer1DownstreamMaxBitRate", serv) + "<br>");
			}else if("urn:schemas-upnp-org:service:WANPPPConnection:1".equals(serv.getServiceType())){
				sb.append("WAN PPP Connection<ul>");
				sb.append("<li>Status: " + toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append("<li>Type: " + toString("GetConnectionTypeInfo", "NewConnectionType", serv));
				sb.append("<li>Upstream: " + toString("GetLinkLayerMaxBitRates", "NewUpstreamMaxBitRate", serv));
				sb.append("<li>Downstream: " + toString("GetLinkLayerMaxBitRates", "NewDownstreamMaxBitRate", serv) + "<br>");
				sb.append("<li>External IP: " + toString("GetExternalIPAddress", "NewExternalIPAddress", serv) + "<br>");
			}else if("urn:schemas-upnp-org:service:Layer3Forwarding:1".equals(serv.getServiceType())){
				sb.append("Layer 3 Forwarding<ul>");
				sb.append("<li>Default Connection Service: " + toString("GetDefaultConnectionService", "NewDefaultConnectionService", serv));
			}else if(WAN_IP_CONNECTION.equals(serv.getServiceType())){
				sb.append("WAN IP Connection<ul>");
				sb.append("<li>Status: " + toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append("<li>Type: " + toString("GetConnectionTypeInfo", "NewConnectionType", serv));
				sb.append("<li>External IP: " + toString("GetExternalIPAddress", "NewExternalIPAddress", serv) + "<br>");
			}else if("urn:schemas-upnp-org:service:WANEthernetLinkConfig:1".equals(serv.getServiceType())){
				sb.append("WAN Ethernet Link Config<ol>");
				sb.append("<li>Status: " + toString("GetEthernetLinkStatus", "NewEthernetLinkStatus", serv) + "<br>");
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
			sb.append("Device: ");
		else
			sb.append("<li>Subdevice: ");
		sb.append(dev.getFriendlyName());
		listSubServices(dev, sb);
		
		DeviceList dl = dev.getDeviceList();
		if (dl.size() <= 0)
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
		sb.append("<a name=\"upnp\"></a><b>UPnP Status:</b><br />");
		
		if(isDisabled) {
			sb.append("UPnP has been disabled; Do you have more than one UPnP Internet Gateway Device on your LAN ?");
			return sb.toString();
		} else if(!isNATPresent()) {
			sb.append("UPnP has not found any UPnP-aware, compatible device on your LAN.");
			return sb.toString();
		}
		
		// FIXME L10n!
		sb.append("<p>Found ");
		listSubDev(null, _router, sb);
		String addr = getNATAddress();
		if (addr != null)
		    sb.append("<br>The current external IP address reported by UPnP is " + addr);
		else
		    sb.append("<br>The current external IP address is not available.");
		int downstreamMaxBitRate = getDownstreamMaxBitRate();
		int upstreamMaxBitRate = getUpstreamMaxBitRate();
		if(downstreamMaxBitRate > 0)
			sb.append("<br>UPnP reports the max downstream bit rate is : " + downstreamMaxBitRate+ " bits/sec\n");
		if(upstreamMaxBitRate > 0)
			sb.append("<br>UPnP reports the max upstream bit rate is : " + upstreamMaxBitRate+ " bits/sec\n");
		synchronized(lock) {
			if(portsToForward != null) {
				for(ForwardPort port : portsToForward) {
					sb.append("<br>" + protoToString(port.protocol) + " port " + port.portNumber + " for " + port.name);
					if(portsForwarded.contains(port))
						sb.append(" has been forwarded successfully by UPnP.\n");
					else
						sb.append(" has not been forwarded by UPnP.\n");
				}
			}
		}
		
		sb.append("</p>");
		return sb.toString();
	}
	
	/** blocking */
	private boolean addMapping(String protocol, int port, String description, ForwardPort fp) {
		if(isDisabled || !isNATPresent() || _router == null) {
                        _log.error("Can't addMapping: " + isDisabled + " " + isNATPresent() + " " + _router);
			return false;
                }
		
		// Just in case...
                // this confuses my linksys? - zzz
		removeMapping(protocol, port, fp, true);
		
		Action add = _service.getAction("AddPortMapping");
		if(add == null) {
		    _log.error("Couldn't find AddPortMapping action!");
		    return false;
		}
		    
		
		add.setArgumentValue("NewRemoteHost", "");
		add.setArgumentValue("NewExternalPort", port);
		add.setArgumentValue("NewInternalClient", _router.getInterfaceAddress());
		add.setArgumentValue("NewInternalPort", port);
		add.setArgumentValue("NewProtocol", protocol);
		add.setArgumentValue("NewPortMappingDescription", description);
		add.setArgumentValue("NewEnabled","1");
		add.setArgumentValue("NewLeaseDuration", 0);
		
		if(add.postControlAction()) {
			synchronized(lock) {
				portsForwarded.add(fp);
			}
			return true;
		} else return false;
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
		
		if(!noLog)
			_log.warn("UPnP: Removed mapping for "+fp.name+" "+port+" / "+protocol);
		return retval;
	}

	/** non-blocking */
	public void onChangePublicPorts(Set<ForwardPort> ports, ForwardPortCallback cb) {
		Set<ForwardPort> portsToDumpNow = null;
		Set<ForwardPort> portsToForwardNow = null;
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
			} else if(ports == null || ports.isEmpty()) {
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
			if(_router == null) return; // When one is found, we will do the forwards
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
				if (proto.length() <= 1) {
					HashMap<ForwardPort, ForwardPortStatus> map = new HashMap<ForwardPort, ForwardPortStatus>();
					map.put(port, new ForwardPortStatus(ForwardPortStatus.DEFINITE_FAILURE, "Protocol not supported", port.portNumber));
					forwardCallback.portForwardStatus(map);
					continue;
				}
				if(tryAddMapping(proto, port.portNumber, port.name, port)) {
					HashMap<ForwardPort, ForwardPortStatus> map = new HashMap<ForwardPort, ForwardPortStatus>();
					map.put(port, new ForwardPortStatus(ForwardPortStatus.MAYBE_SUCCESS, "Port apparently forwarded by UPnP", port.portNumber));
					forwardCallback.portForwardStatus(map);
					continue;
				} else {
					HashMap<ForwardPort, ForwardPortStatus> map = new HashMap<ForwardPort, ForwardPortStatus>();
					map.put(port, new ForwardPortStatus(ForwardPortStatus.PROBABLE_FAILURE, "UPnP port forwarding apparently failed", port.portNumber));
					forwardCallback.portForwardStatus(map);
					continue;
				}
			}
		}
	}

	/**
	 *  postControlAction() can take many seconds, especially if it's failing,
         *  and onChangePublicPorts() may be called from threads we don't want to slow down,
         *  so throw this in a thread.
         */
	private void unregisterPorts(Set<ForwardPort> portsToForwardNow) {
	        Thread t = new Thread(new UnregisterPortsThread(portsToForwardNow));
		t.setName("UPnP Port Opener " + (++__id));
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
}
