package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;

import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Simply read the router config
 */
public class ReadConfigJob extends JobImpl {
    private final static long DELAY = 30*1000; // reread every 30 seconds

    public ReadConfigJob(RouterContext ctx) {
        super(ctx);
    }
    
    public String getName() { return "Read Router Configuration"; }
    public void runJob() {
        doRead(_context);
        getTiming().setStartAfter(_context.clock().now() + DELAY);
        _context.jobQueue().addJob(this);
    }
    
    public static void doRead(RouterContext ctx) { 
        Router r = ctx.router();
        String f = r.getConfigFilename();
        Properties config = getConfig(ctx, f);
        for (Iterator iter = config.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String val = config.getProperty(name);
            r.setConfigSetting(name, val);
        }
    }
    
    private static Properties getConfig(RouterContext ctx, String filename) {
        Log log = ctx.logManager().getLog(ReadConfigJob.class);
        log.debug("Config file: " + filename);
        Properties props = new Properties();
        FileInputStream fis = null;
        try {
            File f = new File(filename);
            if (f.canRead()) {
                fis = new FileInputStream(f);
                props.load(fis);
            } else {
                log.error("Configuration file " + filename + " does not exist");
            }
        } catch (Exception ioe) {
            log.error("Error loading the router configuration from " + filename, ioe);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
        return props;
    }
}
