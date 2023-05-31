package net.i2p.desktopgui;

import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;

import javax.swing.SwingUtilities;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;
import net.i2p.app.MenuCallback;
import net.i2p.app.MenuHandle;
import net.i2p.app.MenuService;
import net.i2p.app.NotificationService;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * A simplified Main that does not require router.jar, for App Context only.
 * Invokes ExternalTrayManager only.
 * No state tracking, ClientAppManager doesn't care.
 *
 * @since 0.9.54
 */
public class ExternalMain implements ClientApp, NotificationService, MenuService {

    private final I2PAppContext _appContext;
    private final ClientAppManager _mgr;
    private final Log log;
    private TrayManager _trayManager;

    private static final String PROP_SWING = "desktopgui.swing";

    public ExternalMain(I2PAppContext ctx, ClientAppManager mgr, String args[]) {
        _appContext = ctx;
        _mgr = mgr;
        log = _appContext.logManager().getLog(ExternalMain.class);
    }

    public ExternalMain() {
        _appContext = I2PAppContext.getGlobalContext();
        _mgr = _appContext.clientAppManager();
        log = _appContext.logManager().getLog(ExternalMain.class);
    }
    
    public static void main(String[] args) {
        // early check so we can bail out when started via CLI
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray not supported");
            return;
        }
        ExternalMain main = new ExternalMain();
        main.beginStartup(args);
    }
    
    /**
     * Start the tray icon code (loads tray icon in the tray area).
     * @throws AWTException on startup error, including systray not supported 
     */
    private synchronized void startUp() throws Exception {
        boolean useSwingDefault = !(SystemVersion.isWindows() || SystemVersion.isMac());
        boolean useSwing = _appContext.getProperty(PROP_SWING, useSwingDefault);
        _trayManager = new ExternalTrayManager(_appContext, useSwing);
        _trayManager.startManager();
        if (_mgr != null)
            _mgr.register(this);
    }

    /**
     * Main method launching the application.
     *
     * @param args unused
     */
    private void beginStartup(String[] args) {
        String headless = System.getProperty("java.awt.headless");
        boolean isHeadless = Boolean.parseBoolean(headless);
        if (isHeadless) {
        	log.warn("Headless environment: not starting desktopgui!");
            return;
        }
        if (SystemVersion.isMac())
            setMacTrayIcon();
        launchForeverLoop();

        // We'll be doing GUI work, so let's stay in the event dispatcher thread.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    startUp();
                } catch(Exception e) {
                    log.error("Failed while running desktopgui!", e);
                }
                
            }
            
        });
    }
    
    /**
     *  Unless we do this, when we start DesktopGUI we get a Java coffee cup
     *  in the tray.
     *
     *  Based on code from https://gist.github.com/bchapuis/1562406 , no apparent license.
     *  See also https://stackoverflow.com/questions/6006173/how-do-you-change-the-dock-icon-of-a-java-program
     *
     *  TODO, if we wanted to add our own menu, see
     *  https://stackoverflow.com/questions/1319805/java-os-x-dock-menu
     *
     *  TODO, if we want to make it bounce, see
     *  https://stackoverflow.com/questions/15079783/how-to-make-my-app-icon-bounce-in-the-mac-dock
     *
     *  TODO, if we want to handle Quit, see
     *  https://nakkaya.com/2009/04/19/java-osx-integration/
     *
     *  @since 0.9.33
     */
    @SuppressWarnings("unchecked")
    private void setMacTrayIcon() {
        File f = new File(_appContext.getBaseDir(), "docs/themes/console/images/itoopie_sm.png");
        if (!f.exists())
            return;
        try {
            Class util = Class.forName("com.apple.eawt.Application");
            Method getApplication = util.getMethod("getApplication", new Class[0]);
            Object application = getApplication.invoke(util);
            Class params[] = new Class[1];
            params[0] = Image.class;
            Method setDockIconImage = util.getMethod("setDockIconImage", params);
            URL url = f.toURI().toURL();
            Image image = Toolkit.getDefaultToolkit().getImage(url);
            setDockIconImage.invoke(application, image);
        } catch (Exception e) {
            if (log.shouldWarn())
                log.warn("Can't set OSX Dock icon", e);
        }
    }
    
    /**
     * Avoids the app terminating because no Window is opened anymore.
     * More info: http://java.sun.com/javase/6/docs/api/java/awt/doc-files/AWTThreadIssues.html#Autoshutdown
     */
    private static void launchForeverLoop() {
       Runnable r = new Runnable() {
            public void run() {
                try {
                    Object o = new Object();
                    synchronized (o) {
                        o.wait();
                    }
                } catch (InterruptedException ie) {
                }
            }
        };
        Thread t = new Thread(r, "DesktopGUI spinner");
        t.setDaemon(false);
        t.start();
    }

    /////// NotificationService methods

    /**
     *  Send a notification to the user.
     *
     *  @param source unsupported
     *  @param category unsupported
     *  @param priority unsupported
     *  @param title for the popup, translated
     *  @param message translated
     *  @param path unsupported
     *  @return 0, or -1 on failure
     */
    public int notify(String source, String category, int priority, String title, String message, String path) {
        TrayManager tm = _trayManager;
        if (tm == null)
            return -1;
        return tm.displayMessage(priority, title, message, path);
    }

    /**
     *  Cancel a notification if possible.
     *  Unsupported.
     *
     *  @return false always
     */
    public boolean cancel(int id) {
        return false;
    }

    /**
     *  Update the text of a notification if possible.
     *  Unsupported.
     *
     *  @return false always
     */
    public boolean update(int id, String title, String message, String path) {
        return false;
    }

    /////// MenuService methods

    /**
     *  Menu will start out shown and enabled, in the root menu
     *
     *  @param message for the menu, translated
     *  @param callback fired on click
     *  @return null on error
     *  @since 0.9.59
     */
    public MenuHandle addMenu(String message, MenuCallback callback) {
        return addMenu(message, callback, null);
    }

    /**
     *  Menu will start out enabled, as a submenu
     *
     *  @param message for the menu, translated
     *  @param callback fired on click
     *  @param parent the parent menu this will be a submenu of, or null for top level
     *  @return null on error
     *  @since 0.9.59
     */
    public MenuHandle addMenu(String message, MenuCallback callback, MenuHandle parent) {
        if (_trayManager == null)
            return null;
        return _trayManager.addMenu(message, callback, parent);
    }

    /**
     *  @since 0.9.59
     */
    public void removeMenu(MenuHandle item) {
        if (_trayManager == null)
            return;
        _trayManager.removeMenu(item);
    }

    /**
     *  @since 0.9.59
     */
    public void showMenu(MenuHandle item) {
        if (_trayManager == null)
            return;
        _trayManager.showMenu(item);
    }

    /**
     *  @since 0.9.59
     */
    public void hideMenu(MenuHandle item) {
        if (_trayManager == null)
            return;
        _trayManager.hideMenu(item);
    }

    /**
     *  @since 0.9.59
     */
    public void enableMenu(MenuHandle item) {
        if (_trayManager == null)
            return;
        _trayManager.enableMenu(item);
    }

    /**
     *  @since 0.9.59
     */
    public void disableMenu(MenuHandle item) {
        if (_trayManager == null)
            return;
        _trayManager.disableMenu(item);
    }

    /**
     *  @since 0.9.59
     */
    public void updateMenu(String message, MenuHandle item) {
        if (_trayManager == null)
            return;
        _trayManager.updateMenu(message, item);
    }

    /////// ClientApp methods

    public synchronized void startup() {
        beginStartup(null);
    }

    public synchronized void shutdown(String[] args) {
        if (_trayManager != null)
            _trayManager.stopManager();
    }

    public ClientAppState getState() {
        return ClientAppState.INITIALIZED;
    }

    public String getName() {
        return "desktopgui";
    }

    public String getDisplayName() {
        return "Desktop GUI";
    }

    /////// end ClientApp methods
}
