/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package net.i2p.router.transport;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

import org.cybergarage.http.HTTPServer;
import org.cybergarage.http.HTTPServerList;
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
import org.cybergarage.upnp.ssdp.SSDPNotifySocket;
import org.cybergarage.upnp.ssdp.SSDPNotifySocketList;
import org.cybergarage.upnp.ssdp.SSDPPacket;
import org.cybergarage.upnp.ssdp.SSDPSearchResponseSocket;
import org.cybergarage.upnp.ssdp.SSDPSearchResponseSocketList;
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
	private Service _service6;
	// UDN -> device
	private final Map<String, Device> _otherUDNs;
	private final Map<String, String> _eventVars;
	private volatile boolean _serviceLacksAPM;
	private volatile boolean _permanentLeasesOnly;
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
	
	public UPnP(I2PAppContext context, int ssdpPort, int httpPort, InetAddress[] binds) {
		super(ssdpPort, httpPort, binds);
		_context = context;
		_log = _context.logManager().getLog(UPnP.class);
		portsToForward = new HashSet<ForwardPort>();
		portsForwarded = new HashSet<ForwardPort>();
		_otherUDNs = new HashMap<String, Device>(4);
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
			_service6 = null;
			_serviceLacksAPM = false;
			_permanentLeasesOnly = false;
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
		Service service;
		synchronized(lock) {
			if (!isNATPresent()) {
				if (_log.shouldLog(Log.WARN))
					_log.warn("No UP&P device found, detection of the external ip address using the plugin has failed");
				return null;
			}
			service = _service;
		}
		
		final String natAddress = getNATAddress(service);
                if (natAddress == null || natAddress.length() <= 0) {
			if (_log.shouldLog(Log.WARN))
				_log.warn("No external address returned");
			return null;
		}
		DetectedIP result = null;
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
		if (!dev.hasUDN()) {
			if (_log.shouldInfo())
				_log.info("Bad device, no UDN");
			return;
		}
                String udn = dev.getUDN();
                String name = dev.getFriendlyName();
		if (name == null)
			name = udn;
		String type = dev.getDeviceType();
		boolean isIGD = (ROUTER_DEVICE.equals(type) || ROUTER_DEVICE_2.equals(type)) && dev.isRootDevice();
		name += isIGD ? " IGD" : (' ' + type);
		String ip = getIP(dev);
		if (ip != null)
			name += ' ' + ip;
		if(!isIGD) {
			if (_log.shouldInfo())
				_log.info("UP&P non-IGD device found, ignoring " + name + ' ' + dev.getDeviceType());
			synchronized (lock) {
				_otherUDNs.put(udn, dev);
			}
			return; // ignore non-IGD devices
		}
		
		boolean ignore = false;
		String toIgnore = _context.getProperty(PROP_IGNORE);
		if (toIgnore != null) {
			String[] ignores = DataHelper.split(toIgnore, "[,; \r\n\t]");
			for (int i = 0; i < ignores.length; i++) {
				if (ignores[i].equals(udn)) {
					ignore = true;
					if (_log.shouldWarn())
						_log.warn("Ignoring by config: " + name + " UDN: " + udn);
					break;
				}
			}
		}
		Set<String> myAddresses = Addresses.getAddresses(true, false);  // yes local, no IPv6
		if (!ignore && !ALLOW_SAME_HOST && ip != null && myAddresses.contains(ip)) {
			ignore = true;
			if (_log.shouldWarn())
				_log.warn("Ignoring UPnP on same host: " + name + " UDN: " + udn);
		}

		// IP check
		if (!ignore) {
			SSDPPacket pkt = dev.getSSDPPacket();
			if (pkt != null) {
				String pktIP = pkt.getRemoteAddress();
				if (!stringEquals(ip, pktIP)) {
					ignore = true;
					if (_log.shouldWarn())
						_log.warn("Ignoring UPnP with IP mismatch: " + name + " UDN: " + udn);
				}
			}
		}

		// Find valid service
		List<Service> services = null;
		Service service = null;
		String extIP = null;
		boolean subscriptionFailed = false;
		if (!ignore) {
			services = discoverService(dev);
			if (services == null) {
				ignore = true;
			} else {
				service = services.get(0);
				// does it have an external IP?
				extIP = getNATAddress(service);
				if (extIP == null) {
					// this would meet all our qualifications if connected.
					// subscribe to it, in case it becomes connected.
					boolean ok = subscribe(service);
					if (ok) {
						// we can't trust that events will work even if the subscription succeeded.
						//ignore = true;
						if (_log.shouldWarn())
							_log.warn("UPnP subscribed but ignoring disconnected device " + name + " UDN: " + udn);
					} else {
						subscriptionFailed = true;
						// we can't ignore it, as it won't tell us when it connects
						if (_log.shouldWarn())
							_log.warn("Failed subscription to disconnected device " + name + " UDN: " + udn);
					}
				} else {
					if (_log.shouldWarn())
						_log.warn("UPnP found device " + name + " UDN: " + udn + " with external IP: " + extIP);
				}
			}
		}

		ForwardPortCallback fpc = null;
		Map<ForwardPort, ForwardPortStatus> removeMap = null;
		synchronized(lock) {
			if (ignore) {
				_otherUDNs.put(udn, dev);
				return;
			}
			if (_router != null && _service != null) {
				if (udn.equals(_router.getUDN())) // oops
					return;
				ignore = true;
				String curIP = null;
				if (extIP != null) {
					curIP = getNATAddress(_service);
					if (curIP == null) {
						// new one is better
						ignore = false;
					} else {
						byte[] cur = Addresses.getIP(curIP);
						byte[] ext = Addresses.getIP(extIP);
						if (cur != null && ext != null &&
						    TransportUtil.isPubliclyRoutable(ext, false) &&
						    !TransportUtil.isPubliclyRoutable(cur, false)) {
							// new one is better
							ignore = false;
						}
					}
				}
				if (ignore) {
					// this meets all our qualifications, but we already have one.
					// subscribe to it, in case ours goes away
					if (_log.shouldWarn())
						_log.warn("UPnP ignoring additional device " + name + " UDN: " + udn);
					_otherUDNs.put(udn, dev);
					if (!subscriptionFailed) {
						boolean ok = subscribe(service);
						if (_log.shouldInfo()) {
							if (ok)
								_log.info("Subscribed to additional device " + name + " UDN: " + udn);
							else
								_log.info("Failed subscription to additional device " + name + " UDN: " + udn);
						}
						return;
					}
				} else {
			                String oldudn = _router.getUDN();
					if (_log.shouldWarn()) {
						String oldname = _router.getFriendlyName();
						if (oldname == null)
							oldname = "";
						oldname += " IGD";
						String oldip = getIP(_router);
						if (oldip != null)
							oldname += ' ' + oldip;
						_log.warn("Replacing device " + oldname + " (external IP " + curIP + ") with new device " + name + " UDN: " + udn + " external IP: " + extIP);
					}
					_otherUDNs.put(oldudn, _router);
				}
				if (!portsForwarded.isEmpty()) {
					fpc = forwardCallback;
					removeMap = new HashMap<ForwardPort, ForwardPortStatus>(portsForwarded.size());
					for (ForwardPort port : portsForwarded) {
						ForwardPortStatus fps = new ForwardPortStatus(ForwardPortStatus.DEFINITE_FAILURE,
						                                              "UPnP device changed",
						                                              port.portNumber);
						removeMap.put(port, fps);
					}
				}
				portsForwarded.clear();
			}

			// We have found the device we need
			_otherUDNs.remove(udn);
			_eventVars.clear();
			_router = dev;
			_service = service;
			if (services.size() > 1)
				_service6 = services.get(1);
			_permanentLeasesOnly = false;
		}
		if (fpc != null)
			fpc.portForwardStatus(removeMap);

		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P IGD found : " + name + " UDN: " + udn + " lease time: " + dev.getLeaseTime());
		
		if (!subscriptionFailed) {
			boolean ok = subscribe(service);
			if (_log.shouldInfo()) {
				if (ok)
					_log.info("Subscribed to our device " + name + " UDN: " + udn);
				else
					_log.info("Failed subscription to our device " + name + " UDN: " + udn);
			}
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
	 * The first Service will be the IPv4 Service.
	 * The second Service, if present, will be the IPv6 Service.
	 *
	 * @return the list of services, non-empty, one or two entries, or null
	 */
	private List<Service> discoverService(Device router) {
			for (Device current : router.getDeviceList()) {
				String type = current.getDeviceType();
				if (!(WAN_DEVICE.equals(type) || WAN_DEVICE_2.equals(type)))
					continue;

				DeviceList l = current.getDeviceList();
				for (int i=0;i<current.getDeviceList().size();i++) {
					Device current2 = l.getDevice(i);
					type = current2.getDeviceType();
					if (!(WANCON_DEVICE.equals(type) || WANCON_DEVICE_2.equals(type)))
						continue;
					
					Service service = current2.getService(WAN_IP_CONNECTION_2);
					if (service == null) {
						service = current2.getService(WAN_IP_CONNECTION);
						if (service == null) {
							service = current2.getService(WAN_PPP_CONNECTION);
							if (service == null) {
								if (_log.shouldWarn())
									_log.warn(_router.getFriendlyName() + " doesn't have any recognized connection type; we won't be able to use it!");
							}
						}
					}
					if (service != null) {
						Service svc2 = current2.getService(WAN_IPV6_CONNECTION);
						if (svc2 != null) {
							List<Service> rv = new ArrayList<Service>(2);
							rv.add(service);
							rv.add(svc2);
							return rv;
						}
						return Collections.singletonList(service);
					}
				}
			}

		return null;
	}
	
	private boolean tryAddMapping(String protocol, int port, String description, ForwardPort fp) {
		if (_log.shouldWarn())
			_log.warn("Registering a port mapping for " + port + "/" + protocol + " IPv" + (fp.isIP6 ? '6' : '4'));
		int nbOfTries = 0;
		final int maxTries = fp.isIP6 ? 1 : 3;
		boolean isPortForwarded = false;
		while ((!_serviceLacksAPM) && nbOfTries++ < maxTries) {
			//isPortForwarded = addMapping(protocol, port, "I2P " + description, fp);
			isPortForwarded = addMapping(protocol, port, description, fp);
			if(isPortForwarded || _serviceLacksAPM)
				break;
			if (++nbOfTries >= maxTries)
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
		if (!dev.hasUDN())
			return;
                String udn = dev.getUDN();
		if (_log.shouldLog(Log.WARN))
			_log.warn("UP&P device removed : " + dev.getFriendlyName() + " UDN: " + udn);
		ForwardPortCallback fpc = null;
		Map<ForwardPort, ForwardPortStatus> removeMap = null;
		boolean runSearch = false;
		synchronized (lock) {
			_otherUDNs.remove(udn);
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
				runSearch = true;
				_router = null;
				_service = null;
				_service6 = null;
				_eventVars.clear();
				_serviceLacksAPM = false;
				_permanentLeasesOnly = false;
				if (!portsForwarded.isEmpty()) {
					fpc = forwardCallback;
					removeMap = new HashMap<ForwardPort, ForwardPortStatus>(portsForwarded.size());
					for (ForwardPort port : portsForwarded) {
						ForwardPortStatus fps = new ForwardPortStatus(ForwardPortStatus.DEFINITE_FAILURE,
                                                                      "UPnP device removed",
                                                                      port.portNumber);
						removeMap.put(port, fps);
					}
				}
				portsForwarded.clear();
			}
		}
		if (fpc != null) {
			fpc.portForwardStatus(removeMap);
		}
		if (runSearch) {
			retryOtherDevices();
			search();
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
		Device newdev = null;
		synchronized(lock) {
			if(_eventVars.size() >= 20 && !_eventVars.containsKey(varName)) {
				if (_log.shouldDebug())
					_log.debug("Ignoring event from " + uuid + ": " + varName + " changed to " + value);
				return;
			}
			if (_service == null || !uuid.equals(_service.getSID())) {
				if (varName.equals("ConnectionStatus") && value.equals("Connected")) {
					newdev = SIDtoDevice(uuid);
					if (newdev == null && _log.shouldInfo())
						_log.debug("Can't map event SID " + uuid + " to device");
				}
				if (newdev == null) {
					if (_log.shouldDebug())
						_log.debug("Ignoring event from " + uuid + ": " + varName + " changed to " + value);
					return;
				}
			}
			if (newdev == null)
				old = _eventVars.put(varName, value);
		}
		if (newdev != null) {
			if (_log.shouldWarn())
				_log.warn("Possibly promoting device on connected event: " + newdev.getUDN());
			deviceAdded(newdev);
			return;
		}

		// The following four variables are "evented":
		// PossibleConnectionTypes: {Unconfigured IP_Routed IP_Bridged}
		// ConnectionStatus: {Unconfigured Connecting Connected PendingDisconnect Disconnecting Disconnected}
		// ExternalIPAddress: string
		// PortMappingNumberOfEntries: int
		if (!value.equals(old)) {
			if (_log.shouldDebug())
				_log.debug("Event: " + varName + " changed from " + old + " to " + value);
			if (varName.equals("ConnectionStatus") && "Connected".equals(old)) {
				if (_log.shouldWarn())
					_log.warn("Device connection change to: " + value + ", starting search");
				retryOtherDevices();
				search();
			}
		}
		// call callback...
	}

	/**
	 * @param sid non-null
	 * @return device or null
	 * @since 0.9.46
	 */
	private Device SIDtoDevice(String sid) {
		synchronized (lock) {
			for (Device dev : _otherUDNs.values()) {
				String type = dev.getDeviceType();
				boolean isIGD = (ROUTER_DEVICE.equals(type) || ROUTER_DEVICE_2.equals(type)) && dev.isRootDevice();
				if (!isIGD)
					continue;
				List<Service> services = discoverService(dev);
				if (services == null)
					continue;
				for (Service service : services) {
					if (sid.equals(service.getSID()))
						return dev;
				}
			}
		}
		return null;
	}

	/**
	 * Go through the other devices, try re-adding them
	 * @since 0.9.46
	 */
	private void retryOtherDevices() {
		int sz = _otherUDNs.size();
		if (sz <= 0)
			return;
		if (_log.shouldWarn())
			_log.warn("Device change, retrying " + sz + " other devices");
                List<Device> others = new ArrayList<Device>(sz);
		synchronized (lock) {
			others.addAll(_otherUDNs.values());
		}
		for (Device dev : others) {
			deviceAdded(dev);
		}
	}

	/**
	 * Get the addresses we want to bind to
	 *
	 * @since 0.9.46
	 */
	static Set<String> getLocalAddresses() {
		Set<String> addrs = Addresses.getAddresses(true, false, false);
		// remove public addresses
		// see TransportManager.startListening()
		for (Iterator<String> iter = addrs.iterator(); iter.hasNext(); ) {
			String addr = iter.next();
			byte[] ip = Addresses.getIP(addr);
			if (ip == null || TransportUtil.isPubliclyRoutable(ip, false))
				iter.remove();
		}
		return addrs;
	}

	/**
	 * Update the SSDPSearchResponseSocketList,
	 * SSDPNotifySocketList, and HTTPServerList every time.
	 * Otherwise, we are just listening on the interfaces that were present when started.
	 *
	 * @since 0.9.46
	 */
	private void updateInterfaces() {
		Set<String> addrs = getLocalAddresses();
		Set<String> oldaddrs = new HashSet<String>(addrs.size());

		// protect against list mod in super.stop()
		synchronized(this) {
			// we do this one first because we can detect failure before adding
			HTTPServerList hlist = getHTTPServerList();
			for (Iterator<HTTPServer> iter = hlist.iterator(); iter.hasNext(); ) {
				HTTPServer skt = iter.next();
				String addr = skt.getBindAddress();
				int slash = addr.indexOf('/');
				if (slash >= 0)
					addr = addr.substring(slash + 1);
				if (!addrs.contains(addr)) {
					iter.remove();
					skt.close();
					skt.stop();
					if (_log.shouldWarn())
						_log.warn("Closed HTTP server socket: " + addr);
				}
				oldaddrs.add(addr);
			}
			for (Iterator<String> iter = addrs.iterator(); iter.hasNext(); ) {
				String addr = iter.next();
				if (!oldaddrs.contains(addr)) {
					HTTPServer socket = new HTTPServer();
					boolean ok = socket.open(addr, getHTTPPort());
					if (ok) {
						socket.addRequestListener(this);
						socket.start();
						hlist.add(socket);
						if (_log.shouldWarn())
							_log.warn("Added HTTP server socket: " + addr);
					} else {
						// so we don't attempt to add to the other lists below
						iter.remove();
						if (_log.shouldWarn())
							_log.warn("open() failed on new HTTP server socket: " + addr);
					}
				}
			}

			oldaddrs.clear();
			SSDPSearchResponseSocketList list = getSSDPSearchResponseSocketList();
			for (Iterator<SSDPSearchResponseSocket> iter = list.iterator(); iter.hasNext(); ) {
				SSDPSearchResponseSocket skt = iter.next();
				String addr = skt.getLocalAddress();
				if (!addrs.contains(addr)) {
					iter.remove();
					skt.setControlPoint(null);
					skt.close();
					skt.stop();
					if (_log.shouldWarn())
						_log.warn("Closed SSDP search response socket: " + addr);
				}
				oldaddrs.add(addr);
			}
			for (String addr : addrs) {
				if (!oldaddrs.contains(addr)) {
					// TODO this calls open() in constructor, fails silently
					SSDPSearchResponseSocket socket = new SSDPSearchResponseSocket(addr, getSSDPPort());
					socket.setControlPoint(this);
					socket.start();
					list.add(socket);
					if (_log.shouldWarn())
						_log.warn("Added SSDP search response socket: " + addr);
				}
			}

			oldaddrs.clear();
			SSDPNotifySocketList nlist = getSSDPNotifySocketList();
			for (Iterator<SSDPNotifySocket> iter = nlist.iterator(); iter.hasNext(); ) {
				SSDPNotifySocket skt = iter.next();
				String addr = skt.getLocalAddress();
				if (!addrs.contains(addr)) {
					iter.remove();
					skt.setControlPoint(null);
					skt.close();
					skt.stop();
					if (_log.shouldWarn())
						_log.warn("Closed SSDP notify socket: " + addr);
				}
				oldaddrs.add(addr);
			}
			for (String addr : addrs) {
				if (!oldaddrs.contains(addr)) {
					// TODO this calls open() in constructor, fails silently
					SSDPNotifySocket socket = new SSDPNotifySocket(addr);
					socket.setControlPoint(this);
					socket.start();
					nlist.add(socket);
					if (_log.shouldWarn())
						_log.warn("Added SSDP notify socket: " + addr);
				}
			}
		}
	}

	/**
	 * We override search() to update the SSDPSearchResponseSocketList,
	 * SSDPNotifySocketList, and HTTPServerList every time.
	 * Otherwise, we are just listening on the interfaces that were present when started.
	 *
	 * @since 0.9.46
	 */
	@Override
	public void search() {
		updateInterfaces();
		super.search();
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
	 * Blocking.
	 *
	 * @param service non-null
	 * @return the external IPv4 address the NAT thinks we have.  Null if we can't find it.
	 */
	private String getNATAddress(Service service) {
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
			sb.append("<p><b>").append(_t("Found Device")).append(":</b> ");
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
		
		Device router;
		Service service;
		Service service6;
		synchronized(lock) {
			if (!_otherUDNs.isEmpty()) {
				sb.append("<b>");
				sb.append(_t("Disabled UPnP Devices"));
				sb.append(":</b>");
				List<Map.Entry<String, Device>> other = new ArrayList<Map.Entry<String, Device>>(_otherUDNs.entrySet());
				Collections.sort(other, new UDNComparator());
				boolean found = false;
				for (Map.Entry<String, Device> e : other) {
					String udn = e.getKey();
					Device dev = e.getValue();
			                String name = dev.getFriendlyName();
					if (name == null)
						name = udn;
					String type = dev.getDeviceType();
					boolean isIGD = (ROUTER_DEVICE.equals(type) || ROUTER_DEVICE_2.equals(type)) && dev.isRootDevice();
					if (!isIGD && !_context.getBooleanProperty(PROP_ADVANCED))
						continue;
					if (!found) {
						found = true;
						sb.append("<ul>");
					}
					name += isIGD ? " IGD" : (' ' + type);
					String ip = getIP(dev);
					if (ip != null)
						name += ' ' + ip;
					sb.append("<li>").append(DataHelper.escapeHTML(name));
					sb.append("<br>UDN: ").append(DataHelper.escapeHTML(udn))
					  .append("</li>");
				}
				if (found)
					sb.append("</ul>");
				else
					sb.append(" none");
			}
			if (!isNATPresent()) {
				sb.append("<p>");
				sb.append(_t("UPnP has not found any UPnP-aware, compatible device on your LAN."));
				return sb.toString();
			}
			router = _router;
			service = _service;
			service6 = _service6;
		}
		listSubDev(null, router, sb);
		String addr = getNATAddress(service);
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
				sb.append(port.isIP6 ? "IPv6: " : "IPv4: ");
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
	 *  Compare based on friendly name of the device
	 *  @since 0.9.46
	 */
	private static class UDNComparator implements Comparator<Map.Entry<String, Device>>, Serializable {
		public int compare(Map.Entry<String, Device> l, Map.Entry<String, Device> r) {
			Device ld = l.getValue();
			Device rd = r.getValue();
                        String ls = ld.getFriendlyName();
			if (ls == null)
				ls = ld.getUDN();
                        String rs = rd.getFriendlyName();
			if (rs == null)
				rs = rd.getUDN();
			return Collator.getInstance().compare(ls, rs);
		}
	}
	
	/**
	 *  This always requests that the external port == the internal port, for now.
	 *  Blocking!
	 *  @return success
	 */
	private boolean addMapping(String protocol, int port, String description, ForwardPort fp) {
		Service service;
		synchronized(lock) {
			if(!isNATPresent() || _router == null) {
				_log.error("Can't addMapping: " + isNATPresent() + " " + _router);
				return false;
			}
			service = fp.isIP6 ? _service6 : _service;
		}
		if (service == null) {
			if (_log.shouldWarn())
				_log.warn("No service for IPv" + (fp.isIP6 ? '6' : '4'));
			return false;
		}
		if (fp.isIP6)
			return addMappingV6(service, port, (IPv6ForwardPort) fp);
		else
			return addMappingV4(service, protocol, port, description, fp);
	}

	/**
	 *  This always requests that the external port == the internal port, for now.
	 *  Blocking!
	 *
	 *  @return success
	 *  @since 0.9.50 split out from above
	 */
	private boolean addMappingV4(Service service, String protocol, int port, String description, ForwardPort fp) {
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
                // 3 hours
                // MUST be longer than max RI republish which is 52 minutes
		int leaseTime = _permanentLeasesOnly ? 0 : 3*60*60;
		add.setArgumentValue("NewLeaseDuration", leaseTime);
		
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

		if (!rv) {
			UPnPStatus status = add.getControlStatus();
			if (status != null) {
				int controlStatus = status.getCode();
				if (controlStatus == 725) {
					if (_log.shouldWarn())
						_log.warn("UPnP device supports permanent leases only");
					_permanentLeasesOnly = true;
		                }
	                }
		}

		return rv;
	}
	
	/**
	 *  This always requests that the external port == the internal port, for now.
	 *  Blocking!
	 *
	 *  @param service WANIPv6FirewallControl
	 *  @return success
	 *  @since 0.9.50
	 */
	private boolean addMappingV6(Service service, int port, IPv6ForwardPort fp) {
		Action add = service.getAction("AddPinhole");
		if (add == null) {
			if (_log.shouldWarn())
				_log.warn("UPnP device does not support pinholing");
			return false;
		}
		String ip = fp.getIP();
		add.setArgumentValue("RemoteHost", ip);
		add.setArgumentValue("RemotePort", port);
		add.setArgumentValue("InternalClient", ip);
		add.setArgumentValue("InternalPort", port);
		add.setArgumentValue("Protocol", fp.protocol);
		// permanent leases aren't supported by miniupnpd anyway
		int leaseTime = 3*60*60;
		add.setArgumentValue("LeaseTime", leaseTime);
		int uid = fp.getUID();
		if (uid < 0) {
			uid = getNewUID();
			fp.setUID(uid);
		}
		add.setArgumentValue("UniqueID", uid);
		
		// I2P - bind the POST socket to the given IP if we're sending to IPv6
		boolean rv;
		String hisIP = service.getRootDevice().getLocation(true);
		if (hisIP != null && hisIP.contains(":")) {
			rv = add.postControlAction(ip);
		} else {
			// this probably won't work if security is enabled
			rv = add.postControlAction();
		}
		if (rv) {
			synchronized(lock) {
				portsForwarded.add(fp);
			}
		}
		int level = rv ? Log.INFO : Log.WARN;
		if (_log.shouldLog(level)) {
			StringBuilder buf = new StringBuilder();
			buf.append("AddPinhole result for ").append(ip).append(' ').append(fp.protocol).append(" port ").append(port);
			UPnPStatus status = add.getStatus();
			if (status != null)
			    buf.append(" Status: ").append(status.getCode()).append(' ').append(status.getDescription());
			status = add.getControlStatus();
			if (status != null)
			    buf.append(" ControlStatus: ").append(status.getCode()).append(' ').append(status.getDescription());
			_log.log(level, buf.toString());
		}

		// 606 Action not authorized

		return rv;
	}

	/**
	 * 65536 isn't a lot, so check for dups
	 *
	 * @return 0 - 65535
	 * @since 0.9.50
	 */
	private int getNewUID() {
		synchronized(lock) {
			while(true) {
				int rv = _context.random().nextInt(65536);
				boolean dup = false;
				for (ForwardPort fp : portsToForward) {
					if (fp.isIP6 && ((IPv6ForwardPort) fp).getUID() == rv) {
						dup = true;
						break;
					}
				}
				if (!dup)
					return rv;
			}
		}
	}

	/**
	 * @param dev non-null
	 * @return The local IP of the device (NOT the external IP) or null
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
	 * @return our adddress or deflt on failure
	 * @since 0.8.8
	 */
	private String getOurAddress(String deflt) {
		String rv = deflt;
		Device router;
		synchronized(lock) {
			router = _router;
		}
		if (router == null)
			return rv;
		String hisIP = getIP(router);
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

	/**
	 *  Blocking
	 *  @return success
	 */
	private boolean removeMapping(String protocol, int port, ForwardPort fp, boolean noLog) {
		Service service;
		synchronized(lock) {
			if(!isNATPresent()) {
				_log.error("Can't removeMapping: " + isNATPresent() + " " + _router);
				return false;
			}
			service = fp.isIP6 ? _service6 : _service;
		}
		if (service == null) {
			if (_log.shouldWarn())
				_log.warn("No service for IPv" + (fp.isIP6 ? '6' : '4'));
			return false;
		}
		if (fp.isIP6)
			return removeMappingV6(service, protocol, port, (IPv6ForwardPort) fp, noLog);
		else
			return removeMappingV4(service, protocol, port, fp, noLog);
	}

	/**
	 *
	 *  @since 0.9.50 split out from above
	 *  @return success
	 */
	private boolean removeMappingV4(Service service, String protocol, int port, ForwardPort fp, boolean noLog) {
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
		
		if (!noLog && _log.shouldWarn()) {
			if (retval)
				_log.warn("UPnP: Removed IPv4 mapping for "+fp.name+" "+port+" / "+protocol);
			else
				_log.warn("UPnP: Failed to remove IPv4 mapping for "+fp.name+" "+port+" / "+protocol);
		}
		return retval;
	}

	/**
	 *
	 *  @since 0.9.50
	 *  @return success
	 */
	private boolean removeMappingV6(Service service, String protocol, int port, IPv6ForwardPort fp, boolean noLog) {
		int uid = fp.getUID();
		if (uid < 0)
		    return false;
		Action remove = service.getAction("DeletePinhole");
		if (remove == null) {
		    if (_log.shouldWarn())
			_log.warn("Couldn't find DeletePinhole action!");
		    return false;
		}
		remove.setArgumentValue("UniqueID", uid);
		// I2P - bind the POST socket to the given IP if we're sending to IPv6
		boolean retval;
		String hisIP = service.getRootDevice().getLocation(true);
		if (hisIP != null && hisIP.contains(":")) {
			String ip = fp.getIP();
			retval = remove.postControlAction(ip);
		} else {
			retval = remove.postControlAction();
		}
		synchronized(lock) {
			portsForwarded.remove(fp);
		}
		if (!noLog && _log.shouldWarn()) {
			String ip = fp.getIP();
			if (retval)
				_log.warn("UPnP: Removed IPv6 mapping for " + fp.name + ' ' + ip + ' ' + port + " / " + protocol);
			else
				_log.warn("UPnP: Failed to remove IPv6 mapping for " + fp.name + ' ' + ip + ' ' + port + " / " + protocol);
		}
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
                                        // Always add, since we now have a 3 hour lease duration,
                                        // so we have to keep refreshing the lease.
					//if(portsToForward.contains(port)) {
					// If not in portsForwarded, it wasn't successful, try again
					//if(portsForwarded.contains(port)) {
						// We have forwarded it, and it should be forwarded, cool.
						// Big problem here, if firewall resets, we don't know it.
						// Do we need to re-forward anyway? or poll the router?
					//} else {
						// Needs forwarding
						if(portsToForwardNow == null) portsToForwardNow = new HashSet<ForwardPort>();
						portsToForwardNow.add(port);
					//}
				}
				for(ForwardPort port : portsToForward) {
					if(ports.contains(port)) {
						// Should be forwarded, has been forwarded, cool.
					} else {
						// TODO don't dump old ipv6 immediately if temporary

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
	 *  Extended to store the requested IP to be forwarded.
	 *  @since 0.9.50
	 */
	static class IPv6ForwardPort extends ForwardPort {
		private final String _ip;
		private int _uid = -1;

		/**
		 *  @param ip the IPv6 address being forwarded
		 */
		public IPv6ForwardPort(String name, int protocol, int port, String ip) {
			super(name, true, protocol, port);
			_ip = ip;
		}

		public String getIP() { return _ip; }

		/**
		 *  @return 0-65535 or -1 if unset
		 */
		public synchronized int getUID() { return _uid; }

		/**
		 *  @param uid 0-65535
		 */
		public synchronized void setUID(int uid) { _uid = uid; }
	
		@Override
		public int hashCode() {
			return _ip.hashCode() ^ super.hashCode();
		}
	
		/**
		 *  Ignores UID
		 */
		@Override
		public boolean equals(Object o) {
			if (o == this) return true;
			if (!(o instanceof IPv6ForwardPort)) return false;
			IPv6ForwardPort f = (IPv6ForwardPort) o;
			return _ip.equals(f.getIP()) && super.equals(o);
		}
	}

	/**
	 *  Dumps out device info in semi-HTML format
	 */
	public static void main(String[] args) throws Exception {
		Properties props = new Properties();
                props.setProperty(PROP_ADVANCED, "true");
		I2PAppContext ctx = new I2PAppContext(props);
		Set<String> addrs = UPnP.getLocalAddresses();
		List<InetAddress> ias = new ArrayList<InetAddress>(addrs.size());
		for (String addr : addrs) {
			    try {
				InetAddress ia = InetAddress.getByName(addr);
				ias.add(ia);
			    } catch (UnknownHostException uhe) {}
		}
		InetAddress[] binds = ias.toArray(new InetAddress[ias.size()]);
		UPnP cp = new UPnP(ctx, 8008, 8058, binds);
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
				String loc = device.getLocation();
				if (loc != null && loc.length() > 0)
					System.out.println("<br>URL: <a href=\"" + loc + "\">" + loc + "</a>");
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
