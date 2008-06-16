package net.i2p.router.startup;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;


/**
 * Contains a really simple ClientApp "structure" and some static methods
 * so they can be used both by LoadClientAppsJob and by the configuration
 * page in the router console.
 *
 */
public class ClientAppConfig {
    /** wait 2 minutes before starting up client apps */
    private final static long STARTUP_DELAY = 2*60*1000;
    
    private static final String PROP_CLIENT_CONFIG_FILENAME = "router.clientConfigFile";
    private static final String DEFAULT_CLIENT_CONFIG_FILENAME = "clients.config";

    // let's keep this really simple
    public String className;
    public String clientName;
    public String args;
    public long delay;
    public boolean disabled;
    public ClientAppConfig(String cl, String client, String a, long d, boolean dis) {
        className = cl;
        clientName = client;
        args = a;
        delay = d;
        disabled = dis;
    }

    private static Properties getClientAppProps(RouterContext ctx) {
        Properties rv = new Properties();
        String clientConfigFile = ctx.getProperty(PROP_CLIENT_CONFIG_FILENAME, DEFAULT_CLIENT_CONFIG_FILENAME);
        File cfgFile = new File(clientConfigFile);
        
        // fall back to use router.config's clientApp.* lines
        if (!cfgFile.exists()) 
            return ctx.router().getConfigMap();
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
        
        return rv;
    }
    
    /*
     * Go through the properties, and return a List of ClientAppConfig structures
     */
    public static List getClientApps(RouterContext ctx) {
        Properties clientApps = getClientAppProps(ctx);
        List rv = new ArrayList(5);
        int i = 0;
        while (true) {
            String className = clientApps.getProperty("clientApp."+i+".main");
            if (className == null) 
                break;
            String clientName = clientApps.getProperty("clientApp."+i+".name");
            String args = clientApps.getProperty("clientApp."+i+".args");
            String delayStr = clientApps.getProperty("clientApp." + i + ".delay");
            String onBoot = clientApps.getProperty("clientApp." + i + ".onBoot");
            String disabled = clientApps.getProperty("clientApp." + i + ".startOnLoad");
            i++;
            boolean dis = disabled != null && "false".equals(disabled);

            boolean onStartup = false;
            if (onBoot != null)
                onStartup = "true".equals(onBoot) || "yes".equals(onBoot);

            long delay = (onStartup ? 0 : STARTUP_DELAY);
            if (delayStr != null && !onStartup)
                try { delay = 1000*Integer.parseInt(delayStr); } catch (NumberFormatException nfe) {}

            rv.add(new ClientAppConfig(className, clientName, args, delay, dis));
        }
        return rv;
    }

}

