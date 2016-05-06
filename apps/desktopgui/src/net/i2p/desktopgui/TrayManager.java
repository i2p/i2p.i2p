package net.i2p.desktopgui;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.net.URL;

import javax.swing.JFrame;
import javax.swing.JPopupMenu;
import javax.swing.event.MenuKeyEvent;
import javax.swing.event.MenuKeyListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import net.i2p.I2PAppContext;
import net.i2p.desktopgui.i18n.DesktopguiTranslator;
import net.i2p.util.SystemVersion;

/**
 * Manages the tray icon life.
 */
abstract class TrayManager {

    protected final I2PAppContext _appContext;
    protected final Main _main;
    protected final boolean _useSwing;
    ///The tray area, or null if unsupported
    protected SystemTray tray;
    ///Our tray icon, or null if unsupported
    protected TrayIcon trayIcon;

    /**
     * Instantiate tray manager.
     */
    protected TrayManager(I2PAppContext ctx, Main main, boolean useSwing) {
        _appContext = ctx;
        _main = main;
        _useSwing = useSwing;
    }
    
    /**
     * Add the tray icon to the system tray and start everything up.
     */
    public synchronized void startManager() throws AWTException {
        if (!SystemTray.isSupported())
            throw new AWTException("SystemTray not supported");
        tray = SystemTray.getSystemTray();
        // Windows typically has tooltips; Linux (at least Ubuntu) doesn't
        String tooltip = SystemVersion.isWindows() ? _t("I2P: Right-click for menu") : null;
        TrayIcon ti;
        if (_useSwing)
            ti = getSwingTrayIcon(tooltip);
        else
            ti = getAWTTrayIcon(tooltip);
        ti.setImageAutoSize(true); //Resize image to fit the system tray
        tray.add(ti);
        trayIcon = ti;
    }

    private TrayIcon getAWTTrayIcon(String tooltip) throws AWTException {
        PopupMenu menu = getMainMenu();
        if (!SystemVersion.isWindows())
            menu.setFont(new Font("Arial", Font.BOLD, 14));
        TrayIcon ti = new TrayIcon(getTrayImage(), tooltip, menu);
        ti.addMouseListener(new MouseListener() {
            public void mouseClicked(MouseEvent m)  {}
            public void mouseEntered(MouseEvent m)  {}
            public void mouseExited(MouseEvent m)   {}
            public void mousePressed(MouseEvent m)  { updateMenu(); }
            public void mouseReleased(MouseEvent m) { updateMenu(); }
        });
        return ti;
    }

    private TrayIcon getSwingTrayIcon(String tooltip) throws AWTException {
        // A JPopupMenu by itself is hard to get rid of,
        // so we hang it off a zero-size, undecorated JFrame.
        // http://stackoverflow.com/questions/1498789/jpopupmenu-behavior
        // http://stackoverflow.com/questions/2581314/how-do-you-hide-a-swing-popup-when-you-click-somewhere-else
        final JFrame frame = new JFrame();
        // http://stackoverflow.com/questions/2011601/jframe-without-frame-border-maximum-button-minimum-button-and-frame-icon
        frame.setUndecorated(true);
        frame.setMinimumSize(new Dimension(0, 0));
        frame.setSize(0, 0);
        final JPopupMenu menu = getSwingMainMenu();
        menu.setFocusable(true);
        frame.add(menu);
        TrayIcon ti = new TrayIcon(getTrayImage(), tooltip, null);
        ti.addMouseListener(new MouseListener() {
            public void mouseClicked(MouseEvent e)  {}
            public void mouseEntered(MouseEvent e)  {}
            public void mouseExited(MouseEvent e)   {}
            public void mousePressed(MouseEvent e)  { handle(e); }
            public void mouseReleased(MouseEvent e) { handle(e); }
            private void handle(MouseEvent e) {
                // http://stackoverflow.com/questions/17258250/changing-the-laf-of-a-popupmenu-for-a-trayicon-in-java
                // menu visible check is failsafe, for when menu gets cancelled
                if (!frame.isVisible() || !menu.isVisible()) {
                    frame.setLocation(e.getX(), e.getY());
                    frame.setVisible(true);
                    menu.show(frame, 0, 0);
                }
                updateMenu();
            }
        });
        menu.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuCanceled(PopupMenuEvent e)            { frame.setVisible(false); }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            public void popupMenuWillBecomeVisible(PopupMenuEvent e)   {}
        });
        // this is to make it go away when we click elsewhere
        // doesn't do anything
        menu.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {}
            public void focusLost(FocusEvent e)   { frame.setVisible(false); }
        });
        // this is to make it go away when we hit escape
        // doesn't do anything
        menu.addMenuKeyListener(new MenuKeyListener() {
            public void menuKeyPressed(MenuKeyEvent e)  {}
            public void menuKeyReleased(MenuKeyEvent e) {}
            public void menuKeyTyped(MenuKeyEvent e)    {
                if (e.getKeyChar() == (char) 0x1b)
                    frame.setVisible(false);
            }
        });
        return ti;
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
        if (trayIcon != null) {
            if (!_useSwing)
                trayIcon.setPopupMenu(getMainMenu());
            // else TODO
        }
    }
    
    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     */
    protected abstract PopupMenu getMainMenu();
    
    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     * @since 0.9.26
     */
    protected abstract JPopupMenu getSwingMainMenu();
    
    /**
     * Update the menu
     * @since 0.9.26
     */
    protected abstract void updateMenu();
    
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
