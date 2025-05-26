package org.klomp.snark.standalone;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.app.MenuCallback;
import net.i2p.app.MenuHandle;
import net.i2p.app.MenuService;
import net.i2p.apps.systray.UrlLauncher;
import net.i2p.data.DataHelper;
import net.i2p.desktopgui.ExternalMain;
import net.i2p.jetty.I2PLogger;
import net.i2p.jetty.JettyStart;
import net.i2p.util.I2PAppThread;
import net.i2p.util.SystemVersion;

import org.klomp.snark.SnarkManager;

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
    private static final String PROP_DTG_ENABLED = "desktopgui.enabled";

    private RunStandalone(String args[]) throws Exception {
        Properties p = new Properties();
        if (APP_CONFIG_FILE.exists()) {
            try {
                DataHelper.loadProps(p, APP_CONFIG_FILE);
            } catch (IOException ioe) {}
        }
        _context = new I2PAppContext(p);
        // Do this after we have a context
        // To take effect, must be set before any Jetty classes are loaded
        // https://slf4j.org/faq.html
        System.setProperty("slf4j.provider", "net.i2p.jetty.I2PLoggingServiceProvider");
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
            String url = "http://" + _host + ':' + _port + "/i2psnark/";
            System.out.println("Starting i2psnark " + SnarkManager.FULL_VERSION + " at " + url);
            MenuService dtg = startTrayApp();
            _jettyStart.startup();
            try {
               Thread.sleep(1000);
            } catch (InterruptedException ie) {}
            String p = _context.getProperty("routerconsole.browser");
            if (!("/bin/false".equals(p) || "NUL".equals(p))) {
                UrlLauncher launch = new UrlLauncher(_context, null, new String[] { url } );
                launch.startup();
            }
            if (dtg != null)
                dtg.addMenu("Shutdown I2PSnark", new StandaloneStopper(dtg));
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

    /**
     *  @since 0.9.54 adapted from RouterConsoleRunner
     */
    private static boolean isSystrayEnabled(I2PAppContext context) {
        if (GraphicsEnvironment.isHeadless())
            return false;
        // default false except on OSX and Windows,
        // and on Linux KDE and LXDE.
        // Xubuntu XFCE works but doesn't look very good
        // Ubuntu Unity was far too buggy to enable
        // Ubuntu GNOME does not work, SystemTray.isSupported() returns false
        String xdg = System.getenv("XDG_CURRENT_DESKTOP");
        boolean dflt = SystemVersion.isWindows() ||
                       SystemVersion.isMac() ||
                       //"XFCE".equals(xdg) ||
                       "KDE".equals(xdg) ||
                       "LXDE".equals(xdg);
        return context.getProperty(PROP_DTG_ENABLED, dflt);
    }

    /**
     *  @since 0.9.54 adapted from RouterConsoleRunner
     *  @return null on failure
     */
    private MenuService startTrayApp() {
        try {
            if (isSystrayEnabled(_context)) {
                System.setProperty("java.awt.headless", "false");
                ExternalMain dtg = new ExternalMain(_context, _context.clientAppManager(), null);
                dtg.startup();
                return dtg;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    /**
     *  Callback when shutdown is clicked in systray
     *  @since 0.9.61
     */
    public static class StandaloneStopper implements MenuCallback {
        private final MenuService _ms;

        public StandaloneStopper(MenuService ms) { _ms = ms; }

        public void clicked(MenuHandle menu) {
            _ms.disableMenu(menu);
            _ms.updateMenu("I2PSnark shutting down", menu);
            Thread t = new I2PAppThread(new StopperThread(), "Snark Stopper", true);
            t.start();
        }
    }

    /**
     *  Threaded shutdown
     *  @since 0.9.61
     */
    public static class StopperThread implements Runnable {
        public void run() {
            shutdown();
        }
    }

}
