/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * SysTray.java
 * 2004 The I2P Project
 * This code is public domain.
 */

package net.i2p.apps.systray;

import snoozesoft.systray4j.SysTrayMenu;
import snoozesoft.systray4j.SysTrayMenuEvent;
import snoozesoft.systray4j.SysTrayMenuIcon;
import snoozesoft.systray4j.SysTrayMenuItem;
import snoozesoft.systray4j.SysTrayMenuListener;

/**
 * A system tray control for launching the I2P router console.
 *
 * @author hypercubus
 * 
 * TODO Add a menu entry and dialog to let the user specify the location of their preferred web browser.
 */
public class SysTray implements SysTrayMenuListener {

    private SysTrayMenuItem itemExit        = new SysTrayMenuItem("Exit systray", "exit");
    private SysTrayMenuItem itemSetBrowser  = new SysTrayMenuItem("Set preferred browser...", "setbrowser");
    private SysTrayMenuIcon sysTrayMenuIcon = new SysTrayMenuIcon("../icons/iggy");
    private SysTrayMenu     sysTrayMenu     = new SysTrayMenu(sysTrayMenuIcon, "I2P Console");

    public SysTray() {
        sysTrayMenuIcon.addSysTrayMenuListener(this);
        createSysTrayMenu();
    }

    public static void main(String[] args) {
        new SysTray();
        while(true)
            try {
            Thread.sleep(2 * 1000);
            } catch (InterruptedException e) {
                // blah
            }
    }

    public void iconLeftClicked(SysTrayMenuEvent e) {}

    public void iconLeftDoubleClicked(SysTrayMenuEvent e) {
        try {
            new UrlLauncher().openUrl("http://localhost:7657");
        } catch (Exception ex) {
            // Pop up a dialog or something.
        }
    }

    public void menuItemSelected(SysTrayMenuEvent e) {
        if (e.getActionCommand().equals("exit")) {
            // exit systray
            System.exit(0);
        } else if (e.getActionCommand().equals("start")) {
            // Popup browser dialog
        }
    }

    private void createSysTrayMenu() {
        itemSetBrowser.addSysTrayMenuListener(this);
        itemExit.addSysTrayMenuListener(this);
        sysTrayMenu.addItem(itemExit);
        sysTrayMenu.addSeparator();
        sysTrayMenu.addItem(itemSetBrowser);
    }
}