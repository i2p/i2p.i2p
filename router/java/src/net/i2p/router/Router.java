package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import net.i2p.CoreVersion;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.SourceRouteReplyMessage;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.message.GarlicMessageHandler;
import net.i2p.router.message.SourceRouteReplyMessageHandler;
import net.i2p.router.message.TunnelMessageHandler;
import net.i2p.router.startup.StartupJob;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Main driver for the router.
 *
 */
public class Router {
    private Log _log;
    private RouterContext _context;
    private Properties _config;
    private String _configFilename;
    private RouterInfo _routerInfo;
    private long _started;
    private boolean _higherVersionSeen;
    private SessionKeyPersistenceHelper _sessionKeyPersistenceHelper;
    private boolean _killVMOnEnd;
    private boolean _isAlive;
    private I2PThread.OOMEventListener _oomListener;
    private ShutdownHook _shutdownHook;
    
    public final static String PROP_CONFIG_FILE = "router.configLocation";
    
    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000; 
    
    public final static String PROP_INFO_FILENAME = "router.info.location";
    public final static String PROP_INFO_FILENAME_DEFAULT = "router.info";
    public final static String PROP_KEYS_FILENAME = "router.keys.location";
    public final static String PROP_KEYS_FILENAME_DEFAULT = "router.keys";
        
    static {
        // grumble about sun's java caching DNS entries *forever*
        System.setProperty("sun.net.inetaddr.ttl", "0");
        System.setProperty("networkaddress.cache.ttl", "0");
        // (no need for keepalive)
        System.setProperty("http.keepAlive", "false");
    }
    
    public Router() { this(null, null); }
    public Router(Properties envProps) { this(null, envProps); }
    public Router(String configFilename) { this(configFilename, null); }
    public Router(String configFilename, Properties envProps) {
        _config = new Properties();
        _context = new RouterContext(this, envProps);
        if (configFilename == null)
            _configFilename = _context.getProperty(PROP_CONFIG_FILE, "router.config");
        else
            _configFilename = configFilename;
        _routerInfo = null;
        _higherVersionSeen = false;
        _log = _context.logManager().getLog(Router.class);
        _log.info("New router created with config file " + _configFilename);
        _sessionKeyPersistenceHelper = new SessionKeyPersistenceHelper(_context);
        _killVMOnEnd = true;
        _oomListener = new I2PThread.OOMEventListener() { 
            public void outOfMemory(OutOfMemoryError oom) { 
                _log.log(Log.CRIT, "Thread ran out of memory", oom);
                shutdown(); 
            }
        };
        _shutdownHook = new ShutdownHook();
    }
    
    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     */
    public void setKillVMOnEnd(boolean shouldDie) { _killVMOnEnd = shouldDie; }
    public boolean getKillVMOnEnd() { return _killVMOnEnd; }
    
    public String getConfigFilename() { return _configFilename; }
    public void setConfigFilename(String filename) { _configFilename = filename; }
    
    public String getConfigSetting(String name) { return _config.getProperty(name); }
    public void setConfigSetting(String name, String value) { _config.setProperty(name, value); }
    public Set getConfigSettings() { return new HashSet(_config.keySet()); }
    public Properties getConfigMap() { return _config; }
    
    public RouterInfo getRouterInfo() { return _routerInfo; }
    public void setRouterInfo(RouterInfo info) { 
        _routerInfo = info; 
        if (info != null)
            _context.jobQueue().addJob(new PersistRouterInfoJob());
    }
    
    /**
     * True if the router has tried to communicate with another router who is running a higher
     * incompatible protocol version.  
     *
     */
    public boolean getHigherVersionSeen() { return _higherVersionSeen; }
    public void setHigherVersionSeen(boolean seen) { _higherVersionSeen = seen; }
    
    public long getWhenStarted() { return _started; }
    /** wall clock uptime */
    public long getUptime() { return _context.clock().now() - _context.clock().getOffset() - _started; }
    
