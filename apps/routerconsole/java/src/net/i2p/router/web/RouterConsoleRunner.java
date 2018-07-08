package net.i2p.router.web;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.data.DataHelper;
import net.i2p.jetty.I2PLogger;
import net.i2p.router.RouterContext;
import net.i2p.router.app.RouterApp;
import net.i2p.router.news.NewsManager;
import net.i2p.router.update.ConsoleUpdateManager;
import net.i2p.util.Addresses;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureDirectory;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.SystemVersion;

import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Credential.MD5;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.ThreadPool;

import org.tanukisoftware.wrapper.WrapperManager;

/**
 *  Start the router console.
 */
public class RouterConsoleRunner implements RouterApp {
        
    static {
        // To take effect, must be set before any Jetty classes are loaded
        try {
            Log.setLog(new I2PLogger());
        } catch (Throwable t) {
            System.err.println("INFO: I2P Jetty logging class not found, logging to wrapper log");
        }
        // This way it doesn't try to load Slf4jLog first
        // This causes an NPE in AbstractLifeCycle
        // http://dev.eclipse.org/mhonarc/lists/jetty-users/msg02587.html
        //System.setProperty("org.eclipse.jetty.util.log.class", "net.i2p.jetty.I2PLogger");
    }

    private final RouterContext _context;
    private final ClientAppManager _mgr;
    private volatile ClientAppState _state = UNINITIALIZED;
    private static Server _server;
    private static ScheduledExecutorScheduler _jettyTimer;
    private String _listenPort;
    private String _listenHost;
    private String _sslListenPort;
    private String _sslListenHost;
    private String _webAppsDir;

    private static final String DEFAULT_WEBAPP_CONFIG_FILENAME = "webapps.config";

    // Jetty Auth
    private static final DigestAuthenticator authenticator = new DigestAuthenticator();
    static {
        // default changed from 0 (forever) in Jetty 6 to 60*1000 ms in Jetty 7
        authenticator.setMaxNonceAge(7*24*60*60*1000L);
    }
    public static final String JETTY_REALM = "i2prouter";
    private static final String JETTY_ROLE = "routerAdmin";
    public static final String PROP_CONSOLE_PW = "routerconsole.auth." + JETTY_REALM;
    public static final String PROP_PW_ENABLE = "routerconsole.auth.enable";
    /** from Jetty Credential.java */
    private static final String MD5_CREDENTIAL_TYPE = "MD5:";

    public static final String ROUTERCONSOLE = "routerconsole";
    public static final String PREFIX = "webapps.";
    public static final String ENABLED = ".startOnLoad";
    private static final String PROP_KEYSTORE_PASSWORD = "routerconsole.keystorePassword";
    private static final String PROP_KEY_PASSWORD = "routerconsole.keyPassword";
    public static final int DEFAULT_LISTEN_PORT = PortMapper.DEFAULT_CONSOLE_PORT;
    private static final String DEFAULT_WEBAPPS_DIR = "./webapps/";
    private static final String USAGE = "Bad RouterConsoleRunner arguments, check clientApp.0.args in your clients.config file! " +
                                        "Usage: [[port host[,host]] [-s sslPort [host[,host]]] [webAppsDir]]";

    /** this is for the handlers only. We will adjust for the connectors and acceptors below. */
    private static final int MIN_THREADS = 1;
    /** this is for the handlers only. We will adjust for the connectors and acceptors below. */
    private static final int MAX_THREADS = 24;
    private static final int MAX_IDLE_TIME = 90*1000;
    private static final String THREAD_NAME = "RouterConsole Jetty";
    public static final String PROP_DTG_ENABLED = "desktopgui.enabled";
    static final String PROP_ALLOWED_HOSTS = "routerconsole.allowedHosts";
    /** @since 0.9.34 */
    static final FileFilter WAR_FILTER = new WarFilenameFilter();

