package net.i2p.router.startup;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SystemVersion;


/**
 * Contains a really simple ClientApp "structure" and some static methods
 * so they can be used both by LoadClientAppsJob and by the configuration
 * page in the router console.
 *
 * <pre>
 *
 * clients.config format:
 *
 * Lines are of the form clientApp.x.prop=val, where x is the app number.
 * App numbers MUST start with 0 and be consecutive.
 *
 * Properties are as follows:
 *	main: Full class name. Required. The main() method in this
 *	      class will be run.
 *	name: Name to be displayed on console.
 *	args: Arguments to the main class, separated by spaces or tabs.
 *	      Arguments containing spaces or tabs may be quoted with ' or "
 *	delay: Seconds before starting, default 120
 *	onBoot: {true|false}, default false, forces a delay of 0,
 *	        overrides delay setting
 *	startOnLoad: {true|false} Is the client to be run at all?
 *                    Default true
 *
 * The following additional properties are used only by plugins:
 *	stopargs: Arguments to stop the client.
 *	uninstallargs: Arguments to stop the client.
 *	classpath: Additional classpath elements for the client,
 *	           separated by commas.
 *
 * The following substitutions are made in the args, stopargs,
 * uninstallargs, and classpath lines, for plugins only:
 *	$I2P: The base I2P install directory
 *	$CONFIG: The user's configuration directory (e.g. ~/.i2p)
 *	$PLUGIN: This plugin's directory (e.g. ~/.i2p/plugins/foo)
 *
 * All properties except "main" are optional.
 * Lines starting with "#" are comments.
 *
 * If the delay is less than zero, the client is run immediately,
 * in the same thread, so that exceptions may be propagated to the console.
 * In this case, the client should either throw an exception, return quickly,
 * or spawn its own thread.
 * If the delay is greater than or equal to zero, it will be run
 * in a new thread, and exceptions will be logged but not propagated
 * to the console.
 *
 * </pre>
 */
public class ClientAppConfig {
    /** wait 2 minutes before starting up client apps */
    private final static long DEFAULT_STARTUP_DELAY = 2*60*1000;
    /** speed up i2ptunnel without rewriting clients.config */
    private final static long I2PTUNNEL_STARTUP_DELAY = -1000;
    
    private static final String PROP_CLIENT_CONFIG_FILENAME = "router.clientConfigFile";
    private static final String DEFAULT_CLIENT_CONFIG_FILENAME = "clients.config";
    private static final String CLIENT_CONFIG_DIR = "clients.config.d";
    private static final String PREFIX = "clientApp.";

    // let's keep this really simple
    // Following 4 may be edited in router console
    public String className;
    public String clientName;
    public String args;
    public boolean disabled;
    public final long delay;
    /** @since 0.7.12 */
    public final String classpath;
    /** @since 0.7.12 */
    public final String stopargs;
    /** @since 0.7.12 */
    public final String uninstallargs;
    /** @since 0.9.42 */
    File configFile;

    public ClientAppConfig(String cl, String client, String a, long d, boolean dis) {
        this(cl, client, a, d, dis, null, null, null);
    }

    /** @since 0.7.12 */
    public ClientAppConfig(String cl, String client, String a, long d, boolean dis, String cp, String sa, String ua) {
        className = cl;
        clientName = client;
        args = a;
        delay = d;
        disabled = dis;
        classpath = cp;
        stopargs = sa;
        uninstallargs = ua;
    }

    /*
     * Only valid for the router's clients (not plugins).
     * Only valid after getClientApps(ctx) has been called.
     * @since 0.9.42
     */
    public synchronized static boolean isSplitConfig(I2PAppContext ctx) {
        File dir = new File(ctx.getConfigDir(), CLIENT_CONFIG_DIR);
        return dir.exists() && !configFile(ctx).exists();
    }

