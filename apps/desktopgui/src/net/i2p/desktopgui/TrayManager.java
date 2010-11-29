package net.i2p.desktopgui;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Desktop.Action;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.swing.SwingWorker;

import net.i2p.desktopgui.router.RouterManager;
import net.i2p.desktopgui.util.ConfigurationManager;
import net.i2p.util.Log;

/**
 * Manages the tray icon life.
 */
public class TrayManager {

	private static TrayManager instance = null;
	///The tray area, or null if unsupported
    private SystemTray tray = null;
    ///Our tray icon, or null if unsupported
    private TrayIcon trayIcon = null;
    private final static Log log = new Log(TrayManager.class);
    
    /**
     * Instantiate tray manager.
     */
    private TrayManager() {}
    
    public static TrayManager getInstance() {
    	if(instance == null) {
    		instance = new TrayManager();
    	}
    	return instance;
    }

    /**
     * Add the tray icon to the system tray and start everything up.
     */
    public void startManager() {
        if(SystemTray.isSupported()) {
            tray = SystemTray.getSystemTray();
            trayIcon = new TrayIcon(getTrayImage(), "I2P", getMainMenu());
            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
            	log.log(Log.WARN, "Problem creating system tray icon!", e);
            }
        }
    }
    
    /**
     * Build a popup menu, adding callbacks to the different items.
     * @return popup menu
     */
    public PopupMenu getMainMenu() {
    	boolean inI2P = ConfigurationManager.getInstance().getBooleanConfiguration("startWithI2P", false);
    	
        PopupMenu popup = new PopupMenu();
        MenuItem browserLauncher = new MenuItem("Launch I2P Browser");
        browserLauncher.addActionListener(new ActionListener() {
            
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {

                    @Override
                    protected Object doInBackground() throws Exception {
                        return null;
                    }
                    
                    @Override
                    protected void done() {
                        if(Desktop.isDesktopSupported()) {
                            Desktop desktop = Desktop.getDesktop();
                            if(desktop.isSupported(Action.BROWSE)) {
                                try {
                                    desktop.browse(new URI("http://localhost:7657"));
                                } catch (IOException e) {
                                    log.log(Log.WARN, "Failed to open browser!", e);
                                } catch (URISyntaxException e) {
                                    log.log(Log.WARN, "Failed to open browser!", e);
                                }
                            }
                            else {
                                trayIcon.displayMessage("Browser not found",
                                        "The default browser for your system was not found.",
                                        TrayIcon.MessageType.WARNING);
                            }
                        }    
                    }
                    
                }.execute();
            }
        });
        popup.add(browserLauncher);
        popup.addSeparator();
        MenuItem startItem = new MenuItem("Start I2P");
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
						trayIcon.displayMessage("Starting", "I2P is starting!", TrayIcon.MessageType.INFO);
						//Hide the tray icon.
						//We cannot stop the desktopgui program entirely,
						//since that risks killing the I2P process as well.
						tray.remove(trayIcon);
					}
					
				}.execute();
			}
        	
        });
        if(!inI2P) {
        	popup.add(startItem);
        }
        MenuItem restartItem = new MenuItem("Restart I2P");
        restartItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {

                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.restart();
                        return null;
                    }
                    
                }.execute();
                
            }
            
        });
        if(inI2P) {
        	popup.add(restartItem);
        }
        MenuItem stopItem = new MenuItem("Stop I2P");
        stopItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    
                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.shutDown();
                        return null;
                    }
                    
                }.execute();
                
            }
            
        });
        if(inI2P) {
        	popup.add(stopItem);
        }
        return popup;
    }
    
    /**
     * Get tray icon image from the desktopgui resources in the jar file.
     * @return image used for the tray icon
     */
    public Image getTrayImage() {
        URL url = getClass().getResource("/desktopgui/resources/images/logo.jpg");
        Image image = Toolkit.getDefaultToolkit().getImage(url);
        return image;
    }
}
