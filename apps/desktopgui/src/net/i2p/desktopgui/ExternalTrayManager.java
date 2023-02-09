package net.i2p.desktopgui;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;

import net.i2p.I2PAppContext;
import net.i2p.desktopgui.router.RouterManager;

/**
 *  When started before the router, e.g. with
 *  java -cp i2p.jar:router.jar:desktopgui.jar net.i2p.desktopgui.Main
 *
 *  No access to context, very limited abilities.
 *  Not fully supported.
 */
class ExternalTrayManager extends TrayManager {
	
    public ExternalTrayManager(I2PAppContext ctx, boolean useSwing) {
        super(ctx, useSwing);
    }

    public PopupMenu getMainMenu() {
        PopupMenu popup = new PopupMenu();
        MenuItem startItem = new MenuItem(_t("Start I2P"));
        startItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.start();
                        return null;
                    }
                    
                    @Override
                    protected void done() {
                        trayIcon.displayMessage(_t("Starting"), _t("I2P is starting!"), TrayIcon.MessageType.INFO);
                        //Hide the tray icon.
                        //We cannot stop the desktopgui program entirely,
                        //since that risks killing the I2P process as well.
                        tray.remove(trayIcon);
                    }
                }.execute();
            }
        });
        popup.add(startItem);
        initializeNotificationItems();
        popup.add(_notificationItem2);
        popup.add(_notificationItem1);
        return popup;
    }

    public JPopupMenu getSwingMainMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem startItem = new JMenuItem(_t("Start I2P"));
        startItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.start();
                        return null;
                    }
                    
                    @Override
                    protected void done() {
                        trayIcon.displayMessage(_t("Starting"), _t("I2P is starting!"), TrayIcon.MessageType.INFO);
                        //Hide the tray icon.
                        //We cannot stop the desktopgui program entirely,
                        //since that risks killing the I2P process as well.
                        tray.remove(trayIcon);
                    }
                }.execute();
            }
        });
        popup.add(startItem);
        initializeJNotificationItems();
        popup.add(_jnotificationItem2);
        popup.add(_jnotificationItem1);
        return popup;
    }

    /**
     * Update the menu
     * @since 0.9.26
     */
    protected void updateMenu() {
        if (_notificationItem1 != null)
            _notificationItem1.setEnabled(_showNotifications);
        if (_notificationItem2 != null)
            _notificationItem2.setEnabled(!_showNotifications);
        if (_jnotificationItem1 != null)
            _jnotificationItem1.setVisible(_showNotifications);
        if (_jnotificationItem2 != null)
            _jnotificationItem2.setVisible(!_showNotifications);
    }
}
