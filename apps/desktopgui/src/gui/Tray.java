/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gui;

import desktopgui.*;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.Menu;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;
import router.RouterHandler;

/**
 *
 * @author mathias
 */
public class Tray {

    public Tray() {
        tray = SystemTray.getSystemTray();
        loadSystemTray();
    }
    
    private void loadSystemTray() {

        Image image = Toolkit.getDefaultToolkit().getImage("desktopgui/resources/logo/logo.jpg");

        PopupMenu popup = new PopupMenu();

        //Create menu items to put in the popup menu
        MenuItem browserLauncher = new MenuItem("Launch browser");
        browserLauncher.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent arg0) {
                if(Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    try {
                        desktop.browse(new URI("http://localhost:7657"));
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    } catch(IOException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }

        });
        MenuItem howto = new MenuItem("How to use I2P");
        howto.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent arg0) {
                if(Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    try {
                        File f = new File("desktopgui/resources/howto/howto.html");
                        desktop.browse(new URI("file://" + f.getAbsolutePath()));
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    } catch(IOException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
            
        });
        Menu config = new Menu("Configuration");
        MenuItem speedConfig = new MenuItem("Speed");
        speedConfig.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent arg0) {
                (new SpeedSelector()).setVisible(true);
            }
            
        });
        MenuItem advancedConfig = new MenuItem("Advanced Configuration");
        advancedConfig.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent arg0) {
                if(Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    try {
                        desktop.browse(new URI("http://localhost:7657/config.jsp"));
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    } catch(IOException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }

        });
        MenuItem shutdown = new MenuItem("Shutdown I2P");
        shutdown.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent arg0) {
                RouterHandler.setStatus(RouterHandler.SHUTDOWN_GRACEFULLY);
            }

        });
        
        //Add menu items to popup menu
        popup.add(browserLauncher);
        popup.add(howto);
        
        config.add(speedConfig);
        config.add(advancedConfig);
        popup.add(config);
        
        popup.add(shutdown);

        //Add tray icon
        trayIcon = new TrayIcon(image, "I2P: the anonymous network", popup);
        try {
            tray.add(trayIcon);
        } catch (AWTException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private SystemTray tray = null;
    private TrayIcon trayIcon = null;
    
}
