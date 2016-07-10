/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * SysTray.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.awt.Frame;
import java.io.File;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import snoozesoft.systray4j.SysTrayMenu;
import snoozesoft.systray4j.SysTrayMenuEvent;
import snoozesoft.systray4j.SysTrayMenuIcon;
import snoozesoft.systray4j.SysTrayMenuItem;
import snoozesoft.systray4j.SysTrayMenuListener;

/**
 * A system tray control for launching the I2P router console.
 *
 * Not part of the public API. Works on 32-bit Windows only.
 * To be removed or replaced.
 * This source file may be safely removed in non-Windows builds.
 *
 * @author hypercubus
 * @since moved from SysTray in 0.9.26
 * @deprecated
 */
@Deprecated
class SysTrayImpl extends SysTray implements SysTrayMenuListener {

    private static BrowserChooser _browserChooser;
    private static String         _browserString;
    private static ConfigFile     _configFile     = new ConfigFile();
    private static Frame          _frame;
    private static SysTrayImpl    _instance;
    private static String         _portString;
    private static boolean        _showIcon;
    private static final boolean _is64 = SystemVersion.is64Bit();

    static {
        File config = new File(I2PAppContext.getGlobalContext().getConfigDir(), "systray.config");
        if (!_configFile.init(config.getAbsolutePath())) {
            _configFile.setProperty("browser", "default");
            _configFile.setProperty("port", "7657");
        }

        _browserString = _configFile.getProperty("browser", "default");
        _portString = _configFile.getProperty("port", "7657");
        _showIcon = Boolean.parseBoolean(_configFile.getProperty("visible", "true"));

        //if (!(new File("router.config")).exists())
        //    openRouterConsole("http://localhost:" + _portString + "/index.jsp");

        if ( (SystemVersion.isWindows()) && (!Boolean.getBoolean("systray.disable")) && (!_is64))
            _instance = new SysTrayImpl();
    }

    private SysTrayMenuItem _itemOpenConsole   = new SysTrayMenuItem("Open router console", "openconsole");
    private SysTrayMenuItem _itemSelectBrowser = new SysTrayMenuItem("Select browser...", "selectbrowser");
//    private SysTrayMenuItem _itemShutdown      = new SysTrayMenuItem("Shut down I2P router", "shutdown");
    private SysTrayMenuIcon _sysTrayMenuIcon   = new SysTrayMenuIcon("icons/iggy");
    private SysTrayMenu     _sysTrayMenu       = new SysTrayMenu(_sysTrayMenuIcon, "I2P Control");

    private SysTrayImpl() {
        _sysTrayMenuIcon.addSysTrayMenuListener(this);
        createSysTrayMenu();
        SimpleTimer2.getInstance().addPeriodicEvent(new RefreshDisplayEvent(), REFRESH_DISPLAY_FREQUENCY);
    }
    
    private static final long REFRESH_DISPLAY_FREQUENCY = 30*1000;
    private class RefreshDisplayEvent implements SimpleTimer.TimedEvent {
        public void timeReached() {
            refreshDisplay();
        }
    }

    /**
     *  Warning - systray4j.jar may not be present - may throw NoClassDefFoundError
     *
     *  @throws NoClassDefFoundError
     *  @since 0.8.1
     */
    public static synchronized SysTrayImpl getInstance() {
        return _instance;
    }

    private static void openRouterConsole(String url) {

        String browser = null;
        UrlLauncher urlLauncher = new UrlLauncher();

        if (_browserString == null || _browserString.equals("default")) {
            try {
                if (urlLauncher.openUrl(url))
                    return;
            } catch (IOException ex) {
                // Fall through.
            }
        } else {
            try {
                if (urlLauncher.openUrl(url, _browserString))
                    return;
            } catch (IOException ex) {
                // Fall through.
            }
        }

        if (!(browser = promptForBrowser("Please select another browser")).equals("nullnull"))
            setBrowser(browser);
    }

    private static String promptForBrowser(String windowTitle) {

        String browser = null;

        _frame = new Frame();
        _browserChooser = new BrowserChooser(_frame, windowTitle);
        browser = _browserChooser.getDirectory() + _browserChooser.getFile();
        _browserChooser = null;
        _frame = null;
        return browser;
    }

    private static void setBrowser(String browser) {
        _browserString = browser;
        _configFile.setProperty("browser", browser);
    }

    public void refreshDisplay() {
        if (_showIcon)
            _sysTrayMenu.showIcon();
        else
            _sysTrayMenu.hideIcon();
    }
    
    public void hide() {
        _configFile.setProperty("visible", "false");
        _showIcon = false;
        _sysTrayMenu.hideIcon();
    }

    public void iconLeftClicked(SysTrayMenuEvent e) {}

    public void iconLeftDoubleClicked(SysTrayMenuEvent e) {
        openRouterConsole("http://127.0.0.1:" + _portString + "/index.jsp");
    }

    public void menuItemSelected(SysTrayMenuEvent e) {

        String browser = null;

//        if (e.getActionCommand().equals("shutdown")) {
//            _browserChooser = null;
//            _frame = null;
//            _itemShutdown = null;
//            _itemSelectBrowser = null;
//            _sysTrayMenuIcon = null;
//            _sysTrayMenu = null;
//            _browserChooser = null;
//            _frame = null;
//            System.exit(0);
        if (e.getActionCommand().equals("selectbrowser")) {
            if (!(browser = promptForBrowser("Select browser")).equals("nullnull"))
                setBrowser(browser);
        } else if (e.getActionCommand().equals("openconsole")) {
            openRouterConsole("http://127.0.0.1:" + _portString + "/index.jsp");
        }
    }

    public void show() {
        _configFile.setProperty("visible", "true");
        _showIcon = true;
        _sysTrayMenu.showIcon();
    }

    private void createSysTrayMenu() {
//        _itemShutdown.addSysTrayMenuListener(this);
        _itemSelectBrowser.addSysTrayMenuListener(this);
        _itemOpenConsole.addSysTrayMenuListener(this);
//        _sysTrayMenu.addItem(_itemShutdown);
//        _sysTrayMenu.addSeparator();
        // hide it, as there have been reports of b0rked behavior on some JVMs.
        // specifically, that on XP & sun1.5.0.1, a user launching i2p w/out the
        // service wrapper would create netDb/, peerProfiles/, and other files
        // underneath each directory browsed to - as if the router's "." directory
        // is changing whenever the itemSelectBrowser's JFileChooser changed
        // directories.  This has not been reproduced or confirmed yet, but is
        // pretty scary, and this function isn't too necessary.
        //_sysTrayMenu.addItem(_itemSelectBrowser);
        _sysTrayMenu.addItem(_itemOpenConsole);
        refreshDisplay();
    }

    /**
     *  Starts SysTray, even on linux (but requires kde3 libsystray4j.so to do anything)
     *  @since 0.8.1
     */
    public static void main(String args[]) {
        System.err.println("SysTray4j version " + SysTrayMenu.VERSION);
        System.err.println("Hit ^C to exit");
        new SysTrayImpl();
        Thread t = Thread.currentThread();
        synchronized(t) {
            try {
                t.wait();
            } catch (InterruptedException ie) {}
        }
    }
}
