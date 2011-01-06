package net.i2p.router.transport;

/*
 * public domain
 */

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import org.cybergarage.util.Debug;
import org.freenetproject.DetectedIP;
import org.freenetproject.ForwardPort;
import org.freenetproject.ForwardPortCallback;
import org.freenetproject.ForwardPortStatus;

/**
 * Bridge from the I2P RouterAddress data structure to
 * the freenet data structures
 *
 * @author zzz
 */
public class UPnPManager {
    private Log _log;
    private RouterContext _context;
    private UPnP _upnp;
    private UPnPCallback _upnpCallback;
    private volatile boolean _isRunning;
    private InetAddress _detectedAddress;
    private TransportManager _manager;
    /**
     *  This is the TCP HTTP Event listener
     *  We move these so we don't conflict with other users of the same upnp library
     *  UPnP also binds to port 1900 UDP for multicast reception - this cannot be changed.
     */
    private static final String PROP_HTTP_PORT = "i2np.upnp.HTTPPort";
    private static final int DEFAULT_HTTP_PORT = 7652;
    /** this is the UDP SSDP Search reply listener */
    private static final String PROP_SSDP_PORT = "i2np.upnp.SSDPPort";
    private static final int DEFAULT_SSDP_PORT = 7653;

    public UPnPManager(RouterContext context, TransportManager manager) {
        _context = context;
        _manager = manager;
        _log = _context.logManager().getLog(UPnPManager.class);
        // UPnP wants to bind to IPv6 link local interfaces by default, but what UPnP router
        // is going to want to talk IPv6 anyway? Just make it easy and force IPv4 only
        org.cybergarage.upnp.UPnP.setEnable(org.cybergarage.upnp.UPnP.USE_ONLY_IPV4_ADDR);
        // set up logging in the UPnP package
        Debug.initialize(context);
        _upnp = new UPnP(context);
        _upnp.setHTTPPort(_context.getProperty(PROP_HTTP_PORT, DEFAULT_HTTP_PORT));
        _upnp.setSSDPPort(_context.getProperty(PROP_SSDP_PORT, DEFAULT_SSDP_PORT));
        _upnpCallback = new UPnPCallback();
        _isRunning = false;
    }
    
    public synchronized void start() {
        if (!_isRunning)
            _isRunning = _upnp.runPlugin();
        if (!_isRunning)
            _log.error("UPnP start failed - port conflict?");
    }

    public synchronized void stop() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Stop");
        if (_isRunning)
            _upnp.terminate();
        _isRunning = false;
        _detectedAddress = null;
    }
    
    /**
     * Call when the ports might have changed
     * The transports can call this pretty quickly at startup,
     * which can have multiple UPnP threads running at once, but
     * that should be ok.
     */
    public void update(Map<String, Integer> ports) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Update:");
        if (!_isRunning)
            return;
        Set<ForwardPort> forwards = new HashSet(ports.size());
        for (Map.Entry<String, Integer> entry : ports.entrySet()) {
            String style = entry.getKey();
            int port = entry.getValue().intValue();
            int protocol = -1;
            if ("SSU".equals(style))
                protocol = ForwardPort.PROTOCOL_UDP_IPV4;
            else if ("NTCP".equals(style))
                protocol = ForwardPort.PROTOCOL_TCP_IPV4;
            else
                continue;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding: " + style + " " + port);
            ForwardPort fp = new ForwardPort(style, false, protocol, port);
            forwards.add(fp);
        }
        // non-blocking
        _upnp.onChangePublicPorts(forwards, _upnpCallback);
    }

    /**
     *  This is the callback from UPnP.
     *  It calls the TransportManager callbacks.
     */
    private class UPnPCallback implements ForwardPortCallback {
	
        /** Called to indicate status on one or more forwarded ports. */
        public void portForwardStatus(Map<ForwardPort,ForwardPortStatus> statuses) {
            if (_log.shouldLog(Log.DEBUG))
                 _log.debug("UPnP Callback:");

            DetectedIP[] ips = _upnp.getAddress();
            byte[] detected = null;
            if (ips != null) {
                for (DetectedIP ip : ips) {
                    // store the first public one and tell the transport manager if it changed
                    if (TransportImpl.isPubliclyRoutable(ip.publicAddress.getAddress())) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("External address: " + ip.publicAddress + " type: " + ip.natType);
                        if (!ip.publicAddress.equals(_detectedAddress)) {
                            _detectedAddress = ip.publicAddress;
                            _manager.externalAddressReceived(Transport.SOURCE_UPNP, _detectedAddress.getAddress(), 0);
                        }
                        break;
                    }
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No external address returned");
            }

            for (Map.Entry<ForwardPort, ForwardPortStatus> entry : statuses.entrySet()) {
                ForwardPort fp = entry.getKey();
                ForwardPortStatus fps = entry.getValue();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(fp.name + " " + fp.protocol + " " + fp.portNumber +
                               " status: " + fps.status + " reason: " + fps.reasonString + " ext port: " + fps.externalPort);
                String style;
                if (fp.protocol == ForwardPort.PROTOCOL_UDP_IPV4)
                    style = "SSU";
                else if (fp.protocol == ForwardPort.PROTOCOL_TCP_IPV4)
                    style = "NTCP";
                else
                    continue;
                boolean success = fps.status >= ForwardPortStatus.MAYBE_SUCCESS;
                _manager.forwardPortStatus(style, fp.portNumber, success, fps.reasonString);
            }
        }
    }

    public String renderStatusHTML() {
        if (!_isRunning)
            return "<h3><a name=\"upnp\"></a>UPnP is not enabled</h3>\n";
        return _upnp.renderStatusHTML();
    }
}
