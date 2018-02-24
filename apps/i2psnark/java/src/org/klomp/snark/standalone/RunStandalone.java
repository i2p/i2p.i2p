package org.klomp.snark.standalone;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.apps.systray.UrlLauncher;
import net.i2p.data.DataHelper;
import net.i2p.jetty.JettyStart;

/**
 *  @since moved from ../web and fixed in 0.9.27
 */
public class RunStandalone {
    
    private final JettyStart _jettyStart;
    private final I2PAppContext _context;
    private int _port = 8002;
    private String _host = "127.0.0.1";
    private static RunStandalone _instance;
    static final File APP_CONFIG_FILE = new File("i2psnark-appctx.config");

    private RunStandalone(String args[]) throws Exception {
        Properties p = new Properties();
        if (APP_CONFIG_FILE.exists()) {
            try {
                DataHelper.loadProps(p, APP_CONFIG_FILE);
            } catch (IOException ioe) {}
        }
        _context = new I2PAppContext(p);
        File base = _context.getBaseDir();
        File xml = new File(base, "jetty-i2psnark.xml");
        _jettyStart = new JettyStart(_context, null, new String[] { xml.getAbsolutePath() } );
        if (args.length > 1) {
            _port = Integer.parseInt(args[1]);
        } 
        if (args.length > 0) {
            _host = args[0];
        }
    }
    
    /**
     *  Usage: RunStandalone [host [port]] (but must match what's in the jetty-i2psnark.xml file)
     */
    public synchronized static void main(String args[]) {
        try {
            RunStandalone runner = new RunStandalone(args);
            runner.start();
            _instance = runner;
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public void start() {
        try {
            _jettyStart.startup();
            String url = "http://" + _host + ':' + _port + "/i2psnark/";
            try {
               Thread.sleep(1000);
            } catch (InterruptedException ie) {}
            UrlLauncher launch = new UrlLauncher(_context, null, new String[] { url } );
            launch.startup();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void stop() {
        _jettyStart.shutdown(null);
    }
    
    /** @since 0.9.27 */
    public synchronized static void shutdown() {
        if (_instance != null)
            _instance.stop();
        // JettyStart.shutdown() is threaded
        try {
           Thread.sleep(3000);
        } catch (InterruptedException ie) {}
        System.exit(1);
    }
}
