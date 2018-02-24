package net.i2p.router;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;
import java.util.Scanner;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;

/**
 * Fire up multiple routers in the same VM, all with their own RouterContext
 * (and all that entails).  In addition, this creates a root I2PAppContext for
 * any objects not booted through one of the RouterContexts.  Each of these 
 * contexts are configured through a simple properties file (where the name=value
 * contained in them are used for the context's getProperty(name)). <p>
 *
 * <b>Usage:</b><pre>
 *  MultiRouter numberRouters
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
 * (for example, <code>java -Di2p.encryption=off -jar lib/i2ptunnel.jar</code>).<p>
 * 
 * To make the router console work, either run from a directory containing 
 * lib/, webapps/, docs/, etc., or point i2p.dir.base to a directory containing the
 * above.
 *
 * The multirouter waits until all of the routers are shut down (which none will
 * do on their own, so as before, you'll want to kill the proc or ^C it).
 */
public class MultiRouter {
	
	private static final int BASE_PORT = 5000;

	private static int nbrRouters;
	
	private static PrintStream _out;
	private static ArrayList<Router> _routers = new ArrayList<Router>(8);
    private static I2PAppContext _defaultContext;
    
    public static void main(String args[]) {
        if ( (args == null) || (args.length < 1) ) {
            usage();
            return;
        }
        Scanner scan = null;
        try {
            scan = new Scanner(args[0]);
            if (!scan.hasNextInt()) {
                usage();
                return;
            }
            nbrRouters = scan.nextInt();
            if (nbrRouters < 0) {
                usage();
                return;
            }
        } finally {
            if (scan != null) scan.close();
        }
        
        _out = System.out;

        buildClientProps(0);
        _defaultContext = new I2PAppContext(buildRouterProps(0));
        _defaultContext.clock().setOffset(0);
        
        _out.println("RouterConsole for Router 0 is listening on: 127.0.0.1:" + (BASE_PORT-1));

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
            	_out.println("Shutting down in a few moments..");
            	for(Router r : _routers) {
            		r.shutdown(0);
            	}
                try { Thread.sleep(1500); } catch (InterruptedException ie) {}
                Runtime.getRuntime().halt(0);
            }
        });

        for (int i = 0; i < nbrRouters; i++) {
            Router router = new Router(buildRouterProps(i));
            router.setKillVMOnEnd(false);
            _routers.add(router);
            _out.println("Router " + i + " was created");
            try { Thread.sleep(100); } catch (InterruptedException ie) {}
        }
        
        for (int i = 0; i < nbrRouters; i++) {
        	final Router r = _routers.get(i);
            long offset = r.getContext().random().nextLong(Router.CLOCK_FUDGE_FACTOR/2);
            if (r.getContext().random().nextBoolean())
                offset = 0 - offset;
            r.getContext().clock().setOffset(offset, true);
            
            /* Start the routers in separate threads since it takes some time. */
            (new Thread() {
            	  public void run() {
            		  r.runRouter();
            	  }
            }).start();
            try { Thread.sleep(100); } catch (InterruptedException ie) {}
            
            _out.println("Router " + i + " was started with time offset " + offset);
        }
        _out.println("All routers have been started");
        
        /* Wait for routers to start services and generate keys
         * before doing the internal reseed. */
        int waitForRouters = (nbrRouters/10)*1000;
        _out.println("Waiting " + waitForRouters/1000 +  " seconds for routers to start" + 
                     "before doing the internal reseed");
        try { Thread.sleep(waitForRouters); } catch (InterruptedException ie) {}   
        internalReseed();
        
        waitForCompletion();
    }
    
    private static void internalReseed() {

    	HashSet<RouterInfo> riSet = new HashSet<RouterInfo>();
    	for(Router r : _routers) {
    		riSet.addAll(r.getContext().netDb().getRouters());
    	}
		for(Router r : _routers) {
    		for(RouterInfo ri : riSet){
    			r.getContext().netDb().publish(ri);
    		}
    	}
		_out.println(riSet.size() + " RouterInfos were reseeded");
    }
    
    private static Properties buildRouterProps(int id) {
        Properties props = getRouterProps(id);
        File f = new File(props.getProperty("router.configLocation"));
        if (!f.exists()) {
        	f.getParentFile().mkdirs();
            try {
				DataHelper.storeProps(props, f);
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
        return props;
    }
    
    private static Properties getRouterProps(int id) {
        Properties props = new Properties();

        props.setProperty("router.profileDir", "/peerProfiles");
        props.setProperty("router.sessionKeys.location", "/sessionKeys.dat");
        props.setProperty("router.info.location", "/router.info");
        props.setProperty("router.keys.location", "/router.keys");
        props.setProperty("router.networkDatabase.dbDir", "/netDb");
        props.setProperty("router.tunnelPoolFile", "/tunnelPool.dat");
        props.setProperty("router.keyBackupDir", "/keyBackup");
        props.setProperty("router.clientConfigFile", getBaseDir(id) + "/clients.config");
        props.setProperty("router.configLocation", getBaseDir(id) + "/router.config");
        props.setProperty("router.pingFile", getBaseDir(id) + "/router.ping");
        props.setProperty("router.rejectStartupTime", "0");
        props.setProperty("router.reseedDisable", "true");
        props.setProperty("i2p.dir.app", getBaseDir(id));
        
        /* If MultiRouter is not run from a dir containing lib/, webapps/, docs/, etc.
         * point i2p.dir.base to a directory containing the above. */
        //props.setProperty("i2p.dir.base", getBaseDir(id));
        props.setProperty("i2p.dir.config", getBaseDir(id));
        props.setProperty("i2p.dir.log", getBaseDir(id));
        props.setProperty("i2p.dir.router", getBaseDir(id));
        props.setProperty("i2p.dir.pid", getBaseDir(id));
        //props.setProperty("i2p.vmCommSystem", "true");
        props.setProperty("i2np.ntcp.hostname", "127.0.0.1");
        props.setProperty("i2np.udp.host", "127.0.0.1");
        props.setProperty("i2np.ntcp.port", BASE_PORT + id + "");
        props.setProperty("i2np.udp.port", BASE_PORT + id + "");
        props.setProperty("i2np.allowLocal", "true");
        props.setProperty("i2np.udp.internalPort", BASE_PORT + id + "");
        props.setProperty("i2cp.port", Integer.toString((BASE_PORT + nbrRouters + id)));   

        return props;
    }
    
    private static Properties buildClientProps(int id) {
    	Properties rProps = getRouterProps(id);
        Properties props = getClientProps();
        File f = new File(rProps.getProperty("router.clientConfigFile"));
        if (!f.exists()) {
        	f.getParentFile().mkdirs();
            try {
				DataHelper.storeProps(props, f);
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
        return props;
    }
    
    private static Properties getClientProps() {
    	Properties props = new Properties();
    	
    	props.setProperty("clientApp.0.args", (BASE_PORT-1) + " 127.0.0.1 ./webapps");
        props.setProperty("clientApp.0.main", "net.i2p.router.web.RouterConsoleRunner");
        props.setProperty("clientApp.0.name", "webconsole");
        props.setProperty("clientApp.0.onBoot", "true");
        props.setProperty("clientApp.1.args", "i2ptunnel.config");
        props.setProperty("clientApp.1.main", "net.i2p.i2ptunnel.TunnelControllerGroup");
        props.setProperty("clientApp.1.name", "tunnels");
        props.setProperty("clientApp.1.delay", "6");
        
        return props;
    }
    
    private static String getBaseDir(int id) {
    	File f = new File(".");
    	return f.getAbsoluteFile().getParentFile().toString() + "/multirouter/"+ Integer.toString(id);
    }
    
    private static void waitForCompletion() {
        while (true) {
            int alive = 0;
            for (int i = 0; i < _routers.size(); i++) {
                Router r = _routers.get(i);
                if (!r.isAlive()) {
                	_out.println("Router " + i + " is dead");
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
        _out.println("All routers shut down");
    }
    
    private static void usage() {
        System.err.println("Usage: MultiRouter nbr_routers");
        System.err.println("       Where nbr_routers > 0");
    }
}
