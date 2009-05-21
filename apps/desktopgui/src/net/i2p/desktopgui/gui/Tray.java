/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.desktopgui.gui;

import net.i2p.desktopgui.desktopgui.*;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
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
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import net.i2p.desktopgui.router.RouterHandler;
import net.i2p.desktopgui.router.RouterHelper;
import net.i2p.desktopgui.router.configuration.PeerHelper;

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

        final JPopupMenu popup = new JPopupMenu();

        //Create menu items to put in the popup menu
        JMenuItem browserLauncher = new JMenuItem("Launch browser");
        browserLauncher.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                if(Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    try {
                        if(desktop.isSupported(Desktop.Action.BROWSE)) {
                            desktop.browse(new URI("http://localhost:7657"));
                        }
                        else {
                            trayIcon.displayMessage("Browser not found", "The default browser for your system was not found.", TrayIcon.MessageType.WARNING);
                        }
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    } catch(IOException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }

        });
        JMenuItem howto = new JMenuItem("How to use I2P");
        howto.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                if(Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    try {
                        File f = new File("desktopgui/resources/howto/howto.html");
                        System.out.println(new URI(null, null, null, 0, "file://" + f.getAbsolutePath(), null, null));
                        desktop.browse(new URI(null, null, null, 0, "file://" + f.getAbsolutePath(), null, null));
                        //desktop.browse(new URI("file://" + f.getAbsolutePath()));
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    } catch(IOException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
            
        });
        JMenu config = new JMenu("Configuration");
        JMenuItem speedConfig = new JMenuItem("Speed");
        speedConfig.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                (new SpeedSelector()).setVisible(true);
            }
            
        });
        JMenuItem generalConfig = new JMenuItem("General Configuration");
        generalConfig.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new GeneralConfiguration();
            }
            
        });
        JMenuItem advancedConfig = new JMenuItem("Advanced Configuration");
        advancedConfig.addActionListener(new ActionListener() {

            @Override
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
        JMenuItem viewLog = new JMenuItem("View log");
        viewLog.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new LogViewer();
            }
            
        });
        JMenuItem version = new JMenuItem("Version");
        version.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new Version();
            }
            
        });
        JMenuItem shutdown = new JMenuItem("Shutdown I2P");
        shutdown.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                RouterHandler.setStatus(RouterHandler.SHUTDOWN_GRACEFULLY);
                long shutdownTime = RouterHelper.getGracefulShutdownTimeRemaining();
                System.out.println("Shutdowntime remaining: " + shutdownTime);
                if(shutdownTime>0) {
                    trayIcon.displayMessage("Shutting down...", "Shutdown time remaining: " + shutdownTime/1000 + " seconds."
                            + System.getProperty("line.separator") + "Shutdown will not happen immediately, because we are still participating in the network.", TrayIcon.MessageType.INFO);
                }
                else {
                    trayIcon.displayMessage("Shutting down...", "Shutting down immediately.", TrayIcon.MessageType.INFO);
                }
            }

        });
        
        //Add menu items to popup menu
        popup.add(browserLauncher);
        popup.add(howto);
        
        popup.addSeparator();
        
        config.add(speedConfig);
        config.add(generalConfig);
        config.add(advancedConfig);
        popup.add(config);
        
        popup.addSeparator();
        
        popup.add(viewLog);
        popup.add(version);
        
        popup.addSeparator();
        
        popup.add(shutdown);

        //Add tray icon
        trayIcon = new JPopupTrayIcon(image, "I2P: the anonymous network", popup);
        
        try {
            tray.add(trayIcon);
        } catch (AWTException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        PeerHelper.addReachabilityListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                updateTooltip();
            }
            
        });
        PeerHelper.addActivePeerListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                updateTooltip();
                int activePeers = PeerHelper.getActivePeers();
                if(activePeers == 0)
                    trayIcon.setImage(Toolkit.getDefaultToolkit().getImage("desktopgui/resources/logo/logo_red.jpg"));
                else if(activePeers < 10)
                    trayIcon.setImage(Toolkit.getDefaultToolkit().getImage("desktopgui/resources/logo/logo_orange.jpg"));
                else
                    trayIcon.setImage(Toolkit.getDefaultToolkit().getImage("desktopgui/resources/logo/logo_green.jpg"));
                
            }
            
        });
    }
    
    public void updateTooltip() {
        trayIcon.setToolTip("I2P Network status: " + PeerHelper.getReachability() + " / " + "Active Peers: " + PeerHelper.getActivePeers());
    }
    
    private SystemTray tray = null;
    private JPopupTrayIcon trayIcon = null;
    
}
