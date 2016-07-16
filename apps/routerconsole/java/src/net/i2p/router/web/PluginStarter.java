package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;
import net.i2p.data.DataHelper;
import net.i2p.data.Base64;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;
import net.i2p.router.update.ConsoleUpdateManager;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;


/**
 *  Start/stop/delete plugins that are already installed
 *  Get properties of installed plugins
 *  Get or change settings in plugins.config
 *
 *  @since 0.7.12
 *  @author zzz
 */
public class PluginStarter implements Runnable {
    protected RouterContext _context;
    private static final String CONFIG_FILE = "plugins.config";
    public static final String PREFIX = "plugin.";
    // false, true, or deleted
    public static final String ENABLED = ".startOnLoad";
    public static final String DELETED = "deleted";
    public static final String PLUGIN_DIR = "plugins";
    private static final String[] STANDARD_WEBAPPS = { "i2psnark", "i2ptunnel", "susidns",
                                                       "susimail", "addressbook", "routerconsole" };
    private static final String[] STANDARD_THEMES = { "images", "light", "dark", "classic",
                                                      "midnight" };
    private static Map<String, ThreadGroup> pluginThreadGroups = new ConcurrentHashMap<String, ThreadGroup>();   // one thread group per plugin (map key=plugin name)
    private static Map<String, Collection<SimpleTimer2.TimedEvent>> _pendingPluginClients =
                   new ConcurrentHashMap<String, Collection<SimpleTimer2.TimedEvent>>();
    private static Map<String, ClassLoader> _clCache = new ConcurrentHashMap<String, ClassLoader>();
    private static Map<String, Collection<String>> pluginWars = new ConcurrentHashMap<String, Collection<String>>();

    public PluginStarter(RouterContext ctx) {
        _context = ctx;
    }

    static boolean pluginsEnabled(I2PAppContext ctx) {
         return ctx.getBooleanPropertyDefaultTrue("router.enablePlugins");
    }

    public void run() {
        deferredDeletePlugins(_context);
        if (_context.getBooleanPropertyDefaultTrue("plugins.autoUpdate") &&
            !NewsHelper.isUpdateInProgress()) {
            String prev = _context.getProperty("router.previousVersion");
            if (prev != null &&
                VersionComparator.comp(RouterVersion.VERSION, prev) > 0) {
                updateAll(_context, true);
            }
        }
        startPlugins(_context);
    }

    /**
     *  threaded
     *  @since 0.8.13
     */
    static void updateAll(RouterContext ctx) {
        Thread t = new I2PAppThread(new PluginUpdater(ctx), "PluginUpdater", true);
        t.start();
    }

    /**
     *  thread
     *  @since 0.8.13
     */
    private static class PluginUpdater implements Runnable {
        private final RouterContext _ctx;

        public PluginUpdater(RouterContext ctx) {
            _ctx = ctx;
        }

        public void run() {
            updateAll(_ctx, false);
        }
    }

