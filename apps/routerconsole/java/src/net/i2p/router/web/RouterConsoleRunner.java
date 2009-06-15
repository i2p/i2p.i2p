package net.i2p.router.web;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.apps.systray.SysTray;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;

import org.mortbay.http.DigestAuthenticator;
import org.mortbay.http.HashUserRealm;
import org.mortbay.http.SecurityConstraint;
import org.mortbay.http.handler.SecurityHandler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.WebApplicationContext;

public class RouterConsoleRunner {
    private Server _server;
    private String _listenPort = "7657";
    private String _listenHost = "127.0.0.1";
    private String _webAppsDir = "./webapps/";
    private static final String PROP_WEBAPP_CONFIG_FILENAME = "router.webappsConfigFile";
    private static final String DEFAULT_WEBAPP_CONFIG_FILENAME = "webapps.config";
    public static final String ROUTERCONSOLE = "routerconsole";
    public static final String PREFIX = "webapps.";
    public static final String ENABLED = ".startOnLoad";
    
    static {
        System.setProperty("org.mortbay.http.Version.paranoid", "true");
        System.setProperty("java.awt.headless", "true");
    }
    
    /**
     *  @param args second arg may be a comma-separated list of bind addresses,
     *              for example ::1,127.0.0.1
     *              On XP, the other order (127.0.0.1,::1) fails the IPV6 bind,
     *              because 127.0.0.1 will bind ::1 also. But even though it's bound
     *              to both, we can't connect to [::1]:7657 for some reason.
     *              So the wise choice is ::1,127.0.0.1
     */
    public RouterConsoleRunner(String args[]) {
        if (args.length == 3) {
            _listenPort = args[0].trim();
            _listenHost = args[1].trim();
            _webAppsDir = args[2].trim();
        }
    }
    
    public static void main(String args[]) {
        RouterConsoleRunner runner = new RouterConsoleRunner(args);
        runner.startConsole();
    }
    
