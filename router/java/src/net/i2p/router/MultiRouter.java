package net.i2p.router;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Fire up multiple routers in the same VM, all with their own RouterContext
 * (and all that entails).  In addition, this creates a root I2PAppContext for
 * any objects not booted through one of the RouterContexts.  Each of these 
 * contexts are configured through a simple properties file (where the name=value
 * contained in them are used for the context's getProperty(name)). <p />
 *
 * <b>Usage:</b><pre>
 *  MultiRouter globalContextFile routerContextFile[ routerContextFile]*
 * </pre>
 *
 * Each routerContext specified is used to boot up a single router.  It is HIGHLY
 * recommended that those context files contain a few base env properties: <ul>
 *  <li>loggerFilenameOverride=rN/logs/log-router-#.txt</li>
 *  <li>router.configLocation=rN/router.config</li>
 * </ul>
 * (where "rN" is an instance number, such as r0 or r9).
 * Additionally, two other properties might be useful:<ul>
 *  <li>i2p.vmCommSystem=true</li>
 *  <li>i2p.encryption=off</li>
 * </ul>
 * The first line tells the router to use an in-VM comm system for sending 
 * messages back and forth between routers (see net.i2p.transport.VMCommSystem),
 * and the second tells the router to stub out ElGamal, AES, and DSA code, reducing
 * the CPU load (but obviously making the router incapable of talking to things 
 * that need the encryption enabled).  To run a client app through a router that
 * has i2p.encryption=off, you should also add that line to the client's JVM
 * (for example, <code>java -Di2p.encryption=off -jar lib/i2ptunnel.jar</code>).<p />
 *
 * The multirouter waits until all of the routers are shut down (which none will
 * do on their own, so as before, you'll want to kill the proc or ^C it).
 */
public class MultiRouter {
    private static Log _log;
    private static ArrayList _routers = new ArrayList(8);
    private static I2PAppContext _defaultContext;
    
    public static void main(String args[]) {
        if ( (args == null) || (args.length <= 1) ) {
            usage();
            return;
        }
        _defaultContext = new I2PAppContext(getEnv(args[0]));
        
        _log = _defaultContext.logManager().getLog(MultiRouter.class);
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        
        _defaultContext.clock().setOffset(0);
        
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                Thread.currentThread().setName("Router* Shutdown");
                try { Thread.sleep(120*1000); } catch (InterruptedException ie) {}
                Runtime.getRuntime().halt(-1);
            }
        });
        
        for (int i = 1; i < args.length; i++) {
            Router router = new Router(getEnv(args[i]));
            router.setKillVMOnEnd(false);
            _routers.add(router);
            _log.info("Router " + i + " created from " + args[i]);
            //try { Thread.sleep(2*1000); } catch (InterruptedException ie) {}
        }
        
        for (int i = 0; i < _routers.size(); i++) {
            Router r = (Router)_routers.get(i);
            long offset = r.getContext().random().nextLong(Router.CLOCK_FUDGE_FACTOR/2);
            if (r.getContext().random().nextBoolean())
                offset = 0 - offset;
            r.getContext().clock().setOffset(offset, true);
            r.runRouter();
            _log.info("Router " + i + " started with clock offset " + offset);
            try { Thread.sleep(2*1000 + new java.util.Random().nextInt(2)*1000); } catch (InterruptedException ie) {}
        }
        _log.info("All " + _routers.size() + " routers started up");
        waitForCompletion();
    }
    
    private static Properties getEnv(String filename) {
        Properties props = new Properties();
        try {
            props.load(new FileInputStream(filename));
            props.setProperty("time.disabled", "true");
            return props;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
    }
    
    private static void waitForCompletion() {
        while (true) {
            int alive = 0;
            for (int i = 0; i < _routers.size(); i++) {
                Router r = (Router)_routers.get(i);
                if (!r.isAlive()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Router " + i + " is dead");
                } else {
                    alive++;
                }
            }
            if (alive > 0) {
                try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
            } else {
                break;
            }
        }
        _log.info("All routers shut down");
    }
    
    private static void usage() {
        System.err.println("Usage: MultiRouter globalContextFile routerContextFile[ routerContextFile]*");
        System.err.println("       The context files contain key=value entries specifying properties");
        System.err.println("       to load into the given context.  In addition, each routerContextFile");
        System.err.println("       in turn is used to boot a router");
    }
}
