package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.SourceRouteReplyMessage;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.admin.StatsGenerator;
import net.i2p.router.message.GarlicMessageHandler;
import net.i2p.router.message.SourceRouteReplyMessageHandler;
import net.i2p.router.message.TunnelMessageHandler;
import net.i2p.router.startup.StartupJob;
import net.i2p.router.transport.BandwidthLimiter;
import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.LogConsoleBuffer;
import net.i2p.util.LogManager;
import net.i2p.util.RandomSource;

/**
 * Main driver for the router.
 *
 */
public class Router {
    private final static Log _log = new Log(Router.class);
    private final static Router _instance = new Router();
    public static Router getInstance() { return _instance; }
    private Properties _config;
    private String _configFilename;
    private RouterInfo _routerInfo;
    private long _started;
    private boolean _higherVersionSeen;
    
    public final static String PROP_CONFIG_FILE = "router.configLocation";
    
    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000; 
    
    public final static String PROP_INFO_FILENAME = "router.info.location";
    public final static String PROP_INFO_FILENAME_DEFAULT = "router.info";
    public final static String PROP_KEYS_FILENAME = "router.keys.location";
    public final static String PROP_KEYS_FILENAME_DEFAULT = "router.keys";
        
    private Router() { 
	_config = new Properties();
	_configFilename = System.getProperty(PROP_CONFIG_FILE, "router.config");
	_routerInfo = null;
	_higherVersionSeen = false;
	// grumble about sun's java caching DNS entries *forever*
	System.setProperty("sun.net.inetaddr.ttl", "0");
	System.setProperty("networkaddress.cache.ttl", "0");
	// (no need for keepalive)
	System.setProperty("http.keepAlive", "false");
    }
    
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
	    JobQueue.getInstance().addJob(new PersistRouterInfoJob());
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
    public long getUptime() { return Clock.getInstance().now() - Clock.getInstance().getOffset() - _started; }
    
    private void runRouter() {
	_started = Clock.getInstance().now();
	Runtime.getRuntime().addShutdownHook(new ShutdownHook());
	I2PThread.setOOMEventListener(new I2PThread.OOMEventListener() { 
	    public void outOfMemory(OutOfMemoryError oom) { 
		_log.log(Log.CRIT, "Thread ran out of memory", oom);
		shutdown(); 
	    }
	});
	setupHandlers();
	startupQueue();
	JobQueue.getInstance().addJob(new CoallesceStatsJob());
	JobQueue.getInstance().addJob(new UpdateRoutingKeyModifierJob());
	warmupCrypto();
	SessionKeyPersistenceHelper.getInstance().startup();
	JobQueue.getInstance().addJob(new StartupJob());
    }
    
    /**
     * coallesce the stats framework every minute
     *
     */
    private final static class CoallesceStatsJob extends JobImpl {
	public String getName() { return "Coallesce stats"; }
	public void runJob() {
	    StatManager.getInstance().coallesceStats();
	    requeue(60*1000);
	}
    }
    
    /**
     * Update the routing Key modifier every day at midnight (plus on startup).
     * This is done here because we want to make sure the key is updated before anyone
     * uses it.
     */
    private final static class UpdateRoutingKeyModifierJob extends JobImpl {
	private Calendar _cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
	public String getName() { return "Update Routing Key Modifier"; }
	public void runJob() {
	    RoutingKeyGenerator.getInstance().generateDateBasedModData();
	    requeue(getTimeTillMidnight());
	}
	private long getTimeTillMidnight() {
	    long now = Clock.getInstance().now();
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
	RandomSource.getInstance().nextBoolean();
	new DHSessionKeyBuilder(); // load the class so it starts the precalc process
    }
    
    private void startupQueue() {
	JobQueue.getInstance().runQueue(1);
    }
    
    private void setupHandlers() {
	InNetMessagePool.getInstance().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler());
	InNetMessagePool.getInstance().registerHandlerJobBuilder(TunnelMessage.MESSAGE_TYPE, new TunnelMessageHandler());
	InNetMessagePool.getInstance().registerHandlerJobBuilder(SourceRouteReplyMessage.MESSAGE_TYPE, new SourceRouteReplyMessageHandler());
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
	buf.append("<b>As of: </b> ").append(new Date(Clock.getInstance().now())).append(" (uptime: ").append(DataHelper.formatDuration(getUptime())).append(") <br />\n");
	buf.append("<b>Started on: </b> ").append(new Date(getWhenStarted())).append("<br />\n");
	buf.append("<b>Clock offset: </b> ").append(Clock.getInstance().getOffset()).append("ms (OS time: ").append(new Date(Clock.getInstance().now() - Clock.getInstance().getOffset())).append(")<br />\n");
	long tot = Runtime.getRuntime().totalMemory()/1024;
	long free = Runtime.getRuntime().freeMemory()/1024;
	buf.append("<b>Memory:</b> In use: ").append((tot-free)).append("KB Free: ").append(free).append("KB <br />\n"); 
	buf.append("<b>Version:</b> Router: ").append(RouterVersion.VERSION).append(" / SDK: ").append(CoreVersion.VERSION).append("<br />\n"); 
	if (_higherVersionSeen) 
	    buf.append("<b><font color=\"red\">HIGHER VERSION SEEN</font><b> - please <a href=\"http://i2p.dnsalias.net/\">check</a> to see if there is a new release out<br />\n");
	
