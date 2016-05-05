package net.i2p.desktopgui;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URL;

import net.i2p.I2PAppContext;
import net.i2p.desktopgui.i18n.DesktopguiTranslator;
import net.i2p.util.SystemVersion;

/**
 * Manages the tray icon life.
 */
abstract class TrayManager {

    protected final I2PAppContext _appContext;
    ///The tray area, or null if unsupported
    protected SystemTray tray;
    ///Our tray icon, or null if unsupported
    protected TrayIcon trayIcon;

    /**
     * Instantiate tray manager.
     */
    protected TrayManager(I2PAppContext ctx) {
        _appContext = ctx;
    }
    
    /**
     * Add the tray icon to the system tray and start everything up.
     */
    public synchronized void startManager()  throws AWTException {
        if(SystemTray.isSupported()) {
            // TODO figure out how to get menu to pop up on left-click
            // left-click does nothing by default
            // MouseListener, MouseEvent, ...
            tray = SystemTray.getSystemTray();
            // Windows typically has tooltips; Linux (at least Ubuntu) doesn't
            String tooltip = SystemVersion.isWindows() ? _t("I2P: Right-click for menu") : null;
            trayIcon = new TrayIcon(getTrayImage(), tooltip, getMainMenu());
            trayIcon.setImageAutoSize(true); //Resize image to fit the system tray
            tray.add(trayIcon);
            // 16x16 on Windows, 24x24 on Linux, but that will probably vary
            //System.out.println("Tray icon size is " + trayIcon.getSize());
        } else {
            throw new AWTException("SystemTray not supported");
        }
    }

    /**
     * Remove the tray icon from the system tray
     *
     * @since 0.9.26
     */
    public synchronized void stopManager() {
        if (tray != null && trayIcon != null) {
            tray.remove(trayIcon);
            tray = null;
            trayIcon = null;
        }
    }
    
    public synchronized void languageChanged() {
        if (trayIcon != null)
            trayIcon.setPopupMenu(getMainMenu());
    }
    
    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     */
    protected abstract PopupMenu getMainMenu();
    
    /**
     * Get tray icon image from the desktopgui resources in the jar file.
     * @return image used for the tray icon
     * @throws AWTException if image not found
     */
    private Image getTrayImage() throws AWTException {
        URL url = getClass().getResource("/desktopgui/resources/images/logo.png");
        if (url == null)
            throw new AWTException("cannot load tray image");
        Image image = Toolkit.getDefaultToolkit().getImage(url);
        return image;
    }
    
    protected String _t(String s) {
        return DesktopguiTranslator._t(_appContext, s);
    }
}
