package net.i2p.router.startup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.SecureFileOutputStream;


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
    private final static long STARTUP_DELAY = 2*60*1000;
    
    private static final String PROP_CLIENT_CONFIG_FILENAME = "router.clientConfigFile";
    private static final String DEFAULT_CLIENT_CONFIG_FILENAME = "clients.config";
    private static final String PREFIX = "clientApp.";

    // let's keep this really simple
    public String className;
    public String clientName;
    public String args;
    public long delay;
    public boolean disabled;
    /** @since 0.7.12 */
    public String classpath;
    /** @since 0.7.12 */
    public String stopargs;
    /** @since 0.7.12 */
    public String uninstallargs;

    public ClientAppConfig(String cl, String client, String a, long d, boolean dis) {
        className = cl;
        clientName = client;
        args = a;
        delay = d;
        disabled = dis;
    }

    /** @since 0.7.12 */
    public ClientAppConfig(String cl, String client, String a, long d, boolean dis, String cp, String sa, String ua) {
        this(cl, client, a, d, dis);
        classpath = cp;
        stopargs = sa;
        uninstallargs = ua;
    }

    public static File configFile(I2PAppContext ctx) {
        String clientConfigFile = ctx.getProperty(PROP_CLIENT_CONFIG_FILENAME, DEFAULT_CLIENT_CONFIG_FILENAME);
        File cfgFile = new File(clientConfigFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(ctx.getConfigDir(), clientConfigFile);
        return cfgFile;
    }

    private static Properties getClientAppProps(RouterContext ctx) {
        Properties rv = new Properties();
        File cfgFile = configFile(ctx);
        
        // fall back to use router.config's clientApp.* lines
        if (!cfgFile.exists()) {
            System.out.println("Warning - No client config file " + cfgFile.getAbsolutePath());
            rv.putAll(ctx.router().getConfigMap());
            return rv;
        }
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {
            System.out.println("Error loading the client app properties from " + cfgFile.getAbsolutePath() + ' ' + ioe);
        }
        
        return rv;
    }
    
    /*
     * Go through the properties, and return a List of ClientAppConfig structures
     */
    public static List<ClientAppConfig> getClientApps(RouterContext ctx) {
        Properties clientApps = getClientAppProps(ctx);
        return getClientApps(clientApps);
    }

    /*
     * Go through the properties, and return a List of ClientAppConfig structures
     *
     * @since 0.7.12
     */
    public static List<ClientAppConfig> getClientApps(File cfgFile) {
        Properties clientApps = new Properties();
        try {
            DataHelper.loadProps(clientApps, cfgFile);
        } catch (IOException ioe) {
            return Collections.EMPTY_LIST;
        }
        return getClientApps(clientApps);
    }

    /*
     * Go through the properties, and return a List of ClientAppConfig structures
     *
     * @since 0.7.12
     */
    private static List<ClientAppConfig> getClientApps(Properties clientApps) {
        List<ClientAppConfig> rv = new ArrayList(8);
        int i = 0;
        while (true) {
            String className = clientApps.getProperty(PREFIX + i + ".main");
            if (className == null) 
                break;
            String clientName = clientApps.getProperty(PREFIX + i + ".name");
            String args = clientApps.getProperty(PREFIX + i + ".args");
            String delayStr = clientApps.getProperty(PREFIX + i + ".delay");
            String onBoot = clientApps.getProperty(PREFIX + i + ".onBoot");
            String disabled = clientApps.getProperty(PREFIX + i + ".startOnLoad");
            String classpath = clientApps.getProperty(PREFIX + i + ".classpath");
            String stopargs = clientApps.getProperty(PREFIX + i + ".stopargs");
            String uninstallargs = clientApps.getProperty(PREFIX + i + ".uninstallargs");
            i++;
            boolean dis = disabled != null && "false".equals(disabled);

            boolean onStartup = false;
            if (onBoot != null)
                onStartup = "true".equals(onBoot) || "yes".equals(onBoot);

            long delay = (onStartup ? 0 : STARTUP_DELAY);
            if (delayStr != null && !onStartup)
                try { delay = 1000*Integer.parseInt(delayStr); } catch (NumberFormatException nfe) {}

            rv.add(new ClientAppConfig(className, clientName, args, delay, dis,
                   classpath, stopargs, uninstallargs));
        }
        return rv;
    }

    /** classpath and stopargs not supported */
    public static void writeClientAppConfig(RouterContext ctx, List apps) {
        File cfgFile = configFile(ctx);
        FileOutputStream fos = null;
        try {
            fos = new SecureFileOutputStream(cfgFile);
            StringBuilder buf = new StringBuilder(2048);
            for(int i = 0; i < apps.size(); i++) {
                ClientAppConfig app = (ClientAppConfig) apps.get(i);
                buf.append(PREFIX).append(i).append(".main=").append(app.className).append("\n");
                buf.append(PREFIX).append(i).append(".name=").append(app.clientName).append("\n");
                if (app.args != null)
                    buf.append(PREFIX).append(i).append(".args=").append(app.args).append("\n");
                buf.append(PREFIX).append(i).append(".delay=").append(app.delay / 1000).append("\n");
                buf.append(PREFIX).append(i).append(".startOnLoad=").append(!app.disabled).append("\n");
            }
            fos.write(buf.toString().getBytes("UTF-8"));
        } catch (IOException ioe) {
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
}

