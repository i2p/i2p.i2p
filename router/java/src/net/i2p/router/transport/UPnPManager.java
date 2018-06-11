package net.i2p.router.transport;

/*
 * public domain
 */

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.router.RouterContext;
import static net.i2p.router.transport.Transport.AddressSource.SOURCE_UPNP;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Translate;

import org.cybergarage.util.Debug;
import org.freenetproject.DetectedIP;
import org.freenetproject.ForwardPort;
import org.freenetproject.ForwardPortCallback;
import org.freenetproject.ForwardPortStatus;

/**
 * Bridge from the I2P RouterAddress data structure to
 * the freenet data structures
 *
 * @since 0.7.4
 * @author zzz
 */
class UPnPManager {
    private final Log _log;
    private final RouterContext _context;
    private final UPnP _upnp;
    private final UPnPCallback _upnpCallback;
    private volatile boolean _isRunning;
    private volatile boolean _shouldBeRunning;
    private volatile long _lastRescan;
    private boolean _errorLogged;
    private boolean _disconLogged;
    private InetAddress _detectedAddress;
    private final TransportManager _manager;
    private final SimpleTimer2.TimedEvent _rescanner;
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
    private static final long RESCAN_MIN_DELAY = 60*1000;
    private static final long RESCAN_SHORT_DELAY = 2*60*1000;
    // minimum UPnP announce interval is 30 minutes. Let's be faster
    // 30 minutes is also the default "lease time" in cybergarage.
    // It expires after 31 minutes.
    private static final long RESCAN_LONG_DELAY = 14*60*1000;
    // make these generic so we don't advertise we're running I2P
    private static final String TCP_PORT_NAME = "TCP";
    private static final String UDP_PORT_NAME = "UDP";

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
        _upnpCallback = new UPnPCallback();
        _rescanner = new Rescanner();
    }
    
    /**
     *  Blocking, may take a while.
     *  May be called even if already running.
     */
    public synchronized void start() {
        _shouldBeRunning = true;
        if (!_isRunning && Addresses.isConnected()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("UPnP Start");
            long b = _context.clock().now();
            try {
                // We set these here every time, because ControlPoint auto-decrements on failure,
                // and will eventually hit 1024 and then negative
                _upnp.setHTTPPort(_context.getProperty(PROP_HTTP_PORT, DEFAULT_HTTP_PORT));
                _upnp.setSSDPPort(_context.getProperty(PROP_SSDP_PORT, DEFAULT_SSDP_PORT));
                _isRunning = _upnp.runPlugin();
                if (_log.shouldDebug())
                    _log.info("UPnP runPlugin took " + (_context.clock().now() - b));
            } catch (RuntimeException e) {
                // NPE in UPnP (ticket #728), can't let it bring us down
                if (!_errorLogged) {
                    _log.error("UPnP error, please report", e);
                    _errorLogged = true;
                }
            }
        }
        if (_isRunning) {
            _rescanner.schedule(RESCAN_LONG_DELAY);
        } else {
            _rescanner.schedule(RESCAN_SHORT_DELAY);
            // Do we have a non-loopback, non-broadcast address?
            // If not, that's why it failed (HTTPServer won't start)
            if (!Addresses.isConnected()) {
                if (!_disconLogged) {
                    _log.logAlways(Log.WARN, "UPnP start failed - no network connection?");
                    _disconLogged = true;
                }
            } else {
                _log.error("UPnP start failed - port conflict?");
            }
        }
    }

    /**
     *  Blocking, may take a while, up to 20 seconds
     */
    public synchronized void stop() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Stop");
        _shouldBeRunning = false;
        _rescanner.cancel();
        if (_isRunning)
            _upnp.terminate();
        _isRunning = false;
        _detectedAddress = null;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Stop Done");
    }

    /**
     *  Call when IP or network connectivity might have changed.
     *  Starts UPnP if previous start failed, else starts a search.
     *  Must have called start() first, and not called stop().
     *
     *  Should be fast. This only starts the search, the responses
     *  will come in over the MX time (3 seconds).
     *
     *  @since 0.9.18
     */
    public synchronized void rescan() {
        if (!_shouldBeRunning)
            return;
        if (_context.router().gracefulShutdownInProgress())
            return;
        long now = System.currentTimeMillis();
        if (_lastRescan + RESCAN_MIN_DELAY > now)
            return;
        _lastRescan = now;
        if (_isRunning) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("UPnP Rescan");
            // TODO default search MX (jitter) is 3 seconds... reduce?
            // See also:
            // Adaptive Jitter Control for UPnP M-Search
            // Kevin Mills and Christopher Dabrowski
            _upnp.search();
        } else {
            start();
        }
    }

    /**
     * Initiate a UPnP search
     *
     * @since 0.9.18
     */
    private class Rescanner extends SimpleTimer2.TimedEvent {

        /** caller must schedule() */
        public Rescanner() {
            super(_context.simpleTimer2());
        }

        public void timeReached() {
            if (_shouldBeRunning) {
                rescan();
                reschedule(_isRunning ? RESCAN_LONG_DELAY : RESCAN_SHORT_DELAY);
            }
        }
    }
    
    /**
     * Call when the ports might have changed
     * The transports can call this pretty quickly at startup,
     * which can have multiple UPnP threads running at once, but
     * that should be ok.
     */
    public void update(Set<TransportManager.Port> ports) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Update with " + ports.size() + " ports");

        //synchronized(this) {
            // TODO
            // called too often and may block for too long
            // may not have started if net was disconnected previously
            //if (!_isRunning && !ports.isEmpty())
            //    start();
            if (!_isRunning)
                return;
        //}

        Set<ForwardPort> forwards = new HashSet<ForwardPort>(ports.size());
        for (TransportManager.Port entry : ports) {
            String style = entry.style;
            int port = entry.port;
            int protocol;
            String name;
            if ("SSU".equals(style)) {
                protocol = ForwardPort.PROTOCOL_UDP_IPV4;
                name = UDP_PORT_NAME;
            } else if ("NTCP".equals(style)) {
                protocol = ForwardPort.PROTOCOL_TCP_IPV4;
                name = TCP_PORT_NAME;
            } else {
                continue;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding: " + style + " " + port);
            ForwardPort fp = new ForwardPort(name, false, protocol, port);
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
            // Let's not have two of these running at once.
            // Deadlock reported in ticket #1699
            // and the locking isn't foolproof in UDPTransport.
            // UPnP runs the callbacks in a thread, so we can block.
            // There is only one UPnPCallback, so lock on this
            synchronized(this) {
                locked_PFS(statuses);
            }
        }

        private void locked_PFS(Map<ForwardPort,ForwardPortStatus> statuses) {
            byte[] ipaddr = null;
            DetectedIP[] ips = _upnp.getAddress();
            if (ips != null) {
                for (DetectedIP ip : ips) {
                    // store the first public one and tell the transport manager if it changed
                    // Note that getAddress() will actually return a max of one address.
                    if (TransportUtil.isPubliclyRoutable(ip.publicAddress.getAddress(), false)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("External address: " + ip.publicAddress + " type: " + ip.natType);
                        if (!ip.publicAddress.equals(_detectedAddress)) {
                            _detectedAddress = ip.publicAddress;
                            // deadlock path 1
                            _manager.externalAddressReceived(SOURCE_UPNP, _detectedAddress.getAddress(), 0);
                        }
                        ipaddr = ip.publicAddress.getAddress();
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
                // deadlock path 2
                _manager.forwardPortStatus(style, ipaddr, fp.portNumber, fps.externalPort, success, fps.reasonString);
            }
        }
    }

    /**
     *  Warning - blocking, very slow, queries the active router,
     *  will take many seconds if it has vanished.
     */
    public String renderStatusHTML() {
        if (!_isRunning)
            return "<h3><a name=\"upnp\"></a>" + _t("UPnP is not enabled") + "</h3>\n";
        return _upnp.renderStatusHTML();
    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     *  Translate
     */
    private final String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

}