    /**
     *  <pre>
     *  non-SSL:
     *  RouterConsoleRunner
     *  RouterConsoleRunner 7657
     *  RouterConsoleRunner 7657 127.0.0.1
     *  RouterConsoleRunner 7657 127.0.0.1,::1
     *  RouterConsoleRunner 7657 127.0.0.1,::1 ./webapps/
     *
     *  SSL:
     *  RouterConsoleRunner -s 7657
     *  RouterConsoleRunner -s 7657 127.0.0.1
     *  RouterConsoleRunner -s 7657 127.0.0.1,::1
     *  RouterConsoleRunner -s 7657 127.0.0.1,::1 ./webapps/
     *
     *  If using both, non-SSL must be first:
     *  RouterConsoleRunner 7657 127.0.0.1 -s 7667
     *  RouterConsoleRunner 7657 127.0.0.1 -s 7667 127.0.0.1
     *  RouterConsoleRunner 7657 127.0.0.1,::1 -s 7667 127.0.0.1,::1
     *  RouterConsoleRunner 7657 127.0.0.1,::1 -s 7667 127.0.0.1,::1 ./webapps/
     *  </pre>
     *
     *  @param args second arg may be a comma-separated list of bind addresses,
     *              for example ::1,127.0.0.1
     *              On XP, the other order (127.0.0.1,::1) fails the IPV6 bind,
     *              because 127.0.0.1 will bind ::1 also. But even though it's bound
     *              to both, we can't connect to [::1]:7657 for some reason.
     *              So the wise choice is ::1,127.0.0.1
     */
    public RouterConsoleRunner(RouterContext ctx, ClientAppManager mgr, String args[]) {
        _context = ctx;
        _mgr = mgr;
        if (args.length == 0) {
            // _listenHost and _webAppsDir are defaulted below
            _listenPort = Integer.toString(DEFAULT_LISTEN_PORT);
        } else {
            boolean ssl = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-s"))
                    ssl = true;
                else if ((!ssl) && _listenPort == null)
                    _listenPort = args[i];
                else if ((!ssl) && _listenHost == null)
                    _listenHost = args[i];
                else if (ssl && _sslListenPort == null)
                    _sslListenPort = args[i];
                else if (ssl && _sslListenHost == null)
                    _sslListenHost = args[i];
                else if (_webAppsDir == null)
                    _webAppsDir = args[i];
                else {
                    System.err.println(USAGE);
                    throw new IllegalArgumentException(USAGE);
                }
            }
        }
        if (_listenHost == null)
           _listenHost = PortMapper.DEFAULT_HOST;
        if (_sslListenHost == null)
           _sslListenHost = _listenHost;
        if (_webAppsDir == null)
           _webAppsDir = DEFAULT_WEBAPPS_DIR;
        // _listenPort and _sslListenPort are not defaulted, if one or the other is null, do not enable
        if (_listenPort == null && _sslListenPort == null) {
            System.err.println(USAGE);
            throw new IllegalArgumentException(USAGE);
        }
        _state = INITIALIZED;
    }
    
    public static void main(String args[]) {
        List<RouterContext> contexts = RouterContext.listContexts();
        if (contexts == null || contexts.isEmpty())
            throw new IllegalStateException("no router context");
        RouterConsoleRunner runner = new RouterConsoleRunner(contexts.get(0), null, args);
        runner.startup();
    }
    
    /////// ClientApp methods

    /** @since 0.9.4 */
    public synchronized void startup() {
        changeState(STARTING);
        checkJavaVersion();
        startTrayApp();
        startConsole();
    }

    /** @since 0.9.4 */
    public synchronized void shutdown(String[] args) {
        if (_state == STOPPED)
            return;
        changeState(STOPPING);
        if (PluginStarter.pluginsEnabled(_context))
            (new I2PAppThread(new PluginStopper(_context), "PluginStopper")).start();
        stopAllWebApps();
        try {
            _server.stop();
        } catch (Exception ie) {}
        PortMapper portMapper = _context.portMapper();
        portMapper.unregister(PortMapper.SVC_CONSOLE);
        portMapper.unregister(PortMapper.SVC_HTTPS_CONSOLE);
        synchronized(RouterConsoleRunner.class) {
            if (_jettyTimer != null) {
                try {
                    _jettyTimer.stop();
                } catch (Exception e) {}
                _jettyTimer = null;
            }
        }
        changeState(STOPPED);
    }

    /** @since 0.9.4 */
    public ClientAppState getState() {
        return _state;
    }

    /** @since 0.9.4 */
    public String getName() {
        return "console";
    }

    /** @since 0.9.4 */
    public String getDisplayName() {
        return "Router Console";
    }

    /////// end ClientApp methods

    private synchronized void changeState(ClientAppState state) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, null);
    }

    /**
     *  SInce _server is now static
     *  @return may be null or stopped perhaps
     *  @since Jetty 6 since it doesn't have Server.getServers()
     */
    static Server getConsoleServer() {
        return _server;
    }

    /** @since 0.8.13, moved from LogsHelper in 0.9.33 */
    public static String jettyVersion() {
        return Server.getVersion();
    }

    private void startTrayApp() {
        // if no permissions, don't even try
        // isLaunchedAsService() always returns true on Linux
        if (GraphicsEnvironment.isHeadless() || SystemVersion.isLinuxService() ||
            (SystemVersion.isWindows() && _context.hasWrapper() && WrapperManager.isLaunchedAsService())) {
            // required true for jrobin to work
            System.setProperty("java.awt.headless", "true");
            return;
        }
        try {
            // default false for now, except on OSX and non-service windows
            String sdtg = _context.getProperty(PROP_DTG_ENABLED);
            boolean desktopguiEnabled = Boolean.parseBoolean(sdtg) ||
                                        (sdtg == null && (SystemVersion.isWindows() || SystemVersion.isMac()));
            if (desktopguiEnabled) {
                System.setProperty("java.awt.headless", "false");
                net.i2p.desktopgui.Main dtg = new net.i2p.desktopgui.Main(_context, _mgr, null);    
                dtg.startup();
            } else {
                // required true for jrobin to work
          	System.setProperty("java.awt.headless", "true");
                // this check is in SysTray but do it here too
                //if (SystemVersion.isWindows() && (!Boolean.getBoolean("systray.disable")) && (!SystemVersion.is64Bit()))
                //    SysTray.getInstance();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** @since 0.9.17 */
    private void checkJavaVersion() {
        boolean noJava7 = !SystemVersion.isJava7();
        boolean noPack200 = (PluginStarter.pluginsEnabled(_context) || !NewsHelper.isUpdateDisabled(_context)) &&
                            !FileUtil.isPack200Supported();
        boolean openARM = SystemVersion.isARM() && SystemVersion.isOpenJDK();
        boolean isJava11 = SystemVersion.isJava11();
        if (noJava7 || noPack200 || openARM || isJava11) {
            String s = "Java version: " + System.getProperty("java.version") +
                       " OS: " + System.getProperty("os.name") + ' ' +
                       System.getProperty("os.arch") + ' ' +
                       System.getProperty("os.version");
            net.i2p.util.Log log = _context.logManager().getLog(RouterConsoleRunner.class);
            log.logAlways(net.i2p.util.Log.WARN, s);
            System.out.println("Warning: " + s);
            if (noJava7) {
                s = "Java 7 is now required, please upgrade";
                log.logAlways(net.i2p.util.Log.WARN, s);
                System.out.println("Warning: " + s);
            }
            if (noPack200) {
                s = "Pack200 is required for plugins and automatic updates, please upgrade Java";
                log.logAlways(net.i2p.util.Log.WARN, s);
                System.out.println("Warning: " + s);
            }
            if (openARM) {
                s = "OpenJDK is not recommended for ARM. Use Oracle Java 8";
                log.logAlways(net.i2p.util.Log.WARN, s);
                System.out.println("Warning: " + s);
            }
            if (isJava11) {
                s = "Java 10/11 support is beta, and not recommended for general use";
                log.logAlways(net.i2p.util.Log.WARN, s);
                System.out.println("Warning: " + s);
            }
        }
    }

    /**
     *  http://irc.codehaus.org/display/JETTY/Porting+to+jetty6
     *
     *<pre>
     *	Server
     *		HandlerCollection
     *			HostCheckHandler (extends GzipHandler)
     *				ContextHandlerCollection
     *					LocaleWebAppHandler (routerconsole)
     *						SessionHandler
     *						SecurityHandler
     *						ServletHandler
     *							servlets...
     *					WebAppContext (i2psnark)
     *					WebAppContext (i2ptunnel)
     *					WebAppContext (imagegen)
     *					WebAppContext (susidns)
     *					WebAppContext (susimail)
     *					WebAppContext (for each plugin with a .war)
     *			DefaultHandler
     *			RequestLogHandler (opt)
     *</pre>
     *
     *  Porting to Jetty 9:
     *
     *  http://dev.eclipse.org/mhonarc/lists/jetty-dev/msg01952.html
     *  You are missing a few facts about Jetty 9.1 ...
     *  First, there are no longer any blocking connectors.
     *  Its all async / nio connectors now. (mainly because that's the direction that the servlet api 3.1 is taking)
     *
     *  Next, there is only 1 connector.   The ServerConnector.
     *  However, it takes 1 or more ConnectionFactory implementations to know how to handle the incoming connection.
     *  We have factories for HTTP (0.9 thru 1.1), SPDY, SSL-http, and SSL-npn so far.
     *  This list of factories will expand as the future of connectivity to web servers is ever growing (think HTTP/2)
     *
     *  Use the embedded examples for help understanding this.
     *  http://git.eclipse.org/c/jetty/org.eclipse.jetty.project.git/tree/examples/embedded/src/main/java/org/eclipse/jetty/embedded/ManyConnectors.java?id=jetty-9.1.0.RC0
     *
     */
    public void startConsole() {
        File workDir = new SecureDirectory(_context.getTempDir(), "jetty-work");
        boolean workDirRemoved = FileUtil.rmdir(workDir, false);
        if (!workDirRemoved)
            System.err.println("ERROR: Unable to remove Jetty temporary work directory");
        boolean workDirCreated = workDir.mkdirs();
        if (!workDirCreated)
            System.err.println("ERROR: Unable to create Jetty temporary work directory");

        // so Jetty can find WebAppConfiguration
        System.setProperty("jetty.class.path", _context.getBaseDir() + "/lib/routerconsole.jar");
        // FIXME
        // http://dev.eclipse.org/mhonarc/lists/jetty-users/msg03487.html
        //_server.setGracefulShutdown(1000);

        // In Jetty 6, QTP was not concurrent, so we switched to
        // ThreadPoolExecutor with a fixed-size queue, a set maxThreads,
        // and a RejectedExecutionPolicy of CallerRuns.
        // Unfortunately, CallerRuns causes lockups in Jetty NIO (ticket #1395)
        // In addition, no flavor of TPE gives us what QTP does:
        // - TPE direct handoff (which we were using) never queues.
        //   This doesn't provide any burst management when maxThreads is reached.
        //   CallerRuns was an attempt to work around that.
        // - TPE unbounded queue does not adjust the number of threads.
        //   This doesn't provide automatic resource management.
        // - TPE bounded queue does not add threads until the queue is full.
        //   This doesn't provide good responsiveness to even small bursts.
        // QTP adds threads as soon as the queue is non-empty.
        // QTP as of Jetty 7 uses concurrent.
        // QTP unbounded queue is the default in Jetty.
        // So switch back to QTP with a bounded queue.
        //
        // ref:
        // http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/ThreadPoolExecutor.html
        // https://wiki.eclipse.org/Jetty/Howto/High_Load
        //
        //try {
        //    ThreadPool ctp = new CustomThreadPoolExecutor();
        //    // Gone in Jetty 7
        //    //ctp.prestartAllCoreThreads();
        //    _server.setThreadPool(ctp);
        //} catch (Throwable t) {
            // class not found...
            //System.out.println("INFO: Jetty concurrent ThreadPool unavailable, using QueuedThreadPool");
            LinkedBlockingQueue<Runnable> lbq = new LinkedBlockingQueue<Runnable>(4*MAX_THREADS);
            // min and max threads will be reset below
            QueuedThreadPool qtp = new QueuedThreadPool(MAX_THREADS, MIN_THREADS, MAX_IDLE_TIME, lbq);
            qtp.setName(THREAD_NAME);
            qtp.setDaemon(true);
            _server = new Server(qtp);
        //}

        HandlerCollection hColl = new HandlerCollection();
        ContextHandlerCollection chColl = new ContextHandlerCollection();
        HostCheckHandler chCollWrapper = new HostCheckHandler(_context);
        chCollWrapper.setHandler(chColl);
        // gone in Jetty 7
        //_server.addHandler(hColl);
        _server.setHandler(hColl);
        hColl.addHandler(chCollWrapper);
        hColl.addHandler(new DefaultHandler());

        String log = _context.getProperty("routerconsole.log");
        if (log != null) {
            File logFile = new File(log);
            if (!logFile.isAbsolute())
                logFile = new File(_context.getLogDir(), "logs/" + log);
            try {
                RequestLogHandler rhl = new RequestLogHandler();
                rhl.setRequestLog(new NCSARequestLog(logFile.getAbsolutePath()));
                hColl.addHandler(rhl);
            } catch (Exception ioe) {
                System.err.println("ERROR: Unable to create Jetty log: " + ioe);
            }
        }
        boolean rewrite = false;
        Properties props = webAppProperties();
        if (props.isEmpty()) {
            props.setProperty(PREFIX + ROUTERCONSOLE + ENABLED, "true");
            rewrite = true;
        }

        // Get an absolute path with a trailing slash for the webapps dir
        // We assume relative to the base install dir for backward compatibility
        File app = new File(_webAppsDir);
        if (!app.isAbsolute()) {
            app = new File(_context.getBaseDir(), _webAppsDir);
            try {
                _webAppsDir = app.getCanonicalPath();
            } catch (IOException ioe) {}
        }
        if (!_webAppsDir.endsWith("/"))
            _webAppsDir += '/';

        Set<String> listenHosts = new HashSet<String>(8);
        HandlerWrapper rootWebApp = null;
        ServletHandler rootServletHandler = null;
        List<Connector> connectors = new ArrayList<Connector>(4);
        try {
            int boundAddresses = 0;
            SortedSet<String> addresses = Addresses.getAllAddresses();
            boolean hasIPV4 = addresses.contains("0.0.0.0");
            boolean hasIPV6 = addresses.contains("0:0:0:0:0:0:0:0");

            // add standard listeners
            int lport = 0;
            if (_listenPort != null) {
                try {
                    lport = Integer.parseInt(_listenPort);
                } catch (NumberFormatException nfe) {}
                if (lport <= 0)
                    System.err.println("Bad routerconsole port " + _listenPort);
            }
            if (lport > 0) {
                List<String> hosts = new ArrayList<String>(2);
                StringTokenizer tok = new StringTokenizer(_listenHost, " ,");
                while (tok.hasMoreTokens()) {
                    String host = tok.nextToken().trim();
                    try {
                        // Test before we add the connector, because Jetty 6 won't start if any of the
                        // connectors are bad
                        if ((!hasIPV6) && Addresses.isIPv6Address(host))
                            throw new IOException("IPv6 addresses unsupported");
                        if ((!hasIPV4) && Addresses.isIPv4Address(host))
                            throw new IOException("IPv4 addresses unsupported");
                        ServerSocket testSock = null;
                        try {
                            // On Windows, this was passing and Jetty was still failing,
                            // possibly due to %scope_id ???
                            // https://issues.apache.org/jira/browse/ZOOKEEPER-667
                            // so do exactly what Jetty does in SelectChannelConnector.open()
                            testSock = new ServerSocket();
                            InetSocketAddress isa = new InetSocketAddress(host, 0);
                            testSock.bind(isa);
                        } finally {
                            if (testSock != null) try { testSock.close(); } catch (IOException ioe) {}
                        }
                        HttpConfiguration httpConfig = new HttpConfiguration();
                        // number of acceptors, (default) number of selectors
                        ServerConnector lsnr = new ServerConnector(_server, 1, 0,
                                                                   new HttpConnectionFactory(httpConfig));
                        //lsnr.setUseDirectBuffers(false);  // default true seems to be leaky
                        lsnr.setHost(host);
                        lsnr.setPort(lport);
                        lsnr.setIdleTimeout(90*1000);  // default 10 sec
                        lsnr.setName("ConsoleSocket");   // all with same name will use the same thread pool
                        //_server.addConnector(lsnr);
                        connectors.add(lsnr);
                        boundAddresses++;
                        hosts.add(host);
                    } catch (Exception ioe) {
                        System.err.println("Unable to bind routerconsole to " + host + " port " + _listenPort + ": " + ioe);
                        System.err.println("You may ignore this warning if the console is still available at http://localhost:" + _listenPort);
                    }
                }
                if (hosts.isEmpty()) {
                    _context.portMapper().register(PortMapper.SVC_CONSOLE, lport);
                } else {
                    // put IPv4 first
                    Collections.sort(hosts, new HostComparator());
                    _context.portMapper().register(PortMapper.SVC_CONSOLE, hosts.get(0), lport);
                    // note that we could still fail in connector.start() below
                    listenHosts.addAll(hosts);
                }
            }

            // add SSL listeners
            int sslPort = 0;
            if (_sslListenPort != null) {
                try {
                    sslPort = Integer.parseInt(_sslListenPort);
                } catch (NumberFormatException nfe) {}
                if (sslPort <= 0)
                    System.err.println("Bad routerconsole SSL port " + _sslListenPort);
            }
            if (sslPort > 0) {
                File keyStore = new File(_context.getConfigDir(), "keystore/console.ks");
                // Put the list of hosts together early, so we can put it in the selfsigned cert.
                StringTokenizer tok = new StringTokenizer(_sslListenHost, " ,");
                Set<String> altNames = new HashSet<String>(4);
                while (tok.hasMoreTokens()) {
                    String s = tok.nextToken().trim();
                    if (!s.equals("0.0.0.0") && !s.equals("::") &&
                        !s.equals("0:0:0:0:0:0:0:0"))
                        altNames.add(s);
                }
                String allowed = _context.getProperty(PROP_ALLOWED_HOSTS);
                if (allowed != null) {
                    tok = new StringTokenizer(allowed, " ,");
                    while (tok.hasMoreTokens()) {
                        altNames.add(tok.nextToken().trim());
                    }
                }
                if (verifyKeyStore(keyStore, altNames)) {
                    // the keystore path and password
                    SslContextFactory sslFactory = new SslContextFactory(keyStore.getAbsolutePath());
                    sslFactory.setKeyStorePassword(_context.getProperty(PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD));
                    // the X.509 cert password (if not present, verifyKeyStore() returned false)
                    sslFactory.setKeyManagerPassword(_context.getProperty(PROP_KEY_PASSWORD, "thisWontWork"));
                    sslFactory.addExcludeProtocols(I2PSSLSocketFactory.EXCLUDE_PROTOCOLS.toArray(
                                                   new String[I2PSSLSocketFactory.EXCLUDE_PROTOCOLS.size()]));
                    sslFactory.addExcludeCipherSuites(I2PSSLSocketFactory.EXCLUDE_CIPHERS.toArray(
                                                      new String[I2PSSLSocketFactory.EXCLUDE_CIPHERS.size()]));
                    List<String> hosts = new ArrayList<String>(2);
                    tok = new StringTokenizer(_sslListenHost, " ,");
                    while (tok.hasMoreTokens()) {
                        String host = tok.nextToken().trim();
                        // doing it this way means we don't have to escape an IPv6 host with []
                        try {
                            // Test before we add the connector, because Jetty 6 won't start if any of the
                            // connectors are bad
                            if ((!hasIPV6) && Addresses.isIPv6Address(host))
                                throw new IOException("IPv6 addresses unsupported");
                            if ((!hasIPV4) && Addresses.isIPv4Address(host))
                                throw new IOException("IPv4 addresses unsupported");
                            ServerSocket testSock = null;
                            try {
                                // see comments above
                                testSock = new ServerSocket();
                                InetSocketAddress isa = new InetSocketAddress(host, 0);
                                testSock.bind(isa);
                            } finally {
                                if (testSock != null) try { testSock.close(); } catch (IOException ioe) {}
                            }
                            HttpConfiguration httpConfig = new HttpConfiguration();
                            httpConfig.setSecureScheme("https");
                            httpConfig.setSecurePort(sslPort);
                            httpConfig.addCustomizer(new SecureRequestCustomizer());
                            // number of acceptors, (default) number of selectors
                            ServerConnector ssll = new ServerConnector(_server, 1, 0,
                                                                       new SslConnectionFactory(sslFactory, "http/1.1"),
                                                                       new HttpConnectionFactory(httpConfig));
                            //sssll.setUseDirectBuffers(false);  // default true seems to be leaky
                            ssll.setHost(host);
                            ssll.setPort(sslPort);
                            ssll.setIdleTimeout(90*1000);  // default 10 sec
                            ssll.setName("ConsoleSocket");   // all with same name will use the same thread pool
                            //_server.addConnector(ssll);
                            connectors.add(ssll);
                            boundAddresses++;
                            hosts.add(host);
                        } catch (Exception e) {
                            System.err.println("Unable to bind routerconsole to " + host + " port " + sslPort + " for SSL: " + e);
                            if (SystemVersion.isGNU())
                                System.err.println("Probably because GNU classpath does not support Sun keystores");
                            System.err.println("You may ignore this warning if the console is still available at https://localhost:" + sslPort);
                        }
                    }
                    if (hosts.isEmpty()) {
                        _context.portMapper().register(PortMapper.SVC_HTTPS_CONSOLE, sslPort);
                    } else {
                        // put IPv4 first
                        Collections.sort(hosts, new HostComparator());
                        _context.portMapper().register(PortMapper.SVC_HTTPS_CONSOLE, hosts.get(0), sslPort);
                        // note that we could still fail in connector.start() below
                        listenHosts.addAll(hosts);
                    }
                } else {
                    System.err.println("Unable to create or access keystore for SSL: " + keyStore.getAbsolutePath());
                }
            }

            if (boundAddresses <= 0) {
                System.err.println("Unable to bind routerconsole to any address on port " + _listenPort + (sslPort > 0 ? (" or SSL port " + sslPort) : ""));
                return;
            }
            // Each address spawns a Connector and an Acceptor thread
            // If the min is less than this, we have no thread for the handlers or the expiration thread.
            qtp.setMinThreads(MIN_THREADS + (2 * boundAddresses));
            qtp.setMaxThreads(MAX_THREADS + (2 * boundAddresses));

            File tmpdir = new SecureDirectory(workDir, ROUTERCONSOLE + "-" +
                                                       (_listenPort != null ? _listenPort : _sslListenPort));
            tmpdir.mkdir();
            rootServletHandler = new ServletHandler();
            rootWebApp = new LocaleWebAppHandler(_context,
                                                  "/", _webAppsDir + ROUTERCONSOLE + ".war",
                                                 tmpdir, rootServletHandler);
            try {
                // Not sure who is supposed to call this, but unless we do,
                // all the jsps die NPE, because JspFactory.getDefaultContext() returns null.
                // We probably have to do this because we don't bundle the Jetty annotations jar and scanner.
                // This is only with Tomcat 8, not with the Jetty (Eclipse) jsp impl.
                // Got a clue from this ancient post for Tomcat 6:
                // https://bz.apache.org/bugzilla/show_bug.cgi?id=39804
                // see also apps/jetty/build.xml
                Class.forName("org.eclipse.jetty.apache.jsp.JettyJasperInitializer");
            } catch (ClassNotFoundException cnfe) {
                System.err.println("Warning: JettyJasperInitializer not found");
            }
            WebAppContext wac = (WebAppContext)(rootWebApp.getHandler());
            initialize(_context, wac);
            WebAppStarter.setWebAppConfiguration(wac);
            chColl.addHandler(rootWebApp);

        } catch (Exception ioe) {
            ioe.printStackTrace();
        }

        // fix up the allowed hosts set (see HostCheckHandler)
        if (listenHosts.contains("0.0.0.0") ||
            listenHosts.contains("::") ||
            listenHosts.contains("0:0:0:0:0:0:0:0")) {
            // empty set says all are valid
            listenHosts.clear();
        } else {
            listenHosts.add("localhost");
            listenHosts.add("127.0.0.1");
            listenHosts.add("::1");
            listenHosts.add("0:0:0:0:0:0:0:1");
            String allowed = _context.getProperty(PROP_ALLOWED_HOSTS);
            if (allowed != null) {
                StringTokenizer tok = new StringTokenizer(allowed, " ,");
                while (tok.hasMoreTokens()) {
                    listenHosts.add(tok.nextToken());
                }
            }
        }
        chCollWrapper.setListenHosts(listenHosts);

        // https://bugs.eclipse.org/bugs/show_bug.cgi?id=364936
        // WARN:oejw.WebAppContext:Failed startup of context o.e.j.w.WebAppContext{/,jar:file:/.../webapps/routerconsole.war!/},/.../webapps/routerconsole.war
        // java.lang.IllegalStateException: zip file closed
        Resource.setDefaultUseCaches(false);
        try {
            // start does a mapContexts()
            _server.start();
        } catch (Throwable me) {
            // NoClassFoundDefError from a webapp is a throwable, not an exception
            System.err.println("Error starting the Router Console server: " + me);
            me.printStackTrace();
        }

        if (_server.isRunning()) {
            // Add and start the connectors one-by-one
            boolean error = false;
            for (Connector conn : connectors) {
                try {
                    _server.addConnector(conn);
                    // start after adding so it gets the right thread pool
                    conn.start();
                } catch (Throwable me) {
                    try {
                        _server.removeConnector(conn);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    System.err.println("WARNING: Error starting " + conn + ": " + me);
                    me.printStackTrace();
                    error = true;
                }
            }
            if (error) {
                String port = (_listenPort != null) ? _listenPort : ((_sslListenPort != null) ? _sslListenPort : Integer.toString(DEFAULT_LISTEN_PORT));
                System.err.println("WARNING: Error starting one or more listeners of the Router Console server.\n" +
                               "If your console is still accessible at http://127.0.0.1:" + port + "/,\n" +
                               "this may be a problem only with binding to the IPV6 address ::1.\n" +
                               "If so, you may ignore this error, or remove the\n" +
                               "\"::1,\" in the \"clientApp.0.args\" line of the clients.config file.");
            }
        }

        // Start all the other webapps after the server is up,
        // so things start faster.
        // Jetty 6 starts the connector before the router console is ready
        // This also prevents one webapp from breaking the whole thing
        List<String> notStarted = new ArrayList<String>();
        if (_server.isRunning()) {
            File dir = new File(_webAppsDir);
            File files[] = dir.listFiles(WAR_FILTER);
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    String appName = files[i].getName();
                    appName = appName.substring(0, appName.lastIndexOf(".war"));
                    String enabled = props.getProperty(PREFIX + appName + ENABLED);
                    if (appName.equals("addressbook")) {
                        // addressbook.war is now empty, thread is started by SusiDNS
                        if (enabled != null) {
                            props.remove(PREFIX + "addressbook" + ENABLED);
                            rewrite = true;
                        }
                    } else if (! "false".equals(enabled)) {
                        try {
                            String path = files[i].getCanonicalPath();
                            WebAppStarter.startWebApp(_context, chColl, appName, path);
                            if (enabled == null) {
                                // do this so configclients.jsp knows about all apps from reading the config
                                props.setProperty(PREFIX + appName + ENABLED, "true");
                                rewrite = true;
                            }
                        } catch (Throwable t) {
                            System.err.println("ERROR: Failed to start " + appName + ' ' + t);
                            t.printStackTrace();
                            notStarted.add(appName);
                        }
                    } else {
                        notStarted.add(appName);
                    }
                }
                changeState(RUNNING);
                if (_mgr != null)
                    _mgr.register(this);
            }
        } else {
            System.err.println("ERROR: Router console did not start, not starting webapps");
            changeState(START_FAILED);
        }

        if (rewrite)
            storeWebAppProperties(_context, props);

        if (rootServletHandler != null && notStarted.size() > 0) {
            // map each not-started webapp to the error page
            ServletHolder noWebApp = rootServletHandler.getServlet("net.i2p.router.web.jsp.nowebapp_jsp");
            for (int i = 0; i < notStarted.size(); i++) {
                // we want a new handler for each one since if the webapp is started we remove the handler???
                try {
                    if (noWebApp != null) {
                        String path = '/' + notStarted.get(i);
                        // LocaleWebAppsHandler adds a .jsp
                        rootServletHandler.addServletWithMapping(noWebApp, path + ".jsp");
                        rootServletHandler.addServletWithMapping(noWebApp, path + "/*");
                    } else {
                        System.err.println("Can't find nowebapp.jsp?");
                    }
                } catch (Throwable me) {
                     System.err.println(me);
                     me.printStackTrace();
                }
            }
        }

        Thread t = new I2PAppThread(new StatSummarizer(), "StatSummarizer", true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
        
            ConsoleUpdateManager um = new ConsoleUpdateManager(_context, _mgr, null);
            um.start();
            NewsManager nm = new NewsManager(_context, _mgr, null);
            nm.startup();
        
            if (PluginStarter.pluginsEnabled(_context)) {
                t = new I2PAppThread(new PluginStarter(_context), "PluginStarter", true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.start();
            }
            // stat summarizer registers its own hook
            // RouterAppManager registers its own hook
            if (_mgr == null)
                _context.addShutdownTask(new ServerShutdown());
            ConfigServiceHandler.registerSignalHandler(_context);
    }
    
    /**
     * @return success if it exists and we have a password, or it was created successfully.
     * @since 0.8.3
     */
    private boolean verifyKeyStore(File ks, Set<String> altNames) {
        if (ks.exists()) {
            String ksPW = _context.getProperty(PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
            KeyStoreUtil.logCertExpiration(ks, ksPW, 180*24*60*60*1000L);
            boolean rv = _context.getProperty(PROP_KEY_PASSWORD) != null;
            if (!rv)
                System.err.println("Console SSL error, must set " + PROP_KEY_PASSWORD + " in " +
                                   (new File(_context.getConfigDir(), "router.config")).getAbsolutePath());
            return rv;
        }
        return createKeyStore(ks, altNames);
    }


    /**
     * Create a new keystore with a keypair in it.
     *
     * @return success
     * @since 0.8.3
     */
    private boolean createKeyStore(File ks, Set<String> altNames) {
        // make a random 48 character password (30 * 8 / 5)
        String keyPassword = KeyStoreUtil.randomString();
        String cname = "localhost";
        boolean success = KeyStoreUtil.createKeys(ks, "console", cname, altNames, "Console", keyPassword);
        if (success) {
            success = ks.exists();
            if (success) {
                try {
                    Map<String, String> changes = new HashMap<String, String>();
                    changes.put(PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
                    changes.put(PROP_KEY_PASSWORD, keyPassword);
                    _context.router().saveConfig(changes, null);
                } catch (Exception e) {}  // class cast exception
                // export cert, fails silently
                File dir = new SecureDirectory(_context.getConfigDir(), "certificates");
                dir.mkdir();
                dir = new SecureDirectory(dir, "console");
                dir.mkdir();
                File certFile = new File(dir, "console.local.crt");
                KeyStoreUtil.exportCert(ks, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, "console", certFile);
            }
        }
        if (success) {
            System.err.println("Created self-signed certificate for " + cname + " in keystore: " + ks.getAbsolutePath() + "\n" +
                               "The certificate was generated randomly.\n" +
                               "Unless you have changed the default settings, the certificate is not associated with your " +
                               "IP address, host name, router identity, or destination keys.");
        } else {
            System.err.println("Failed to create console SSL keystore.\n" +
                               "This is for the Sun/Oracle keytool, others may be incompatible.\n" +
                               "If you create the keystore manually, you must add " + PROP_KEYSTORE_PASSWORD + " and " + PROP_KEY_PASSWORD +
                               " to " + (new File(_context.getConfigDir(), "router.config")).getAbsolutePath());
        }
        return success;
    }

    /**
     *  Set up basic security constraints for the webapp.
     *  Add all users and passwords.
     */
    static void initialize(RouterContext ctx, WebAppContext context) {
        ConstraintSecurityHandler sec = new ConstraintSecurityHandler();
        List<ConstraintMapping> constraints = new ArrayList<ConstraintMapping>(4);
        ConsolePasswordManager mgr = new ConsolePasswordManager(ctx);
        boolean enable = ctx.getBooleanProperty(PROP_PW_ENABLE);
        if (enable) {
            Map<String, String> userpw = mgr.getMD5(PROP_CONSOLE_PW);
            if (userpw.isEmpty()) {
                enable = false;
                ctx.router().saveConfig(PROP_PW_ENABLE, "false");
            } else {
                HashLoginService realm = new CustomHashLoginService(JETTY_REALM, context.getContextPath(),
                                                                    ctx.logManager().getLog(RouterConsoleRunner.class));
                sec.setLoginService(realm);
                sec.setAuthenticator(authenticator);
                String[] role = new String[] {JETTY_ROLE};
                for (Map.Entry<String, String> e : userpw.entrySet()) {
                    String user = e.getKey();
                    String pw = e.getValue();
                    Credential cred = Credential.getCredential(MD5_CREDENTIAL_TYPE + pw);
                    realm.putUser(user, cred, role);
                    Constraint constraint = new Constraint(user, JETTY_ROLE);
                    constraint.setAuthenticate(true);
                    ConstraintMapping cm = new ConstraintMapping();
                    cm.setConstraint(constraint);
                    cm.setPathSpec("/");
                    constraints.add(cm);
                    // Jetty does auth checking only with ISO-8859-1,
                    // so register a 2nd and 3rd user with different encodings if necessary.
                    // Might work, might not...
                    // There's no standard and browser behavior varies.
                    // Chrome sends UTF-8. Firefox doesn't send anything.
                    // https://bugzilla.mozilla.org/show_bug.cgi?id=41489
                    // see also RFC 7616/7617 (late 2015) and PasswordManager.md5Hex()
                    byte[] b1 = DataHelper.getUTF8(user);
                    byte[] b2 = DataHelper.getASCII(user);
                    if (!DataHelper.eq(b1, b2)) {
                        try {
                            // each char truncated to 8 bytes
                            String user2 = new String(b2, "ISO-8859-1");
                            realm.putUser(user2, cred, role);
                            constraint = new Constraint(user2, JETTY_ROLE);
                            constraint.setAuthenticate(true);
                            cm = new ConstraintMapping();
                            cm.setConstraint(constraint);
                            cm.setPathSpec("/");
                            constraints.add(cm);

                            // each UTF-8 byte as a char
                            // this is what chrome does
                            String user3 = new String(b1, "ISO-8859-1");
                            realm.putUser(user3, cred, role);
                            constraint = new Constraint(user3, JETTY_ROLE);
                            constraint.setAuthenticate(true);
                            cm = new ConstraintMapping();
                            cm.setConstraint(constraint);
                            cm.setPathSpec("/");
                            constraints.add(cm);
                        } catch (UnsupportedEncodingException uee) {}
                    }
                }
            }
        }

        // This forces a '403 Forbidden' response for TRACE and OPTIONS unless the
        // WAC handler handles it.
        // (LocaleWebAppHandler returns a '405 Method Not Allowed')
        // TRACE and OPTIONS aren't really security issues...
        // TRACE doesn't echo stuff unless you call setTrace(true)
        // But it might bug some people
        // The other strange methods - PUT, DELETE, MOVE - are disabled by default
        // See also:
        // http://old.nabble.com/Disable-HTTP-TRACE-in-Jetty-5.x-td12412607.html

        Constraint sc = new Constraint();
        sc.setName("No trace");
        ConstraintMapping cm = new ConstraintMapping();
        cm.setMethod("TRACE");
        cm.setConstraint(sc);
        cm.setPathSpec("/");
        constraints.add(cm);

        sc = new Constraint();
        sc.setName("No options");
        cm = new ConstraintMapping();
        cm.setMethod("OPTIONS");
        cm.setConstraint(sc);
        cm.setPathSpec("/");
        constraints.add(cm);

        ConstraintMapping cmarr[] = constraints.toArray(new ConstraintMapping[constraints.size()]);
        sec.setConstraintMappings(cmarr);

        context.setSecurityHandler(sec);

        // No, we can't share the ConstraintSecurityHandler across all webapps
        // But we can force all webapps to use a single Timer thread
        // see HashSessionManager javadoc
        synchronized(RouterConsoleRunner.class) {
            if (_jettyTimer == null) {
                _jettyTimer = new ScheduledExecutorScheduler("Console HashSessionScavenger", true);
                try {
                    _jettyTimer.start();
                } catch (Exception e) {
                    System.err.println("Warning: ScheduledExecutorScheduler start failed: " + e);
                }
            }
            context.getServletContext().setAttribute("org.eclipse.jetty.server.session.timer", _jettyTimer);
        }
    }

    /**
     * For logging authentication failures
     * @since 0.9.28
     */
    private static class CustomHashLoginService extends HashLoginService {
        private final String _webapp;
        private final net.i2p.util.Log _log;

        public CustomHashLoginService(String realm, String webapp, net.i2p.util.Log log) {
            super(realm);
            _webapp = webapp;
            _log = log;
        }

        @Override
        public UserIdentity login(String username, Object credentials) {
            UserIdentity rv = super.login(username, credentials);
            if (rv == null)
                //_log.logAlways(net.i2p.util.Log.WARN, "Console authentication failed, webapp: " + _webapp + ", user: " + username);
                _log.logAlways(net.i2p.util.Log.WARN, "Console authentication failed, user: " + username);
            return rv;
        }
    }
    
    /** @since 0.8.8 */
    private class ServerShutdown implements Runnable {
        public void run() {
            shutdown(null);
        }
    }
    
    private Properties webAppProperties() {
        return webAppProperties(_context.getConfigDir().getAbsolutePath());
    }

    /** @since 0.9.4 */
    public static Properties webAppProperties(I2PAppContext ctx) {
        return webAppProperties(ctx.getConfigDir().getAbsolutePath());
    }

    public static Properties webAppProperties(String dir) {
        Properties rv = new Properties();
        // String webappConfigFile = _context.getProperty(PROP_WEBAPP_CONFIG_FILENAME, DEFAULT_WEBAPP_CONFIG_FILENAME);
        String webappConfigFile = DEFAULT_WEBAPP_CONFIG_FILENAME;
        File cfgFile = new File(dir, webappConfigFile);
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
        
        return rv;
    }

    public static void storeWebAppProperties(RouterContext ctx, Properties props) {
        // String webappConfigFile = _context.getProperty(PROP_WEBAPP_CONFIG_FILENAME, DEFAULT_WEBAPP_CONFIG_FILENAME);
        String webappConfigFile = DEFAULT_WEBAPP_CONFIG_FILENAME;
        File cfgFile = new File(ctx.getConfigDir(), webappConfigFile);
        
        try {
            DataHelper.storeProps(props, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
    }

    /**
     *  Stops all but the root webapp (routerconsole.war)
     *  In Jetty 9, stopping the server doesn't stop the non-root webapps,
     *  so we must do it here.
     *  There should be a better way to do this, possibly by
     *  making the webapps "managed".
     *  @since 0.9.30
     */
    private void stopAllWebApps() {
        Properties props = webAppProperties(_context);
        Set<String> keys = props.stringPropertyNames();
        for (String name : keys) {
            if (name.startsWith(PREFIX) && name.endsWith(ENABLED)) {
                String app = name.substring(PREFIX.length(), name.lastIndexOf(ENABLED));
                if (ROUTERCONSOLE.equals(app))
                    continue;
                if (WebAppStarter.isWebAppRunning(app)) {
                    try {
                        WebAppStarter.stopWebApp(_context, app);
                    } catch (Throwable t) { t.printStackTrace(); }
                }
            }
        }

    }

    private static class WarFilenameFilter extends FileSuffixFilter {
        private static final String RCWAR = ROUTERCONSOLE + ".war";

        public WarFilenameFilter() { super(".war"); }

        public boolean accept(File file) {
            return super.accept(file) && !file.getName().equals(RCWAR);
        }
    }

    /**
     * Put IPv4 first
     * @since 0.9.24
     */
    private static class HostComparator implements Comparator<String>, Serializable {
         public int compare(String l, String r) {
             boolean l4 = l.contains(".");
             boolean r4 = r.contains(".");
             if (l4 && !r4)
                 return -1;
             if (r4 && !l4)
                 return 1;
             return l.compareTo(r);
        }
    }
    
    /**
     * Just to set the name and set Daemon
     * @since Jetty 6
     */
/*****
    private static class CustomThreadPoolExecutor extends ExecutorThreadPool {
        public CustomThreadPoolExecutor() {
             super(new ThreadPoolExecutor(
                      MIN_THREADS, MAX_THREADS, MAX_IDLE_TIME, TimeUnit.MILLISECONDS,
                      new SynchronousQueue<Runnable>(),
                      new CustomThreadFactory(),
                      new ThreadPoolExecutor.CallerRunsPolicy())
                  );
        }
    }
*****/

    /**
     * Just to set the name and set Daemon
     * @since Jetty 6
     */
/*****
    private static class CustomThreadFactory implements ThreadFactory {

        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(THREAD_NAME);
            rv.setDaemon(true);
            return rv;
        }
    }
*****/

}
