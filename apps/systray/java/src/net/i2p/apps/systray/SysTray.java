/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * SysTray.java
 * 2004 The I2P Project
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
    private SysTrayMenuItem _itemExit          = new SysTrayMenuItem("Exit I2P systray", "exit");
    private SysTrayMenuItem _itemSelectBrowser = new SysTrayMenuItem("Select preferred browser...", "selectbrowser");
    private SysTrayMenuIcon _sysTrayMenuIcon   = new SysTrayMenuIcon("../icons/iggy");
    private SysTrayMenu     _sysTrayMenu       = new SysTrayMenu(_sysTrayMenuIcon, "I2P Router Console");
    private UrlLauncher     _urlLauncher       = new UrlLauncher();

    public SysTray() {

        if (!_configFile.init("../systray.config"))
            _configFile.setProperty("browser", "default");

        _browserString = _configFile.getProperty("browser", "default");

        _sysTrayMenuIcon.addSysTrayMenuListener(this);
        createSysTrayMenu();
    }

    public static void main(String[] args) {
        new SysTray();
    }

    public void iconLeftClicked(SysTrayMenuEvent e) {}

    public void iconLeftDoubleClicked(SysTrayMenuEvent e) {
        if (_browserString == null || _browserString.equals("default")) {
            try {

                if (_urlLauncher.openUrl("http://localhost:7657"))
                    return;

            } catch (Exception ex) {
                // Fall through.
            }
        } else {
            try {

                if (_urlLauncher.openUrl("http://localhost:7657", _browserString))
                    return;

            } catch (Exception ex) {
                // Fall through.
            }
        }
        setBrowser(promptForBrowser("Please select another browser"));
    }

    public void menuItemSelected(SysTrayMenuEvent e) {
        if (e.getActionCommand().equals("exit")) {
            _browserChooser = null;
            _frame = null;
            _itemExit = null;
            _itemSelectBrowser = null;
            _sysTrayMenuIcon = null;
            _sysTrayMenu = null;
            _browserChooser = null;
            _frame = null;
            System.exit(0);
        } else if (e.getActionCommand().equals("selectbrowser")) {
            setBrowser(promptForBrowser("Select preferred browser"));
        }
    }

    private void createSysTrayMenu() {
        _itemSelectBrowser.addSysTrayMenuListener(this);
        _itemExit.addSysTrayMenuListener(this);
        _sysTrayMenu.addItem(_itemExit);
        _sysTrayMenu.addSeparator();
        _sysTrayMenu.addItem(_itemSelectBrowser);
    }

    private String promptForBrowser(String windowTitle) {
        _frame = new Frame();
        _browserChooser = new BrowserChooser(_frame, windowTitle);
        return _browserChooser.getDirectory() + _browserChooser.getFile();
    }

    private void setBrowser(String browser) {
        _browserString = browser;
        _configFile.setProperty("browser", browser);
    }
}