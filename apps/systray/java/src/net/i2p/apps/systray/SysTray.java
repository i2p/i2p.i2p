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

import snoozesoft.systray4j.SysTrayMenu;
import snoozesoft.systray4j.SysTrayMenuEvent;
import snoozesoft.systray4j.SysTrayMenuIcon;
import snoozesoft.systray4j.SysTrayMenuItem;
import snoozesoft.systray4j.SysTrayMenuListener;

/**
 * A system tray control for launching the I2P router console.
 *
 * @author hypercubus
 */
public class SysTray implements SysTrayMenuListener {

    private BrowserChooser  _browserChooser;
    private String          _browserString;
    private ConfigFile      _configFile        = new ConfigFile();
    private Frame           _frame;
    private SysTrayMenuItem _itemOpenConsole   = new SysTrayMenuItem("Open router console", "openconsole");
    private SysTrayMenuItem _itemSelectBrowser = new SysTrayMenuItem("Select browser...", "selectbrowser");
    private SysTrayMenuItem _itemShutdown      = new SysTrayMenuItem("Shut down I2P router", "shutdown");
    private SysTrayMenuIcon _sysTrayMenuIcon   = new SysTrayMenuIcon("icons/iggy");
    private SysTrayMenu     _sysTrayMenu       = new SysTrayMenu(_sysTrayMenuIcon, "I2P Control");
    private UrlLauncher     _urlLauncher       = new UrlLauncher();

    public SysTray() {

        if (!_configFile.init("systray.config"))
            _configFile.setProperty("browser", "default");

        _browserString = _configFile.getProperty("browser", "default");

        _sysTrayMenuIcon.addSysTrayMenuListener(this);
        createSysTrayMenu();
    }

    public static void main(String[] args) {

        if (System.getProperty("os.name").startsWith("Windows"))
            new SysTray();
    }

    public void iconLeftClicked(SysTrayMenuEvent e) {}

    public void iconLeftDoubleClicked(SysTrayMenuEvent e) {
        openRouterConsole();
    }

    public void menuItemSelected(SysTrayMenuEvent e) {

        String browser = null;

        if (e.getActionCommand().equals("shutdown")) {
            _browserChooser = null;
            _frame = null;
            _itemShutdown = null;
            _itemSelectBrowser = null;
            _sysTrayMenuIcon = null;
            _sysTrayMenu = null;
            _browserChooser = null;
            _frame = null;
            System.exit(0);
        } else if (e.getActionCommand().equals("selectbrowser")) {

            if (!(browser = promptForBrowser("Select browser")).equals("nullnull"))
                setBrowser(browser);

        } else if (e.getActionCommand().equals("openconsole")) {
            openRouterConsole();
        }
    }

    private void createSysTrayMenu() {
        _itemShutdown.addSysTrayMenuListener(this);
        _itemSelectBrowser.addSysTrayMenuListener(this);
        _itemOpenConsole.addSysTrayMenuListener(this);
        _sysTrayMenu.addItem(_itemShutdown);
        _sysTrayMenu.addSeparator();
        _sysTrayMenu.addItem(_itemSelectBrowser);
        _sysTrayMenu.addItem(_itemOpenConsole);
    }

    private void openRouterConsole() {

        String browser = null;

        if (_browserString == null || _browserString.equals("default")) {
            try {

                if (_urlLauncher.openUrl("http://localhost:7657/"))
                    return;

            } catch (Exception ex) {
                // Fall through.
            }
        } else {
            try {

                if (_urlLauncher.openUrl("http://localhost:7657/", _browserString))
                    return;

            } catch (Exception ex) {
                // Fall through.
            }
        }

        if (!(browser = promptForBrowser("Please select another browser")).equals("nullnull"))
            setBrowser(browser);
    }

    private String promptForBrowser(String windowTitle) {

        String browser = null;

        _frame = new Frame();
        _browserChooser = new BrowserChooser(_frame, windowTitle);
        browser = _browserChooser.getDirectory() + _browserChooser.getFile();
        _browserChooser = null;
        _frame = null;
        return browser;
    }

    private void setBrowser(String browser) {
        _browserString = browser;
        _configFile.setProperty("browser", browser);
    }
}