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
 */
public class SysTray implements SysTrayMenuListener {

    private SysTrayMenuItem itemExit                = new SysTrayMenuItem("Exit", "exit");
    private SysTrayMenuItem itemStartRouter         = new SysTrayMenuItem("Start router", "start");
    private SysTrayMenuItem itemStopRouter          = new SysTrayMenuItem("Stop router", "stop");
    private SysTrayMenuIcon sysTrayMenuIconDisabled = new SysTrayMenuIcon("icons/iggy_grey");
    private SysTrayMenuIcon sysTrayMenuIconEnabled  = new SysTrayMenuIcon("icons/iggy");
    private SysTrayMenu     sysTrayMenu             = new SysTrayMenu(sysTrayMenuIconEnabled, "I2P Console");

    public SysTray() {
        sysTrayMenuIconDisabled.addSysTrayMenuListener(this);
        sysTrayMenuIconEnabled.addSysTrayMenuListener(this);
        createSysTrayMenu();
    }

    public static void main(String[] args) {
        new SysTray();
        while(true)
            try {
            Thread.sleep(2*1000);
            } catch (InterruptedException e) {
                // blah
            }
    }

    public void iconLeftClicked(SysTrayMenuEvent e) {}

    public void iconLeftDoubleClicked(SysTrayMenuEvent e) {
        System.out.println("Double click!");
    }

    public void menuItemSelected(SysTrayMenuEvent e) {
        if (e.getActionCommand().equals("exit")) {
            // exit systray
            System.exit(0);
        } else if (e.getActionCommand().equals("stop")) {
            itemStartRouter.setEnabled(true);
            itemStopRouter.setEnabled(false);
            // stop router
            sysTrayMenu.setIcon(sysTrayMenuIconDisabled);
        } else if (e.getActionCommand().equals("start")) {
            itemStopRouter.setEnabled(true);
            itemStartRouter.setEnabled(false);
            // start router
            sysTrayMenu.setIcon(sysTrayMenuIconEnabled);
        }
    }

    private void createSysTrayMenu() {
        itemStartRouter.addSysTrayMenuListener(this);
        itemStartRouter.setEnabled(false);
        itemStopRouter.addSysTrayMenuListener(this);
        itemExit.addSysTrayMenuListener(this);
        sysTrayMenu.addItem(itemExit);
        sysTrayMenu.addSeparator();
        sysTrayMenu.addItem(itemStopRouter);
        sysTrayMenu.addItem(itemStartRouter);
    }
}