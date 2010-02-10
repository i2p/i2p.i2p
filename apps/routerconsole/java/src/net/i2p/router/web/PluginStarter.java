package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.Translate;

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

    static boolean pluginsEnabled(I2PAppContext ctx) {
         return Boolean.valueOf(ctx.getProperty("router.enablePlugins")).booleanValue();
    }

    public void run() {
        startPlugins(_context);
    }

    static void startPlugins(RouterContext ctx) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        Properties props = pluginProperties();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (name.startsWith(PREFIX) && name.endsWith(ENABLED)) {
                if (Boolean.valueOf(props.getProperty(name)).booleanValue()) {
                    String app = name.substring(PREFIX.length(), name.lastIndexOf(ENABLED));
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
            runClientApps(ctx, pluginDir, clients, true);
        }

        // start console webapps in console/webapps
        Server server = getConsoleServer();
        if (server != null) {
            File consoleDir = new File(pluginDir, "console");
            Properties props = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            File webappDir = new File(consoleDir, "webapps");
            String fileNames[] = webappDir.list(RouterConsoleRunner.WarFilenameFilter.instance());
            if (fileNames != null) {
                for (int i = 0; i < fileNames.length; i++) {
                    try {
                        String warName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                        // check for duplicates in $I2P
                        // easy way for now...
                        if (warName.equals("i2psnark") || warName.equals("susidns") || warName.equals("i2ptunnel") ||
                            warName.equals("susimail") || warName.equals("addressbook")) {
                            log.error("Skipping duplicate webapp " + warName + " in plugin " + appName);
                            continue;
                        }
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
        // These will not override existing resource bundles since we are adding them
        // later in the classpath.
        File localeDir = new File(pluginDir, "console/locale");
        if (localeDir.exists() && localeDir.isDirectory()) {
            File[] files = localeDir.listFiles();
            if (files != null) {
                boolean added = false;
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    if (f.getName().endsWith(".jar")) {
                        try {
                            addPath(f.toURI().toURL());
                            log.error("INFO: Adding translation plugin to classpath: " + f);
                            added = true;
                        } catch (Exception e) {
                            log.error("Plugin " + appName + " bad classpath element: " + f, e);
                        }
                    }
                }
                if (added)
                    Translate.clearCache();
            }
        }

        // add themes in console/themes

        // add summary bar link
        Properties props = pluginProperties(ctx, appName);
        String name = ConfigClientsHelper.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(ctx));
        if (name == null)
            name = ConfigClientsHelper.stripHTML(props, "consoleLinkName");
        String url = ConfigClientsHelper.stripHTML(props, "consoleLinkURL");
        if (name != null && url != null && name.length() > 0 && url.length() > 0)
            NavHelper.registerApp(name, url);

        return true;
    }

    /** @return true on success */
    static boolean stopPlugin(RouterContext ctx, String appName) throws Exception {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        File pluginDir = new File(ctx.getAppDir(), PluginUpdateHandler.PLUGIN_DIR + '/' + appName);
        if ((!pluginDir.exists()) || (!pluginDir.isDirectory())) {
            log.error("Cannot stop nonexistent plugin: " + appName);
            return false;
        }

        // stop things in clients.config
        File clientConfig = new File(pluginDir, "clients.config");
        if (clientConfig.exists()) {
            Properties props = new Properties();
            DataHelper.loadProps(props, clientConfig);
            List<ClientAppConfig> clients = ClientAppConfig.getClientApps(clientConfig);
            runClientApps(ctx, pluginDir, clients, false);
        }

        // stop console webapps in console/webapps
        Server server = getConsoleServer();
        if (server != null) {
            File consoleDir = new File(pluginDir, "console");
            Properties props = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            File webappDir = new File(consoleDir, "webapps");
            String fileNames[] = webappDir.list(RouterConsoleRunner.WarFilenameFilter.instance());
            if (fileNames != null) {
                for (int i = 0; i < fileNames.length; i++) {
                    String warName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                    if (warName.equals("i2psnark") || warName.equals("susidns") || warName.equals("i2ptunnel") ||
                        warName.equals("susimail") || warName.equals("addressbook")) {
                        continue;
                    }
                    WebAppStarter.stopWebApp(server, warName);
                }
            }
        }

        // remove summary bar link
        Properties props = pluginProperties(ctx, appName);
        String name = ConfigClientsHelper.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(ctx));
        if (name == null)
            name = ConfigClientsHelper.stripHTML(props, "consoleLinkName");
        if (name != null && name.length() > 0)
            NavHelper.unregisterApp(name);

        return true;
    }

    /** @return true on success - call stopPlugin() first */
    static boolean deletePlugin(RouterContext ctx, String appName) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        File pluginDir = new File(ctx.getAppDir(), PluginUpdateHandler.PLUGIN_DIR + '/' + appName);
        if ((!pluginDir.exists()) || (!pluginDir.isDirectory())) {
            log.error("Cannot stop nonexistent plugin: " + appName);
            return false;
        }
        FileUtil.rmdir(pluginDir, false);
        Properties props = pluginProperties();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (name.startsWith(PREFIX + appName))
                iter.remove();
        }
        storePluginProperties(props);
        return true;
    }

    /** plugin.config */
    public static Properties pluginProperties(I2PAppContext ctx, String appName) {
        File cfgFile = new File(ctx.getAppDir(), PluginUpdateHandler.PLUGIN_DIR + '/' + appName + '/' + "plugin.config");
        Properties rv = new Properties();
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {}
        return rv;
    }

    /**
     *  plugins.config
     *  this auto-adds a propery for every dir in the plugin directory
     */
    public static Properties pluginProperties() {
        File dir = I2PAppContext.getGlobalContext().getConfigDir();
        Properties rv = new Properties();
        File cfgFile = new File(dir, "plugins.config");
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {}

        List<String> names = getPlugins();
        for (String name : names) {
            String prop = PREFIX + name + ENABLED;
            if (rv.getProperty(prop) == null)
                rv.setProperty(prop, "true");
        }
        return rv;
    }

    /**
     *  all installed plugins whether enabled or not
     */
    public static List<String> getPlugins() {
        List<String> rv = new ArrayList();
        File pluginDir = new File(I2PAppContext.getGlobalContext().getAppDir(), PluginUpdateHandler.PLUGIN_DIR);
        File[] files = pluginDir.listFiles();
        if (files == null)
            return rv;
        for (int i = 0; i < files.length; i++) {
            if (files[i].isDirectory())
                rv.add(files[i].getName());
        }
        return rv;
    }

    /**
     *  The signing keys from all the plugins
     *  @return Map of key to keyname
     *  Last one wins if a dup (installer should prevent dups)
     */
    public static Map<String, String> getPluginKeys(I2PAppContext ctx) {
        Map<String, String> rv = new HashMap();
        List<String> names = getPlugins();
        for (String name : names) {
            Properties props = pluginProperties(ctx, name);
            String pubkey = props.getProperty("key");
            String keyName = props.getProperty("keyName");
            if (pubkey != null && keyName != null && pubkey.length() == 172 && keyName.length() > 0)
                rv.put(pubkey, keyName);
        }
        return rv;
    }

    /**
     *  plugins.config
     */
    public static void storePluginProperties(Properties props) {
        File cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), "plugins.config");
        try {
            DataHelper.storeProps(props, cfgFile);
        } catch (IOException ioe) {}
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

    /** @param start true=start, false=stop */
    private static void runClientApps(RouterContext ctx, File pluginDir, List<ClientAppConfig> apps, boolean start) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        for(ClientAppConfig app : apps) {
            if (start && app.disabled)
                continue;
            String argVal[];
            if (start) {
                argVal = LoadClientAppsJob.parseArgs(app.args);
            } else {
                // stopargs must be present
                if (app.stopargs == null || app.stopargs.length() <= 0)
                    continue;
                argVal = LoadClientAppsJob.parseArgs(app.stopargs);
            }
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
            if (app.delay == 0 || !start) {
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
                addPath(f.toURI().toURL());
                log.error("INFO: Adding plugin to classpath: " + f);
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