    void runRouter() {
        _isAlive = true;
        _started = _context.clock().now();
        Runtime.getRuntime().addShutdownHook(_shutdownHook);
        I2PThread.addOOMEventListener(_oomListener);
        setupHandlers();
        startupQueue();
        _context.jobQueue().addJob(new CoallesceStatsJob());
        _context.jobQueue().addJob(new UpdateRoutingKeyModifierJob());
        warmupCrypto();
        _sessionKeyPersistenceHelper.startup();
        _context.jobQueue().addJob(new StartupJob(_context));
    }
    
    public boolean isAlive() { return _isAlive; }
    
    /**
     * coallesce the stats framework every minute
     *
     */
    private final class CoallesceStatsJob extends JobImpl {
        public CoallesceStatsJob() { super(Router.this._context); }
        public String getName() { return "Coallesce stats"; }
        public void runJob() {
            Router.this._context.statManager().coallesceStats();
            requeue(60*1000);
        }
    }
    
    /**
     * Update the routing Key modifier every day at midnight (plus on startup).
     * This is done here because we want to make sure the key is updated before anyone
     * uses it.
     */
    private final class UpdateRoutingKeyModifierJob extends JobImpl {
        private Calendar _cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        public UpdateRoutingKeyModifierJob() { super(Router.this._context); }
        public String getName() { return "Update Routing Key Modifier"; }
        public void runJob() {
            Router.this._context.routingKeyGenerator().generateDateBasedModData();
            requeue(getTimeTillMidnight());
        }
        private long getTimeTillMidnight() {
            long now = Router.this._context.clock().now();
            _cal.setTime(new Date(now));
            _cal.add(Calendar.DATE, 1);
            _cal.set(Calendar.HOUR_OF_DAY, 0);
            _cal.set(Calendar.MINUTE, 0);
            _cal.set(Calendar.SECOND, 0);
            _cal.set(Calendar.MILLISECOND, 0);
            long then = _cal.getTime().getTime();
            _log.debug("Time till midnight: " + (then-now) + "ms");
            if (then - now <= 60*1000) {
                // everyone wave at kaffe.
                // "Hi Kaffe"
                return 60*1000;
            } else {
                return then - now;
            }
        }
    }
    
    private void warmupCrypto() {
        _context.random().nextBoolean();
        new DHSessionKeyBuilder(); // load the class so it starts the precalc process
    }
    
    private void startupQueue() {
        _context.jobQueue().runQueue(1);
    }
    