    /*
     * This is the old config file. Only valid if not a split config.
     */
    public static File configFile(I2PAppContext ctx) {
        String clientConfigFile = ctx.getProperty(PROP_CLIENT_CONFIG_FILENAME, DEFAULT_CLIENT_CONFIG_FILENAME);
        File cfgFile = new File(clientConfigFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(ctx.getConfigDir(), clientConfigFile);
        return cfgFile;
    }

    /*
     * This is the config dir. Only valid if a split config.
     * @since 0.9.48
     */
    public static File configDir(I2PAppContext ctx) {
        return new File(ctx.getConfigDir(), CLIENT_CONFIG_DIR);
    }

    /*
     * Go through the files, and return a List of ClientAppConfig structures
     * This is for the router.
     */
    public synchronized static List<ClientAppConfig> getClientApps(RouterContext ctx) {
        File dir = new SecureDirectory(ctx.getConfigDir(), CLIENT_CONFIG_DIR);
        // clients.config
        List<ClientAppConfig> rv = new ArrayList<ClientAppConfig>(8);
        File cf = configFile(ctx);
        try {
            List<ClientAppConfig> cacs = getClientApps(cf);
            if (!cacs.isEmpty()) {
                if (!SystemVersion.isAndroid())
                    MigrateJetty.migrate(ctx, cacs);
                boolean ok = migrate(ctx, cacs, cf, dir);
                if (!ok)
                    rv.addAll(cacs);
            }
        } catch (IOException ioe) {
            ctx.logManager().getLog(ClientAppConfig.class).error("Error loading the client app properties from " + cf, ioe);
            System.out.println("Error loading the client app properties from " + cf + ' ' + ioe);
        }
        // clients.config.d
        if (dir.isDirectory()) {
            File[] files = dir.listFiles(new FileSuffixFilter(".config"));
            if (files != null && files.length > 0) {
                // sort so the returned order is consistent
                Arrays.sort(files);
                for (File f : files) {
                    if (!f.getName().endsWith(".config"))
                        continue;
                    if (!f.isFile())
                        continue;
                    try {
                        List<ClientAppConfig> cacs = getClientApps(f);
                        if (!cacs.isEmpty()) {
                            rv.addAll(cacs);
                        } else {
                            ctx.logManager().getLog(ClientAppConfig.class).error("Error loading the client app properties from " + f);
                            System.out.println("Error loading the client app properties from " + f);
                        }
                    } catch (IOException ioe) {
                        ctx.logManager().getLog(ClientAppConfig.class).error("Error loading the client app properties from " + f, ioe);
                        System.out.println("Error loading the client app properties from " + f + ' ' + ioe);
                    }
                }
            }
        }
        return rv;
    }

    /*
     * Go through the file, and return a List of ClientAppConfig structures
     *
     * @since 0.7.12
     */
    public synchronized static List<ClientAppConfig> getClientApps(File cfgFile) throws IOException {
        if (!cfgFile.isFile())
            return new ArrayList<ClientAppConfig>();
        Properties clientApps = new Properties();
        DataHelper.loadProps(clientApps, cfgFile);
        List<ClientAppConfig> rv =  getClientApps(clientApps);
        for (ClientAppConfig cac : rv) {
            cac.configFile = cfgFile;
        }
        return rv;
    }

    /*
     * Migrate apps from file to individual files in dir
     *
     * @return success
     * @since 0.9.42
     */
    private static boolean migrate(I2PAppContext ctx, List<ClientAppConfig> apps, File from, File dir) {
        // don't migrate Android
        if (SystemVersion.isAndroid())
            return false;
        // don't migrate portable
        try {
            if (ctx.getConfigDir().getCanonicalPath().equals(ctx.getBaseDir().getCanonicalPath()))
                return false;
        } catch (IOException ioe) {}
        if (!dir.isDirectory() && !dir.mkdirs())
            return false;
        boolean ok = true;
        for (int i = 0; i < apps.size(); i++) {
            ClientAppConfig cac = apps.get(i);
            String name = i + "-" + cac.className + "-clients.config";
            if (i < 10)
                name = '0' + name;
            File f = new File(dir, name);
            cac.configFile = f;
            try {
                writeClientAppConfig(ctx, cac);
            } catch (IOException ioe) {
                ctx.logManager().getLog(ClientAppConfig.class).error("Error migrating the client app properties to " + f, ioe);
                System.out.println("Error migrating the client app properties to " + f + ' ' + ioe);
                cac.configFile = from;
                ok = false;
            }
        }
        if (ok) {
            if (!FileUtil.rename(from, new File(from.getAbsolutePath() + ".bak")))
                from.delete();
        }
        return ok;
    }

    /*
     * Go through the properties, and return a List of ClientAppConfig structures
     *
     * @since 0.7.12
     */
    private static List<ClientAppConfig> getClientApps(Properties clientApps) {
        List<ClientAppConfig> rv = new ArrayList<ClientAppConfig>(8);
        int i = 0;
        while (true) {
            ClientAppConfig cac = getClientApp(clientApps, PREFIX + i);
            if (cac == null)
                break;
            i++;
            rv.add(cac);
        }
        return rv;
    }

    /*
     * Go through the properties, and get a single ClientAppConfig structure
     * with this prefix
     *
     * @return null if none
     * @since 0.9.42 split out from above
     */
    private static ClientAppConfig getClientApp(Properties clientApps, String prefix) {
            String className = clientApps.getProperty(prefix + ".main");
            if (className == null) 
                return null;
            String clientName = clientApps.getProperty(prefix + ".name");
            String args = clientApps.getProperty(prefix + ".args");
            String delayStr = clientApps.getProperty(prefix + ".delay");
            String onBoot = clientApps.getProperty(prefix + ".onBoot");
            String disabled = clientApps.getProperty(prefix + ".startOnLoad");
            String classpath = clientApps.getProperty(prefix + ".classpath");
            String stopargs = clientApps.getProperty(prefix + ".stopargs");
            String uninstallargs = clientApps.getProperty(prefix + ".uninstallargs");
            boolean dis = disabled != null && "false".equals(disabled);

            boolean onStartup = false;
            if (onBoot != null)
                onStartup = "true".equals(onBoot) || "yes".equals(onBoot);

            long delay;
            if (onStartup) {
                delay = 0;
            } else if (className.equals("net.i2p.i2ptunnel.TunnelControllerGroup")) {
                // speed up the start of i2ptunnel for everybody without rewriting clients.config
                delay = I2PTUNNEL_STARTUP_DELAY;
            } else {
                delay = DEFAULT_STARTUP_DELAY;
                if (delayStr != null)
                    try { delay = 1000*Integer.parseInt(delayStr); } catch (NumberFormatException nfe) {}
            }
            return new ClientAppConfig(className, clientName, args, delay, dis,
                                       classpath, stopargs, uninstallargs);
    }

    /**
     * Classpath and stopargs not supported.
     * All other apps in the file will be deleted.
     * Do not use if multiple apps in a single file - use writeClientAppConfig(ctx, apps).
     * If app.configFile is null, a new file will be created and assigned.
     *
     * @since 0.9.42
     */
    public synchronized static void writeClientAppConfig(I2PAppContext ctx, ClientAppConfig app) throws IOException {
        if (app.configFile == null) {
            File dir = new SecureDirectory(ctx.getConfigDir(), CLIENT_CONFIG_DIR);
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("Can't create " + dir);
            int i = 0;
            String[] files = dir.list();
            if (files != null)
                i = files.length;
            File f;
            do {
                String name = i + "-" + app.className + "-clients.config";
                if (i < 10)
                    name = '0' + name;
                f = new File(dir, name);
                i++;
            } while (f.exists());
            app.configFile = f;
        }
        writeClientAppConfig(Collections.singletonList(app), app.configFile);
    }

    /**
     * Classpath and stopargs not supported.
     * All other apps in the files will be deleted.
     * Do not add apps with this method - use writeClientAppConfig(ctx, app).
     * Do not delete apps with this method - use deleteClientAppConfig().
     *
     * @since 0.9.42 split out from above
     */
    public synchronized static void writeClientAppConfig(I2PAppContext ctx, List<ClientAppConfig> apps) throws IOException {
        // Gather the set of config files
        ObjectCounter<File> counter = new ObjectCounter<File>();
        for (ClientAppConfig cac : apps) {
            File f = cac.configFile;
            if (f == null)
                throw new IllegalArgumentException("No file for " + cac.className);
            counter.increment(f);
        }
        IOException e = null;
        // Write the config files
        Set<File> files = counter.objects();
        // For each file, write all the configs for that file
        for (File f : files) {
            // Gather configs for this file
            List<ClientAppConfig> cacs = new ArrayList<ClientAppConfig>(8);
            for (ClientAppConfig cac : apps) {
                if (cac.configFile.equals(f))
                    cacs.add(cac);
            }
            try {
                writeClientAppConfig(cacs, f);
            } catch (IOException ioe) {
                if (e == null)
                    e = ioe;
            }
        }
        if (e != null)
            throw e;
    }

    /**
     * All to a single file, apps.configFile ignored
     *
     * @throws IllegalArgumentException if null cfgFile
     * @since 0.9.42 split out from above
     */
    private static void writeClientAppConfig(List<ClientAppConfig> apps, File cfgFile) throws IOException {
        if (cfgFile == null)
            throw new IllegalArgumentException("No file");
        Properties props = new OrderedProperties();
        for(int i = 0; i < apps.size(); i++) {
            ClientAppConfig app = apps.get(i);
            String pfx = PREFIX + i;
            props.setProperty(pfx + ".main", app.className);
            props.setProperty(pfx + ".name", app.clientName);
            if (app.args != null)
                props.setProperty(pfx + ".args", app.args);
            props.setProperty(pfx + ".delay", Long.toString(app.delay / 1000));
            props.setProperty(pfx + ".startOnLoad", Boolean.toString(!app.disabled));
        }
        DataHelper.storeProps(props, cfgFile);
    }

    /**
     * This works for both split and non-split config.
     * If this is the only config in the file, the file will be deleted;
     * otherwise the file will be saved with the remaining configs.
     *
     * @return success
     * @throws IllegalArgumentException if cac has a null configfile
     * @since 0.9.42
     */
    public synchronized static boolean deleteClientAppConfig(ClientAppConfig cac) throws IOException {
        File f = cac.configFile;
        if (f == null)
            throw new IllegalArgumentException("No file for " + cac.className);
        List<ClientAppConfig> cacs = getClientApps(f);
        if (cacs.remove(cac)) {
            if (cacs.isEmpty())
                return f.delete();
            writeClientAppConfig(cacs, f);
            return true;
        }
        return false;
    }

    /**
     * @since 0.9.42
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(className);
    }

    /**
     * Matches on class, args, and name only
     * @since 0.9.42
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null) return false;
        if (o instanceof ClientAppConfig) {
            ClientAppConfig cac = (ClientAppConfig) o;
            return DataHelper.eq(className, cac.className) &&
                   DataHelper.eq(clientName, cac.clientName) &&
                   DataHelper.eq(args, cac.args);
        }
        return false;
    }
}
