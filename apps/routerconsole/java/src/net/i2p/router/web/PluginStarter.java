package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;
import net.i2p.util.Log;

import org.mortbay.http.HttpListener;
import org.mortbay.jetty.Server;


/**
 *  Start plugins that are already installed
 *
 *  @since 0.7.12
 *  @author zzz
 */
public class PluginStarter implements Runnable {
    private RouterContext _context;
    static final String PREFIX = "plugin.";
    static final String ENABLED = ".startOnLoad";

    public PluginStarter(RouterContext ctx) {
        _context = ctx;
    }

    public void run() {
        startPlugins(_context);
    }

    static void startPlugins(RouterContext ctx) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        Properties props = pluginProperties();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (name.startsWith(PluginStarter.PREFIX) && name.endsWith(PluginStarter.ENABLED)) {
                if (Boolean.valueOf(props.getProperty(name)).booleanValue()) {
                    String app = name.substring(PluginStarter.PREFIX.length(), name.lastIndexOf(PluginStarter.ENABLED));
                    try {
                        if (!startPlugin(ctx, app))
                            log.error("Failed to start plugin: " + app);
                    } catch (Exception e) {
                        log.error("Failed to start plugin: " + app, e);
                    }
                }
            }
        }
    }

    /** @return true on success */
    static boolean startPlugin(RouterContext ctx, String appName) throws Exception {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        File pluginDir = new File(ctx.getAppDir(), PluginUpdateHandler.PLUGIN_DIR + '/' + appName);
        if ((!pluginDir.exists()) || (!pluginDir.isDirectory())) {
            log.error("Cannot start nonexistent plugin: " + appName);
            return false;
        }

        // load and start things in clients.config
        File clientConfig = new File(pluginDir, "clients.config");
        if (clientConfig.exists()) {
            Properties props = new Properties();
            DataHelper.loadProps(props, clientConfig);
            List<ClientAppConfig> clients = ClientAppConfig.getClientApps(clientConfig);
            runClientApps(ctx, pluginDir, clients);
        }

        // start console webapps in console/webapps
        Server server = getConsoleServer();
        if (server != null) {
            File consoleDir = new File(pluginDir, "console");
            Properties props = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            File webappDir = new File(pluginDir, "webapps");
            String fileNames[] = webappDir.list(RouterConsoleRunner.WarFilenameFilter.instance());
            if (fileNames != null) {
                for (int i = 0; i < fileNames.length; i++) {
                    try {
                        String warName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                        // check for duplicates in $I2P ?
                        String enabled = props.getProperty(PREFIX + warName + ENABLED);
                        if (! "false".equals(enabled)) {
                            String path = new File(webappDir, fileNames[i]).getCanonicalPath();
                            WebAppStarter.startWebApp(ctx, server, warName, path);
                        }
                    } catch (IOException ioe) {
                        log.error("Error resolving '" + fileNames[i] + "' in '" + webappDir, ioe);
                    }
                }
            }
        }

        // add translation jars in console/locale

        // add themes in console/themes

        // add summary bar link

        return true;
    }

    /** this auto-adds a propery for every dir in the plugin directory */
    public static Properties pluginProperties() {
        File dir = I2PAppContext.getGlobalContext().getConfigDir();
        Properties rv = new Properties();
        File cfgFile = new File(dir, "plugins.config");
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {}

        File pluginDir = new File(I2PAppContext.getGlobalContext().getAppDir(), PluginUpdateHandler.PLUGIN_DIR);
        File[] files = pluginDir.listFiles();
        if (files == null)
            return rv;
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            String prop = PREFIX + name + ENABLED;
            if (files[i].isDirectory() && rv.getProperty(prop) == null)
                rv.setProperty(prop, "true");
        }
        return rv;
    }

    /** see comments in ConfigClientsHandler */
    static Server getConsoleServer() {
        Collection c = Server.getHttpServers();
        for (int i = 0; i < c.size(); i++) {
            Server s = (Server) c.toArray()[i];
            HttpListener[] hl = s.getListeners();
            for (int j = 0; j < hl.length; j++) {
                if (hl[j].getPort() == 7657)
                    return s;
            }
        }
        return null;
    }

    private static void runClientApps(RouterContext ctx, File pluginDir, List<ClientAppConfig> apps) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        for(ClientAppConfig app : apps) {
            if (app.disabled)
                continue;
            String argVal[] = LoadClientAppsJob.parseArgs(app.args);
            // do this after parsing so we don't need to worry about quoting
            for (int i = 0; i < argVal.length; i++) {
                if (argVal[i].indexOf("$") >= 0) {
                    argVal[i] = argVal[i].replace("$I2P", ctx.getBaseDir().getAbsolutePath());
                    argVal[i] = argVal[i].replace("$CONFIG", ctx.getConfigDir().getAbsolutePath());
                    argVal[i] = argVal[i].replace("$PLUGIN", pluginDir.getAbsolutePath());
                }
            }
            if (app.classpath != null) {
                String cp = new String(app.classpath);
                if (cp.indexOf("$") >= 0) {
                    cp = cp.replace("$I2P", ctx.getBaseDir().getAbsolutePath());
                    cp = cp.replace("$CONFIG", ctx.getConfigDir().getAbsolutePath());
                    cp = cp.replace("$PLUGIN", pluginDir.getAbsolutePath());
                }
                addToClasspath(cp, app.clientName, log);
            }
            if (app.delay == 0) {
                // run this guy now
                LoadClientAppsJob.runClient(app.className, app.clientName, argVal, log);
            } else {
                // wait before firing it up
                ctx.jobQueue().addJob(new LoadClientAppsJob.DelayedRunClient(ctx, app.className, app.clientName, argVal, app.delay));
            }
        }
    }

    /**
     *  Perhaps there's an easy way to use Thread.setContextClassLoader()
     *  but I don't see how to make it magically get used for everything.
     *  So add this to the whole JVM's classpath.
     */
    private static void addToClasspath(String classpath, String clientName, Log log) {
        StringTokenizer tok = new StringTokenizer(classpath, ",");
        while (tok.hasMoreTokens()) {
            String elem = tok.nextToken().trim();
            File f = new File(elem);
            if (!f.isAbsolute()) {
                log.error("Plugin client " + clientName + " classpath element is not absolute: " + f);
                continue;
            }
            try {
                log.error("INFO: Adding plugin classpath: " + f);
                addPath(f.toURI().toURL());
            } catch (Exception e) {
                log.error("Plugin client " + clientName + " bad classpath element: " + f, e);
            }
        }
    }

    /**
     *  http://jimlife.wordpress.com/2007/12/19/java-adding-new-classpath-at-runtime/
     */
    public static void addPath(URL u) throws Exception {
        URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Class urlClass = URLClassLoader.class;
        Method method = urlClass.getDeclaredMethod("addURL", new Class[]{URL.class});
        method.setAccessible(true);
        method.invoke(urlClassLoader, new Object[]{u});
    }
}