    public void startConsole() {
        File workDir = new File(I2PAppContext.getGlobalContext().getTempDir(), "jetty-work");
        boolean workDirRemoved = FileUtil.rmdir(workDir, false);
        if (!workDirRemoved)
            System.err.println("ERROR: Unable to remove Jetty temporary work directory");
        boolean workDirCreated = workDir.mkdirs();
        if (!workDirCreated)
            System.err.println("ERROR: Unable to create Jetty temporary work directory");
        
        _server = new Server();
        boolean rewrite = false;
        Properties props = webAppProperties();
        if (props.size() <= 0) {
            props.setProperty(PREFIX + ROUTERCONSOLE + ENABLED, "true");
            rewrite = true;
        }

        // Get an absolute path with a trailing slash for the webapps dir
        // We assume relative to the base install dir for backward compatibility
        File app = new File(_webAppsDir);
        if (!app.isAbsolute()) {
            app = new File(I2PAppContext.getGlobalContext().getBaseDir(), _webAppsDir);
            try {
                _webAppsDir = app.getCanonicalPath();
            } catch (IOException ioe) {}
        }
        if (!_webAppsDir.endsWith("/"))
            _webAppsDir += '/';

        try {
            StringTokenizer tok = new StringTokenizer(_listenHost, " ,");
            int boundAddresses = 0;
            while (tok.hasMoreTokens()) {
                String host = tok.nextToken().trim();
                try {
                    if (host.indexOf(":") >= 0) // IPV6 - requires patched Jetty 5
                        _server.addListener('[' + host + "]:" + _listenPort);
                    else
                        _server.addListener(host + ':' + _listenPort);
                    boundAddresses++;
                } catch (IOException ioe) { // this doesn't seem to work, exceptions don't happen until start() below
                    System.err.println("Unable to bind routerconsole to " + host + " port " + _listenPort + ' ' + ioe);
                }
            }
            if (boundAddresses <= 0) {
                System.err.println("Unable to bind routerconsole to any address on port " + _listenPort);
                return;
            }
            _server.setRootWebApp(ROUTERCONSOLE);
            WebApplicationContext wac = _server.addWebApplication("/", _webAppsDir + ROUTERCONSOLE + ".war");
            File tmpdir = new File(workDir, ROUTERCONSOLE + "-" + _listenPort);
            tmpdir.mkdir();
            wac.setTempDirectory(tmpdir);
            initialize(wac);
            File dir = new File(_webAppsDir);
            String fileNames[] = dir.list(WarFilenameFilter.instance());
            if (fileNames != null) {
                for (int i = 0; i < fileNames.length; i++) {
                    try {
                        String appName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                        String enabled = props.getProperty(PREFIX + appName + ENABLED);
                        if (! "false".equals(enabled)) {
                            String path = new File(dir, fileNames[i]).getCanonicalPath();
                            wac = _server.addWebApplication("/"+ appName, path);
                            tmpdir = new File(workDir, appName + "-" + _listenPort);
                            tmpdir.mkdir();
                            wac.setTempDirectory(tmpdir);
                            initialize(wac);
                            if (enabled == null) {
                                // do this so configclients.jsp knows about all apps from reading the config
                                props.setProperty(PREFIX + appName + ENABLED, "true");
                                rewrite = true;
                            }
                        }
                    } catch (IOException ioe) {
                        System.err.println("Error resolving '" + fileNames[i] + "' in '" + dir);
                    }
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        if (rewrite)
            storeWebAppProperties(props);
        try {
            _server.start();
        } catch (Throwable me) {
            // NoClassFoundDefError from a webapp is a throwable, not an exception
            System.err.println("WARNING: Error starting one or more listeners of the Router Console server.\n" +
                               "If your console is still accessible at http://127.0.0.1:7657/,\n" +
                               "this may be a problem only with binding to the IPV6 address ::1.\n" +
                               "If so, you may ignore this error, or remove the\n" +
                               "\"::1,\" in the \"clientApp.0.args\" line of the clients.config file.\n" +
                               "Exception: " + me);
        }
        try {
            SysTray tray = SysTray.getInstance();
        } catch (Throwable t) {
            t.printStackTrace();
        }

        NewsFetcher fetcher = NewsFetcher.getInstance(I2PAppContext.getGlobalContext());
        I2PThread t = new I2PThread(fetcher, "NewsFetcher");
        t.setDaemon(true);
        t.start();
        
        I2PThread st = new I2PThread(new StatSummarizer(), "StatSummarizer");
        st.setDaemon(true);
        st.start();
    }
    
    private void initialize(WebApplicationContext context) {
        String password = getPassword();
        if (password != null) {
            HashUserRealm realm = new HashUserRealm("i2prouter");
            realm.put("admin", password);
            realm.addUserToRole("admin", "routerAdmin");
            context.setRealm(realm);
            context.setAuthenticator(new DigestAuthenticator());
            context.addHandler(0, new SecurityHandler());
            SecurityConstraint constraint = new SecurityConstraint("admin", "routerAdmin");
            constraint.setAuthenticate(true);
            context.addSecurityConstraint("/", constraint);
        }
    }
    
    private String getPassword() {
        List contexts = RouterContext.listContexts();
        if (contexts != null) {
            for (int i = 0; i < contexts.size(); i++) {
                RouterContext ctx = (RouterContext)contexts.get(i);
                String password = ctx.getProperty("consolePassword");
                if (password != null) {
                    password = password.trim();
                    if (password.length() > 0) {
                        return password;
                    }
                }
            }
            // no password in any context
            return null;
        } else {
            // no contexts?!
            return null;
        }
    }
    
    public void stopConsole() {
        try {
            _server.stop();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
    
    public static Properties webAppProperties() {
        Properties rv = new Properties();
        // String webappConfigFile = ctx.getProperty(PROP_WEBAPP_CONFIG_FILENAME, DEFAULT_WEBAPP_CONFIG_FILENAME);
        String webappConfigFile = DEFAULT_WEBAPP_CONFIG_FILENAME;
        File cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), webappConfigFile);
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
        
        return rv;
    }

    public static void storeWebAppProperties(Properties props) {
        // String webappConfigFile = ctx.getProperty(PROP_WEBAPP_CONFIG_FILENAME, DEFAULT_WEBAPP_CONFIG_FILENAME);
        String webappConfigFile = DEFAULT_WEBAPP_CONFIG_FILENAME;
        File cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), webappConfigFile);
        
        try {
            DataHelper.storeProps(props, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
    }

    private static class WarFilenameFilter implements FilenameFilter {
        private static final WarFilenameFilter _filter = new WarFilenameFilter();
        public static WarFilenameFilter instance() { return _filter; }
        public boolean accept(File dir, String name) {
            return (name != null) && (name.endsWith(".war") && !name.equals(ROUTERCONSOLE + ".war"));
        }
    }
}
