package net.i2p.router.startup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static final String PREFIX = "clientApp.";

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
        if (!cfgFile.isAbsolute())
            cfgFile = new File(ctx.getConfigDir(), clientConfigFile);
        
        // fall back to use router.config's clientApp.* lines
        if (!cfgFile.exists()) {
            System.out.println("Warning - No client config file " + cfgFile.getAbsolutePath());
            return ctx.router().getConfigMap();
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
    public static List getClientApps(RouterContext ctx) {
        Properties clientApps = getClientAppProps(ctx);
        List rv = new ArrayList(5);
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

    public static void writeClientAppConfig(RouterContext ctx, List apps) {
        String clientConfigFile = ctx.getProperty(PROP_CLIENT_CONFIG_FILENAME, DEFAULT_CLIENT_CONFIG_FILENAME);
        File cfgFile = new File(clientConfigFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(ctx.getConfigDir(), clientConfigFile);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cfgFile);
            StringBuilder buf = new StringBuilder(2048);
            for(int i = 0; i < apps.size(); i++) {
                ClientAppConfig app = (ClientAppConfig) apps.get(i);
                buf.append(PREFIX).append(i).append(".main=").append(app.className).append("\n");
                buf.append(PREFIX).append(i).append(".name=").append(app.clientName).append("\n");
                buf.append(PREFIX).append(i).append(".args=").append(app.args).append("\n");
                buf.append(PREFIX).append(i).append(".delay=").append(app.delay / 1000).append("\n");
                buf.append(PREFIX).append(i).append(".startOnLoad=").append(!app.disabled).append("\n");
            }
            fos.write(buf.toString().getBytes());
        } catch (IOException ioe) {
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
}