    private void setupHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler(_context));
        _context.inNetMessagePool().registerHandlerJobBuilder(TunnelMessage.MESSAGE_TYPE, new TunnelMessageHandler(_context));
        _context.inNetMessagePool().registerHandlerJobBuilder(SourceRouteReplyMessage.MESSAGE_TYPE, new SourceRouteReplyMessageHandler(_context));
    }
    
    public String renderStatusHTML() {
        StringBuffer buf = new StringBuffer();
        buf.append("<html><head><title>I2P Router Console</title></head><body>\n");
        buf.append("<h1>Router console</h1>\n");
        buf.append("<i><a href=\"/routerConsole.html\">console</a> | <a href=\"/routerStats.html\">stats</a></i><br>\n");

        buf.append("<form action=\"/routerConsole.html\">");
        buf.append("<select name=\"go\" onChange='location.href=this.value'>");
        buf.append("<option value=\"/routerConsole.html#bandwidth\">Bandwidth</option>\n");
        buf.append("<option value=\"/routerConsole.html#clients\">Clients</option>\n");
        buf.append("<option value=\"/routerConsole.html#transports\">Transports</option>\n");
        buf.append("<option value=\"/routerConsole.html#profiles\">Peer Profiles</option>\n");
        buf.append("<option value=\"/routerConsole.html#tunnels\">Tunnels</option>\n");
        buf.append("<option value=\"/routerConsole.html#jobs\">Jobs</option>\n");
        buf.append("<option value=\"/routerConsole.html#shitlist\">Shitlist</option>\n");
        buf.append("<option value=\"/routerConsole.html#pending\">Pending messages</option>\n");
        buf.append("<option value=\"/routerConsole.html#netdb\">Network Database</option>\n");
        buf.append("<option value=\"/routerConsole.html#logs\">Log messages</option>\n");
        buf.append("</select>");
        buf.append("</form>");

        buf.append("<hr />\n");

        if ( (_routerInfo != null) && (_routerInfo.getIdentity() != null) )
            buf.append("<b>Router: </b> ").append(_routerInfo.getIdentity().getHash().toBase64()).append("<br />\n");
        buf.append("<b>As of: </b> ").append(new Date(_context.clock().now())).append(" (uptime: ").append(DataHelper.formatDuration(getUptime())).append(") <br />\n");
        buf.append("<b>Started on: </b> ").append(new Date(getWhenStarted())).append("<br />\n");
        buf.append("<b>Clock offset: </b> ").append(_context.clock().getOffset()).append("ms (OS time: ").append(new Date(_context.clock().now() - _context.clock().getOffset())).append(")<br />\n");
        long tot = Runtime.getRuntime().totalMemory()/1024;
        long free = Runtime.getRuntime().freeMemory()/1024;
        buf.append("<b>Memory:</b> In use: ").append((tot-free)).append("KB Free: ").append(free).append("KB <br />\n"); 
        buf.append("<b>Version:</b> Router: ").append(RouterVersion.VERSION).append(" / SDK: ").append(CoreVersion.VERSION).append("<br />\n"); 
        if (_higherVersionSeen) 
            buf.append("<b><font color=\"red\">HIGHER VERSION SEEN</font><b> - please <a href=\"http://i2p.dnsalias.net/\">check</a> to see if there is a new release out<br />\n");

        buf.append("<hr /><a name=\"bandwidth\"> </a><h2>Bandwidth</h2>\n");
        long sent = _context.bandwidthLimiter().getTotalSendBytes();
        long received = _context.bandwidthLimiter().getTotalReceiveBytes();
        buf.append("<ul>");

        buf.append("<li> ").append(sent).append(" bytes sent, ");
        buf.append(received).append(" bytes received</li>");

        DecimalFormat fmt = new DecimalFormat("##0.00");

        // we use the unadjusted time, since thats what getWhenStarted is based off
        long lifetime = _context.clock().now()-_context.clock().getOffset() - getWhenStarted();
        lifetime /= 1000;
        if ( (sent > 0) && (received > 0) ) {
            double sendKBps = sent / (lifetime*1024.0);
            double receivedKBps = received / (lifetime*1024.0);
            buf.append("<li>Lifetime rate: ");
            buf.append(fmt.format(sendKBps)).append("KBps sent ");
            buf.append(fmt.format(receivedKBps)).append("KBps received");
            buf.append("</li>");
            } 
        
        RateStat sendRate = _context.statManager().getRate("transport.sendMessageSize");
        for (int i = 0; i < sendRate.getPeriods().length; i++) {
            Rate rate = sendRate.getRate(sendRate.getPeriods()[i]);
            double bytes = rate.getLastTotalValue() + rate.getCurrentTotalValue();
            long ms = rate.getLastTotalEventTime() + rate.getLastTotalEventTime();
            if (ms <= 0) {
                bytes = 0;
                ms = 1;
            }
            buf.append("<li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" instantaneous send avg: ");
            double bps = bytes*1000.0d/ms;
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li><li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" period send avg: ");
            // we include lastPeriod + current *partial* period, and jrandom is too lazy to calculate how
            // much of that partial is contained here, so 2*period it is.
            bps = bytes*1000.0d/(2*rate.getPeriod()); 
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li>");
        }

        RateStat receiveRate = _context.statManager().getRate("transport.receiveMessageSize");
        for (int i = 0; i < receiveRate.getPeriods().length; i++) {
            Rate rate = receiveRate.getRate(receiveRate.getPeriods()[i]);
            double bytes = rate.getLastTotalValue() + rate.getCurrentTotalValue();
            long ms = rate.getLastTotalEventTime() + rate.getLastTotalEventTime();
            if (ms <= 0) {
                bytes = 0;
                ms = 1;
            }
            buf.append("<li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" instantaneous receive avg: ");
            double bps = bytes*1000.0d/ms;
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps ");
            } else {
                buf.append(fmt.format(bps)).append(" Bps ");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li><li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" period receive avg: ");
            // we include lastPeriod + current *partial* period, and jrandom is too lazy to calculate how
            // much of that partial is contained here, so 2*period it is.
            bps = bytes*1000.0d/(2*rate.getPeriod());
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li>");
        }

        buf.append("</ul>\n");
        buf.append("<i>Instantaneous averages count how fast the transfers go when we're trying to transfer data, ");
        buf.append("while period averages count how fast the transfers go across the entire period, even when we're not ");
        buf.append("trying to transfer data.  Lifetime averages count how many elephants there are on the moon [like anyone reads this text]</i>");
        buf.append("\n");

        buf.append("<hr /><a name=\"clients\"> </a>\n");
        buf.append(_context.clientManager().renderStatusHTML());
        buf.append("\n<hr /><a name=\"transports\"> </a>\n");
        buf.append(_context.commSystem().renderStatusHTML());
        buf.append("\n<hr /><a name=\"profiles\"> </a>\n");
        buf.append(_context.peerManager().renderStatusHTML());
        buf.append("\n<hr /><a name=\"tunnels\"> </a>\n");
        buf.append(_context.tunnelManager().renderStatusHTML());
        buf.append("\n<hr /><a name=\"jobs\"> </a>\n");
        buf.append(_context.jobQueue().renderStatusHTML());
        buf.append("\n<hr /><a name=\"shitlist\"> </a>\n");
        buf.append(_context.shitlist().renderStatusHTML());
        buf.append("\n<hr /><a name=\"pending\"> </a>\n");
        buf.append(_context.messageRegistry().renderStatusHTML());
        buf.append("\n<hr /><a name=\"netdb\"> </a>\n");
        buf.append(_context.netDb().renderStatusHTML());
        buf.append("\n<hr /><a name=\"logs\"> </a>\n");	
        List msgs = _context.logManager().getBuffer().getMostRecentMessages();
        buf.append("\n<h2>Most recent console messages:</h2><table border=\"1\">\n");
        for (Iterator iter = msgs.iterator(); iter.hasNext(); ) {
            String msg = (String)iter.next();
            buf.append("<tr><td valign=\"top\" align=\"left\"><pre>").append(msg);
            buf.append("</pre></td></tr>\n");
        }
        buf.append("</table>");
        buf.append("</body></html>\n");
        return buf.toString();
    }
    
    public void shutdown() {
        _isAlive = false;
        I2PThread.removeOOMEventListener(_oomListener);
        try { _context.jobQueue().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the job queue", t); }
        try { _context.statPublisher().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the stats manager", t); }
        try { _context.clientManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the client manager", t); }
        try { _context.tunnelManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the tunnel manager", t); }
        try { _context.netDb().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the networkDb", t); }
        try { _context.commSystem().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the comm system", t); }
        try { _context.peerManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the peer manager", t); }
        try { _context.messageRegistry().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the message registry", t); }
        try { _context.messageValidator().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the message validator", t); }
        try { _sessionKeyPersistenceHelper.shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the session key manager", t); }
        dumpStats();
        _log.log(Log.CRIT, "Shutdown complete", new Exception("Shutdown"));
        try { _context.logManager().shutdown(); } catch (Throwable t) { }
        if (_killVMOnEnd) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            Runtime.getRuntime().halt(-1);
        }
    }
    
    private void dumpStats() {
        //_log.log(Log.CRIT, "Lifetime stats:\n\n" + StatsGenerator.generateStatsPage());
    }
    
    public static void main(String args[]) {
        Router r = new Router();
        r.runRouter();
    }
    
    private class ShutdownHook extends Thread {
        public void run() {
            _log.log(Log.CRIT, "Shutting down the router...");
            shutdown();
        }
    }
    
    /** update the router.info file whenever its, er, updated */
    private class PersistRouterInfoJob extends JobImpl {
        public PersistRouterInfoJob() { super(Router.this._context); }
        public String getName() { return "Persist Updated Router Information"; }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Persisting updated router info");

            String infoFilename = getConfigSetting(PROP_INFO_FILENAME);
            if (infoFilename == null)
                infoFilename = PROP_INFO_FILENAME_DEFAULT;

            RouterInfo info = getRouterInfo();

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(infoFilename);
                info.writeBytes(fos);
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding the router information", dfe);
            } catch (IOException ioe) {
                _log.error("Error writing out the rebuilt router information", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
}
