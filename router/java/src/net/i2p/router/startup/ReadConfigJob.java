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
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simply read the router config
 */
public class ReadConfigJob extends JobImpl {
    private final static long DELAY = 30*1000; // reread every 30 seconds
    private long _lastRead = -1;

    public ReadConfigJob(RouterContext ctx) {
        super(ctx);
    }
    
    public String getName() { return "Read Router Configuration"; }
    public void runJob() {
        if (shouldReread()) {
            getContext().router().readConfig();
            _lastRead = getContext().clock().now();
        }
        getTiming().setStartAfter(getContext().clock().now() + DELAY);
        getContext().jobQueue().addJob(this);
    }
    
    private boolean shouldReread() {
        File configFile = new File(getContext().router().getConfigFilename());
        if (!configFile.exists()) return false;
        if (configFile.lastModified() > _lastRead) 
            return true;
        else
            return false;
    }
}