    /**
     *  inline
     *  @since 0.8.13
     */
    private static void updateAll(RouterContext ctx, boolean delay) {
        List<String> plugins = getPlugins();
        Map<String, String> toUpdate = new HashMap<String, String>();
        for (String appName : plugins) {
            Properties props = pluginProperties(ctx, appName);
            String url = props.getProperty("updateURL");
            if (url != null)
                toUpdate.put(appName, url);
        }
        if (toUpdate.isEmpty())
            return;

        ConsoleUpdateManager mgr = UpdateHandler.updateManager(ctx);
        if (mgr == null)
            return;
        if (mgr.isUpdateInProgress())
            return;

        if (delay) {
            // wait for proxy
            mgr.update(TYPE_DUMMY, 3*60*1000);
            mgr.notifyProgress(null, Messages.getString("Checking for plugin updates", ctx));
            int loop = 0;
            do {
                try {
                    Thread.sleep(5*1000);
                } catch (InterruptedException ie) {}
                if (loop++ > 40) break;
            } while (mgr.isUpdateInProgress(TYPE_DUMMY));
        }

        String proxyHost = ctx.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = ConfigUpdateHandler.proxyPort(ctx);
        if (proxyPort == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT &&
            proxyHost.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST) &&
            ctx.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) < 0) {
            mgr.notifyComplete(null, Messages.getString("Plugin update check failed", ctx) +
                                     " - " +
                                     Messages.getString("HTTP client proxy tunnel must be running", ctx));
            return;
        }
        if (ctx.commSystem().isDummy()) {
            mgr.notifyComplete(null, Messages.getString("Plugin update check failed", ctx) +
                                     " - " +
                                     "VM Comm System");
            return;
        }

        Log log = ctx.logManager().getLog(PluginStarter.class);
        int updated = 0;
        for (Map.Entry<String, String> entry : toUpdate.entrySet()) {
            String appName = entry.getKey();
            if (log.shouldLog(Log.WARN))
                log.warn("Checking for update plugin: " + appName);

            // blocking
            if (mgr.checkAvailable(PLUGIN, appName, 60*1000) == null) {
                if (log.shouldLog(Log.WARN))
                    log.warn("No update available for plugin: " + appName);
                continue;
            }

            if (log.shouldLog(Log.WARN))
                log.warn("Updating plugin: " + appName);
            // non-blocking
            mgr.update(PLUGIN, appName, 30*60*1000);
            int loop = 0;
            do {
                // only wait for 4 minutes, then we will
                // keep going
                try {
                    Thread.sleep(5*1000);
                } catch (InterruptedException ie) {}
                if (loop++ > 48) break;
            } while (mgr.isUpdateInProgress(PLUGIN, appName));

            if (mgr.getUpdateAvailable(PLUGIN, appName) != null)
                updated++;
        }
        if (updated > 0)
            mgr.notifyComplete(null, ngettext("1 plugin updated", "{0} plugins updated", updated, ctx));
        else
            mgr.notifyComplete(null, Messages.getString("Plugin update check complete", ctx));
    }

    /** this shouldn't throw anything */
    static void startPlugins(RouterContext ctx) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        Properties props = pluginProperties();
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String name = (String)e.getKey();
            if (name.startsWith(PREFIX) && name.endsWith(ENABLED)) {
                if (Boolean.parseBoolean((String) e.getValue())) {
                    String app = name.substring(PREFIX.length(), name.lastIndexOf(ENABLED));
                    // plugins could have been started after update
                    if (isPluginRunning(app, ctx))
                        continue;
                    try {
                        if (!startPlugin(ctx, app))
                            log.error("Failed to start plugin: " + app);
                    } catch (Throwable t) {
                        log.error("Failed to start plugin: " + app, t);
                    }
                }
            }
        }
    }

    /**
     *  Deferred deletion of plugins that we failed to delete before.
     *
     *  @since 0.9.13
     */
    private static void deferredDeletePlugins(RouterContext ctx) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        boolean changed = false;
        Properties props = pluginProperties();
        for (Iterator<Map.Entry<Object, Object>> iter = props.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Object, Object> e = iter.next();
            String name = (String)e.getKey();
            if (name.startsWith(PREFIX) && name.endsWith(ENABLED)) {
                // deferred deletion of a plugin
                if (e.getValue().equals(DELETED)) {
                    String app = name.substring(PREFIX.length(), name.lastIndexOf(ENABLED));
                    // shouldn't happen, this is run early
                    if (isPluginRunning(app, ctx))
                        continue;
                    File pluginDir = new File(ctx.getConfigDir(), PLUGIN_DIR + '/' + app);
                    boolean deleted = FileUtil.rmdir(pluginDir, false);
                    if (deleted) {
                        log.logAlways(Log.WARN, "Deferred deletion of " + pluginDir + " successful");
                        iter.remove();
                        changed = true;
                    } else {
                        if (log.shouldLog(Log.WARN))
                            log.warn("Deferred deletion of " + pluginDir + " failed");
                    }
                }
            }
        }
        if (changed)
            storePluginProperties(props);
    }

    /**
     *  @return true on success
     *  @throws just about anything, caller would be wise to catch Throwable
     */
    @SuppressWarnings("deprecation")
    public static boolean startPlugin(RouterContext ctx, String appName) throws Exception {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        File pluginDir = new File(ctx.getConfigDir(), PLUGIN_DIR + '/' + appName);
        String iconfile = null;
        if ((!pluginDir.exists()) || (!pluginDir.isDirectory())) {
            log.error("Cannot start nonexistent plugin: " + appName);
            disablePlugin(appName);
            return false;
        }

        // Do we need to extract an update?
        File pluginUpdate = new File(ctx.getConfigDir(), PLUGIN_DIR + '/' + appName + "/app.xpi2p.zip" );
        if(pluginUpdate.exists()) {
            // Compare the start time of the router with the plugin.
            if(ctx.router().getWhenStarted() > pluginUpdate.lastModified()) {
                if (!FileUtil.extractZip(pluginUpdate, pluginDir)) {
                    pluginUpdate.delete();
                    String foo = "Plugin '" + appName + "' failed to update! File '" + pluginUpdate +"' deleted. You may need to remove and install the plugin again.";
                    log.error(foo);
                    disablePlugin(appName);
                    throw new Exception(foo);
                } else {
                    pluginUpdate.delete();
                    // Need to always log this, and  log.logAlways() did not work for me.
                    System.err.println("INFO: Plugin updated: " + appName);
                }
            } // silently fail to update, because we have not restarted.
        }

        Properties props = pluginProperties(ctx, appName);




        String minVersion = ConfigClientsHelper.stripHTML(props, "min-i2p-version");
        if (minVersion != null &&
            VersionComparator.comp(CoreVersion.VERSION, minVersion) < 0) {
            String foo = "Plugin " + appName + " requires I2P version " + minVersion + " or higher";
            log.error(foo);
            disablePlugin(appName);
            throw new Exception(foo);
        }

        minVersion = ConfigClientsHelper.stripHTML(props, "min-java-version");
        if (minVersion != null &&
            VersionComparator.comp(System.getProperty("java.version"), minVersion) < 0) {
            String foo = "Plugin " + appName + " requires Java version " + minVersion + " or higher";
            log.error(foo);
            disablePlugin(appName);
            throw new Exception(foo);
        }

        String jVersion = LogsHelper.jettyVersion();
        minVersion = ConfigClientsHelper.stripHTML(props, "min-jetty-version");
        if (minVersion != null &&
            VersionComparator.comp(minVersion, jVersion) > 0) {
            String foo = "Plugin " + appName + " requires Jetty version " + minVersion + " or higher";
            log.error(foo);
            disablePlugin(appName);
            throw new Exception(foo);
        }

        String maxVersion = ConfigClientsHelper.stripHTML(props, "max-jetty-version");
        if (maxVersion != null &&
            VersionComparator.comp(maxVersion, jVersion) < 0) {
            String foo = "Plugin " + appName + " requires Jetty version " + maxVersion + " or lower";
            log.error(foo);
            disablePlugin(appName);
            throw new Exception(foo);
        }

        if (log.shouldLog(Log.INFO))
            log.info("Starting plugin: " + appName);

        // register themes
        File dir = new File(pluginDir, "console/themes");
        File[] tfiles = dir.listFiles();
        if (tfiles != null) {
            for (int i = 0; i < tfiles.length; i++) {
                String name = tfiles[i].getName();
                if (tfiles[i].isDirectory() && (!Arrays.asList(STANDARD_THEMES).contains(tfiles[i]))) {
                    // deprecated
                    ctx.router().setConfigSetting(ConfigUIHelper.PROP_THEME_PFX + name, tfiles[i].getAbsolutePath());
                    // we don't need to save
                }
            }
        }

        //handle console icons for plugins without web-resources through prop icon-code
        String fullprop = props.getProperty("icon-code");
        if(fullprop != null && fullprop.length() > 1){
            byte[] decoded = Base64.decode(fullprop);
            if(decoded != null) {
                NavHelper.setBinary(appName, decoded);
                iconfile = "/Plugins/pluginicon?plugin=" + appName;
            } else {
                iconfile = "/themes/console/images/plugin.png";
            }
        }

        // load and start things in clients.config
        File clientConfig = new File(pluginDir, "clients.config");
        if (clientConfig.exists()) {
            Properties cprops = new Properties();
            DataHelper.loadProps(cprops, clientConfig);
            List<ClientAppConfig> clients = ClientAppConfig.getClientApps(clientConfig);
            runClientApps(ctx, pluginDir, clients, "start");
        }

        // start console webapps in console/webapps
        ContextHandlerCollection server = WebAppStarter.getConsoleServer();
        if (server != null) {
            File consoleDir = new File(pluginDir, "console");
            Properties wprops = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            File webappDir = new File(consoleDir, "webapps");
            String fileNames[] = webappDir.list(RouterConsoleRunner.WarFilenameFilter.instance());
            if (fileNames != null) {
                if(!pluginWars.containsKey(appName))
                    pluginWars.put(appName, new ConcurrentHashSet<String>());
                for (int i = 0; i < fileNames.length; i++) {
                    try {
                        String warName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                        //log.error("Found webapp: " + warName);
                        // check for duplicates in $I2P
                        if (Arrays.asList(STANDARD_WEBAPPS).contains(warName)) {
                            log.error("Skipping duplicate webapp " + warName + " in plugin " + appName);
                            continue;
                        }
                        String enabled = wprops.getProperty(RouterConsoleRunner.PREFIX + warName + ENABLED);
                        if (! "false".equals(enabled)) {
                            if (log.shouldLog(Log.INFO))
                                log.info("Starting webapp: " + warName);
                            String path = new File(webappDir, fileNames[i]).getCanonicalPath();
                            WebAppStarter.startWebApp(ctx, server, warName, path);
                            pluginWars.get(appName).add(warName);
                        }
                    } catch (IOException ioe) {
                        log.error("Error resolving '" + fileNames[i] + "' in '" + webappDir, ioe);
                    }
                }
                // Check for iconfile in plugin.properties
                String icfile = ConfigClientsHelper.stripHTML(props, "console-icon");
                if (icfile != null && !icfile.contains("..")) {
                    StringBuilder buf = new StringBuilder(32);
                    buf.append('/').append(appName);
                    if (!icfile.startsWith("/"))
                        buf.append('/');
                    buf.append(icfile);
                    iconfile = buf.toString();
                }
            }
        } else {
            log.error("No console web server to start plugins?");
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
                        } catch (RuntimeException e) {
                            log.error("Plugin " + appName + " bad classpath element: " + f, e);
                        }
                    }
                }
                if (added)
                    Translate.clearCache();
            }
        }
        // add summary bar link
        String name = ConfigClientsHelper.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(ctx));
        if (name == null)
            name = ConfigClientsHelper.stripHTML(props, "consoleLinkName");
        String url = ConfigClientsHelper.stripHTML(props, "consoleLinkURL");
        if (name != null && url != null && name.length() > 0 && url.length() > 0) {
            String tip = ConfigClientsHelper.stripHTML(props, "consoleLinkTooltip_" + Messages.getLanguage(ctx));
            if (tip == null)
                tip = ConfigClientsHelper.stripHTML(props, "consoleLinkTooltip");
            NavHelper.registerApp(name, url, tip, iconfile);
        }

        return true;
    }

    /**
     *  @return true on success
     *  @throws just about anything, caller would be wise to catch Throwable
     */
    public static boolean stopPlugin(RouterContext ctx, String appName) throws Exception {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        File pluginDir = new File(ctx.getConfigDir(), PLUGIN_DIR + '/' + appName);
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
            runClientApps(ctx, pluginDir, clients, "stop");
        }

        // stop console webapps in console/webapps
        //ContextHandlerCollection server = WebAppStarter.getConsoleServer();
        //if (server != null) {
        /*
            File consoleDir = new File(pluginDir, "console");
            Properties props = RouterConsoleRunner.webAppProperties(consoleDir.getAbsolutePath());
            File webappDir = new File(consoleDir, "webapps");
            String fileNames[] = webappDir.list(RouterConsoleRunner.WarFilenameFilter.instance());
            if (fileNames != null) {
                for (int i = 0; i < fileNames.length; i++) {
                    String warName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                    if (Arrays.asList(STANDARD_WEBAPPS).contains(warName)) {
                        continue;
                    }
                    WebAppStarter.stopWebApp(server, warName);
                }
            }
        */
            if(pluginWars.containsKey(appName)) {
                Iterator <String> wars = pluginWars.get(appName).iterator();
                while (wars.hasNext()) {
                    String warName = wars.next();
                    WebAppStarter.stopWebApp(warName);
                }
                pluginWars.get(appName).clear();
            }
        //}

        // remove summary bar link
        Properties props = pluginProperties(ctx, appName);
        String name = ConfigClientsHelper.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(ctx));
        if (name == null)
            name = ConfigClientsHelper.stripHTML(props, "consoleLinkName");
        if (name != null && name.length() > 0)
            NavHelper.unregisterApp(name);

        if (log.shouldLog(Log.WARN))
            log.warn("Stopping plugin: " + appName);
        return true;
    }

    /** @return true on success - caller should call stopPlugin() first */
    static boolean deletePlugin(RouterContext ctx, String appName) throws Exception {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        File pluginDir = new File(ctx.getConfigDir(), PLUGIN_DIR + '/' + appName);
        if ((!pluginDir.exists()) || (!pluginDir.isDirectory())) {
            log.error("Cannot delete nonexistent plugin: " + appName);
            return false;
        }
        // uninstall things in clients.config
        File clientConfig = new File(pluginDir, "clients.config");
        if (clientConfig.exists()) {
            Properties props = new Properties();
            DataHelper.loadProps(props, clientConfig);
            List<ClientAppConfig> clients = ClientAppConfig.getClientApps(clientConfig);
            runClientApps(ctx, pluginDir, clients, "uninstall");
        }

        // unregister themes, and switch to default if we are unregistering the current theme
        File dir = new File(pluginDir, "console/themes");
        File[] tfiles = dir.listFiles();
        if (tfiles != null) {
            String current = ctx.getProperty(CSSHelper.PROP_THEME_NAME);
            Map<String, String> changes = new HashMap<String, String>();
            List<String> removes = new ArrayList<String>();
            for (int i = 0; i < tfiles.length; i++) {
                String name = tfiles[i].getName();
                if (tfiles[i].isDirectory() && (!Arrays.asList(STANDARD_THEMES).contains(tfiles[i]))) {
                    removes.add(ConfigUIHelper.PROP_THEME_PFX + name);
                    if (name.equals(current))
                        changes.put(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
                }
            }
            ctx.router().saveConfig(changes, removes);
        }

        boolean deleted = FileUtil.rmdir(pluginDir, false);
        Properties props = pluginProperties();
        for (Iterator<?> iter = props.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (name.startsWith(PREFIX + appName + '.'))
                iter.remove();
        }
        if (!deleted) {
            // This happens on Windows when there are plugin jars in classpath
            // Mark it as deleted, we will try again after restart
            log.logAlways(Log.WARN, "Deletion of " + pluginDir + " failed, will try again at restart");
            props.setProperty(PREFIX + appName + ENABLED, DELETED);
        }
        storePluginProperties(props);
        return true;
    }

    /** plugin.config */
    public static Properties pluginProperties(I2PAppContext ctx, String appName) {
        File cfgFile = new File(ctx.getConfigDir(), PLUGIN_DIR + '/' + appName + '/' + "plugin.config");
        Properties rv = new Properties();
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {}
        return rv;
    }

    /**
     *  plugins.config
     *  this auto-adds a property for every dir in the plugin directory
     */
    public static Properties pluginProperties() {
        File dir = I2PAppContext.getGlobalContext().getConfigDir();
        Properties rv = new Properties();
        File cfgFile = new File(dir, CONFIG_FILE);
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {}

        List<String> names = getAllPlugins();
        for (String name : names) {
            String prop = PREFIX + name + ENABLED;
            if (rv.getProperty(prop) == null)
                rv.setProperty(prop, "true");
        }
        return rv;
    }

    /**
     *  Is the plugin enabled in plugins.config?
     *  Default true
     *
     *  @since 0.8.13
     */
    public static boolean isPluginEnabled(String appName) {
        Properties props = pluginProperties();
        String prop = PREFIX + appName + ENABLED;
        return Boolean.parseBoolean(props.getProperty(prop, "true"));
    }

    /**
     *  Disable in plugins.config
     *
     *  @since 0.8.13
     */
    public static void disablePlugin(String appName) {
        Properties props = pluginProperties();
        String prop = PREFIX + appName + ENABLED;
        if (Boolean.parseBoolean(props.getProperty(prop, "true"))) {
            props.setProperty(prop, "false");
            storePluginProperties(props);
        }
    }

    /**
     *  all installed plugins whether enabled or not,
     *  but does NOT include plugins marked as deleted.
     *  @return non-null, sorted, modifiable
     */
    public static List<String> getPlugins() {
        List<String> rv = getAllPlugins();
        Properties props = pluginProperties();
        for (Iterator<String> iter = rv.iterator(); iter.hasNext(); ) {
            String app = iter.next();
            if (DELETED.equals(props.getProperty(PREFIX + app + ENABLED)))
                iter.remove();
        }
        Collections.sort(rv); // ensure the list is in sorted order.
        return rv;
    }

    /**
     *  all installed plugins whether enabled or not,
     *  DOES include plugins marked as deleted.
     *  @return non-null, unsorted, modifiable
     *  @since 0.9.13
     */
    private static List<String> getAllPlugins() {
        List<String> rv = new ArrayList<String>();
        File pluginDir = new File(I2PAppContext.getGlobalContext().getConfigDir(), PLUGIN_DIR);
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
        Map<String, String> rv = new HashMap<String, String>();
        List<String> names = getPlugins();
        for (String name : names) {
            Properties props = pluginProperties(ctx, name);
            String pubkey = props.getProperty("key");
            String signer = props.getProperty("signer");
            if (pubkey != null && signer != null && pubkey.length() == 172 && signer.length() > 0)
                rv.put(pubkey, signer);
        }
        return rv;
    }

    /**
     *  plugins.config
     */
    public static void storePluginProperties(Properties props) {
        File cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), CONFIG_FILE);
        try {
            DataHelper.storeProps(props, cfgFile);
        } catch (IOException ioe) {}
    }

    /**
     *  @param action "start" or "stop" or "uninstall"
     *  @throws just about anything if an app has a delay less than zero, caller would be wise to catch Throwable
     *  If no apps have a delay less than zero, it shouldn't throw anything
     */
    private static void runClientApps(RouterContext ctx, File pluginDir, List<ClientAppConfig> apps, String action) throws Exception {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        
        // initialize pluginThreadGroup and _pendingPluginClients
        String pluginName = pluginDir.getName();
        if (!pluginThreadGroups.containsKey(pluginName))
            pluginThreadGroups.put(pluginName, new ThreadGroup(pluginName));
        ThreadGroup pluginThreadGroup = pluginThreadGroups.get(pluginName);
        if (action.equals("start"))
            _pendingPluginClients.put(pluginName, new ConcurrentHashSet<SimpleTimer2.TimedEvent>());
        
        for(ClientAppConfig app : apps) {
            // If the client is a running ClientApp that we want to stop,
            // bypass all the logic below.
            if (action.equals("stop")) {
                String[] argVal = LoadClientAppsJob.parseArgs(app.args);
                // We must do all the substitution just as when started, so the
                // argument array comparison in getClientApp() works.
                // Do this after parsing so we don't need to worry about quoting
                for (int i = 0; i < argVal.length; i++) {
                    if (argVal[i].indexOf("$") >= 0) {
                        argVal[i] = argVal[i].replace("$I2P", ctx.getBaseDir().getAbsolutePath());
                        argVal[i] = argVal[i].replace("$CONFIG", ctx.getConfigDir().getAbsolutePath());
                        argVal[i] = argVal[i].replace("$PLUGIN", pluginDir.getAbsolutePath());
                    }
                }
                ClientApp ca = ctx.routerAppManager().getClientApp(app.className, argVal);
                if (ca != null) {
                    // even if (ca.getState() != ClientAppState.RUNNING), we do this, we don't want to fall thru
                    try {
                        ca.shutdown(LoadClientAppsJob.parseArgs(app.stopargs));
                    } catch (Throwable t) {
                        throw new Exception(t);
                    }
                    continue;
                }
            }

            if (action.equals("start") && app.disabled)
                continue;
            String argVal[];
            if (action.equals("start")) {
                // start
                argVal = LoadClientAppsJob.parseArgs(app.args);
            } else {
                String args;
                if (action.equals("stop"))
                    args = app.stopargs;
                else if (action.equals("uninstall"))
                    args = app.uninstallargs;
                else
                    throw new IllegalArgumentException("bad action");
                // args must be present
                if (args == null || args.length() <= 0)
                    continue;
                argVal = LoadClientAppsJob.parseArgs(args);
            }
            // do this after parsing so we don't need to worry about quoting
            for (int i = 0; i < argVal.length; i++) {
                if (argVal[i].indexOf("$") >= 0) {
                    argVal[i] = argVal[i].replace("$I2P", ctx.getBaseDir().getAbsolutePath());
                    argVal[i] = argVal[i].replace("$CONFIG", ctx.getConfigDir().getAbsolutePath());
                    argVal[i] = argVal[i].replace("$PLUGIN", pluginDir.getAbsolutePath());
                }
            }

            ClassLoader cl = null;
            if (app.classpath != null) {
                String cp = app.classpath;
                if (cp.indexOf("$") >= 0) {
                    cp = cp.replace("$I2P", ctx.getBaseDir().getAbsolutePath());
                    cp = cp.replace("$CONFIG", ctx.getConfigDir().getAbsolutePath());
                    cp = cp.replace("$PLUGIN", pluginDir.getAbsolutePath());
                }

                // Old way - add for the whole JVM
                //addToClasspath(cp, app.clientName, log);

                // New way - add only for this client
                // We cache the ClassLoader we start the client with, so
                // we can reuse it for stopping and uninstalling.
                // If we don't, the client won't be able to find its
                // static members.
                String clCacheKey = pluginName + app.className + app.args;
                if (!action.equals("start"))
                    cl = _clCache.get(clCacheKey);
                if (cl == null) {
                    URL[] urls = classpathToURLArray(cp, app.clientName, log);
                    if (urls != null) {
                        cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
                        if (action.equals("start"))
                            _clCache.put(clCacheKey, cl);
                    }
                }
            }

            if (app.delay < 0 && action.equals("start")) {
                // this will throw exceptions
                LoadClientAppsJob.runClientInline(app.className, app.clientName, argVal, log, cl);
            } else if (app.delay == 0 || !action.equals("start")) {
                // quick check, will throw ClassNotFoundException on error
                LoadClientAppsJob.testClient(app.className, cl);
                // run this guy now
                LoadClientAppsJob.runClient(app.className, app.clientName, argVal, ctx, log, pluginThreadGroup, cl);
            } else {
                // If there is some delay, there may be a really good reason for it.
                // Loading a class would be one of them!
                // So we do a quick check first, If it bombs out, we delay and try again.
                // If it bombs after that, then we throw the ClassNotFoundException.
                try {
                    // quick check
                    LoadClientAppsJob.testClient(app.className, cl);
                } catch (ClassNotFoundException ex) {
                    // Try again 1 or 2 seconds later. 
                    // This should be enough time. Although it is a lousy hack
                    // it should work for most cases.
                    // Perhaps it may be even better to delay a percentage
                    // if > 1, and reduce the delay time.
                    // Under normal circumstances there will be no delay at all.
                    try {
                        if (app.delay > 1) {
                            Thread.sleep(2000);
                        } else {
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException ie) {}
                    // quick check, will throw ClassNotFoundException on error
                    LoadClientAppsJob.testClient(app.className, cl);
                }
                // wait before firing it up
                SimpleTimer2.TimedEvent evt = new TrackedDelayedClient(pluginName, ctx.simpleTimer2(), ctx, app.className,
                                                                       app.clientName, argVal, pluginThreadGroup, cl);
                evt.schedule(app.delay);
            }
        }
    }

    /**
     *  Simple override to track whether a plugin's client is delayed and queued
     *  @since 0.9.6
     */
    private static class TrackedDelayedClient extends LoadClientAppsJob.DelayedRunClient {
        private final String _pluginName;

        public TrackedDelayedClient(String pluginName,
                                    SimpleTimer2 pool, RouterContext enclosingContext, String className, String clientName,
                                    String args[], ThreadGroup threadGroup, ClassLoader cl) {
            super(pool, enclosingContext, className, clientName, args, threadGroup, cl);
            _pluginName = pluginName;
            _pendingPluginClients.get(pluginName).add(this);
        }

        @Override
        public boolean cancel() {
            boolean rv = super.cancel();
            _pendingPluginClients.get(_pluginName).remove(this);
            return rv;
        }

        @Override
        public void timeReached() {
            super.timeReached();
            _pendingPluginClients.get(_pluginName).remove(this);
        }
    }

    public static boolean isPluginRunning(String pluginName, RouterContext ctx) {
        Log log = ctx.logManager().getLog(PluginStarter.class);
        
        boolean isJobRunning = false;
        Collection<SimpleTimer2.TimedEvent> pending = _pendingPluginClients.get(pluginName);
        if (pending != null && !pending.isEmpty()) {
            // TODO have a pending indication too
            isJobRunning = true;
        }
        boolean isWarRunning = false;
        if(pluginWars.containsKey(pluginName)) {
            Iterator <String> it = pluginWars.get(pluginName).iterator();
            while(it.hasNext() && !isWarRunning) {
                String warName = it.next();
                if(WebAppStarter.isWebAppRunning(warName)) {
                    isWarRunning = true;
                }
            }
        }

        boolean isClientThreadRunning = isClientThreadRunning(pluginName, ctx);
        if (log.shouldLog(Log.DEBUG))
            log.debug("plugin name = <" + pluginName + ">; threads running? " + isClientThreadRunning + "; webapp running? " + isWarRunning + "; jobs running? " + isJobRunning);
        return isClientThreadRunning || isWarRunning || isJobRunning;
        //
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug("plugin name = <" + pluginName + ">; threads running? " + isClientThreadRunning(pluginName) + "; webapp running? " + WebAppStarter.isWebAppRunning(pluginName) + "; jobs running? " + isJobRunning);
        //return isClientThreadRunning(pluginName) || WebAppStarter.isWebAppRunning(pluginName) || isJobRunning;
        //
    }
    
    /**
     * Returns <code>true</code> if one or more client threads are running in a given plugin.
     * @param pluginName
     * @return true if running
     */
    private static boolean isClientThreadRunning(String pluginName, RouterContext ctx) {
        ThreadGroup group = pluginThreadGroups.get(pluginName);
        if (group == null)
            return false;
        boolean rv = group.activeCount() > 0;
        
        // Plugins start before the eepsite, and will create the static Timer thread
        // in RolloverFileOutputStream, which never stops. Don't count it.
        // Ditto HSQLDB Timer (jwebcache)
        if (rv) {
            Log log = ctx.logManager().getLog(PluginStarter.class);
            Thread[] activeThreads = new Thread[128];
            int count = group.enumerate(activeThreads);
            boolean notRollover = false;
            for (int i = 0; i < count; i++) {
                if (activeThreads[i] != null) {
                    String name = activeThreads[i].getName();
                    if (!"org.eclipse.jetty.util.RolloverFileOutputStream".equals(name) &&
                        !name.startsWith("HSQLDB Timer"))
                        notRollover = true;
                    if (log.shouldLog(Log.DEBUG))
                        log.debug("Found " + activeThreads[i].getState() + " thread " + name + " for " + pluginName + ": " + name);
                }
            }
            rv = notRollover;
        }

        return rv;
    }
    
    /**
     *  Perhaps there's an easy way to use Thread.setContextClassLoader()
     *  but I don't see how to make it magically get used for everything.
     *  So add this to the whole JVM's classpath.
     */
/******
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
                if (log.shouldLog(Log.WARN))
                    log.warn("INFO: Adding plugin to classpath: " + f);
            } catch (Exception e) {
                log.error("Plugin client " + clientName + " bad classpath element: " + f, e);
            }
        }
    }
*****/

    /**
     *  @return null if no valid elements
     */
    private static URL[] classpathToURLArray(String classpath, String clientName, Log log) {
        StringTokenizer tok = new StringTokenizer(classpath, ",");
        List<URL> urls = new ArrayList<URL>();
        while (tok.hasMoreTokens()) {
            String elem = tok.nextToken().trim();
            File f = new File(elem);
            if (!f.isAbsolute()) {
                log.error("Plugin client " + clientName + " classpath element is not absolute: " + f);
                continue;
            }
            try {
                urls.add(f.toURI().toURL());
                if (log.shouldLog(Log.WARN))
                    log.warn("INFO: Adding plugin to classpath: " + f);
            } catch (IOException e) {
                log.error("Plugin client " + clientName + " bad classpath element: " + f, e);
            }
        }
        if (urls.isEmpty())
            return null;
        return urls.toArray(new URL[urls.size()]);
    }

    /**
     *  http://jimlife.wordpress.com/2007/12/19/java-adding-new-classpath-at-runtime/
     */
    private static void addPath(URL u) throws Exception {
        URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Class<URLClassLoader> urlClass = URLClassLoader.class;
        Method method = urlClass.getDeclaredMethod("addURL", URL.class);
        method.setAccessible(true);
        method.invoke(urlClassLoader, new Object[]{u});
    }

    /** translate a string */
    private static String ngettext(String s, String p, int n, I2PAppContext ctx) {
        return Messages.getString(n, s, p, ctx);
    }
}