	buf.append("<hr /><a name=\"bandwidth\"> </a><h2>Bandwidth</h2>\n");
	long sent = BandwidthLimiter.getInstance().getTotalSendBytes();
	long received = BandwidthLimiter.getInstance().getTotalReceiveBytes();
	buf.append("<ul>");
	
	buf.append("<li> ").append(sent).append(" bytes sent, ");
	buf.append(received).append(" bytes received</li>");
	
	DecimalFormat fmt = new DecimalFormat("##0.00");
		    
	// we use the unadjusted time, since thats what getWhenStarted is based off
	long lifetime = Clock.getInstance().now()-Clock.getInstance().getOffset() - getWhenStarted();
	lifetime /= 1000;
	if ( (sent > 0) && (received > 0) ) {
	    double sendKBps = sent / (lifetime*1024.0);
	    double receivedKBps = received / (lifetime*1024.0);
	    buf.append("<li>Lifetime rate: ");
	    buf.append(fmt.format(sendKBps)).append("KBps sent ");
	    buf.append(fmt.format(receivedKBps)).append("KBps received");
	    buf.append("</li>");
	} 

	RateStat sendRate = StatManager.getInstance().getRate("transport.sendMessageSize");
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
	
	RateStat receiveRate = StatManager.getInstance().getRate("transport.receiveMessageSize");
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
	buf.append(ClientManagerFacade.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"transports\"> </a>\n");
	buf.append(CommSystemFacade.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"profiles\"> </a>\n");
	buf.append(PeerManagerFacade.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"tunnels\"> </a>\n");
	buf.append(TunnelManagerFacade.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"jobs\"> </a>\n");
	buf.append(JobQueue.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"shitlist\"> </a>\n");
	buf.append(Shitlist.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"pending\"> </a>\n");
	buf.append(OutboundMessageRegistry.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"netdb\"> </a>\n");
	buf.append(NetworkDatabaseFacade.getInstance().renderStatusHTML());
	buf.append("\n<hr /><a name=\"logs\"> </a>\n");	
	List msgs = LogConsoleBuffer.getInstance().getMostRecentMessages();
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
	try { JobQueue.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the job queue", t); }
	try { StatisticsManager.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the stats manager", t); }
	try { ClientManagerFacade.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the client manager", t); }
	try { TunnelManagerFacade.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the tunnel manager", t); }
	try { NetworkDatabaseFacade.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the networkDb", t); }
	try { CommSystemFacade.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the comm system", t); }
	try { PeerManagerFacade.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the peer manager", t); }
	try { SessionKeyPersistenceHelper.getInstance().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the session key manager", t); }
	dumpStats();
	_log.log(Log.CRIT, "Shutdown complete", new Exception("Shutdown"));
	try { LogManager.getInstance().shutdown(); } catch (Throwable t) { }
	try { Thread.sleep(1000); } catch (InterruptedException ie) {}
	Runtime.getRuntime().halt(-1);
    }
    
    private void dumpStats() {
	_log.log(Log.CRIT, "Lifetime stats:\n\n" + StatsGenerator.generateStatsPage());
    }
    
    public static void main(String args[]) {
	Router.getInstance().runRouter();
	if (args.length > 0) {
	    _log.info("Not interactive");
	} else {
	    _log.info("Interactive");
	    try {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		String line = null;
		while ( (line = in.readLine()) != null) {
		    ClientMessagePool.getInstance().dumpPoolInfo();
		    OutNetMessagePool.getInstance().dumpPoolInfo();
		    InNetMessagePool.getInstance().dumpPoolInfo();
		}
	    } catch (IOException ioe) {
		_log.error("Error dumping queue", ioe);
	    }
	}
    }
    
    private class ShutdownHook extends Thread {
	public void run() {
	    _log.log(Log.CRIT, "Shutting down the router...", new Exception("Shutting down"));
	    shutdown();
	}
    }
    
    /** update the router.info file whenever its, er, updated */
    private static class PersistRouterInfoJob extends JobImpl {
	public String getName() { return "Persist Updated Router Information"; }
	public void runJob() {
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Persisting updated router info");
	    
	    String infoFilename = Router.getInstance().getConfigSetting(PROP_INFO_FILENAME);
	    if (infoFilename == null)
		infoFilename = PROP_INFO_FILENAME_DEFAULT;
	
	    RouterInfo info = Router.getInstance().getRouterInfo();
	    
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
