package net.i2p.desktopgui;

/*
 * Main.java
 */

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import net.i2p.I2PAppContext;
import net.i2p.desktopgui.util.*;
import net.i2p.util.Log;
import net.i2p.util.Translate;
import net.i2p.util.I2PProperties.I2PPropertyCallback;

/**
 * The main class of the application.
 */
public class Main {
	
    ///Manages the lifetime of the tray icon.
    private TrayManager trayManager = null;
    private final static Log log = new Log(Main.class);

	/**
	 * Start the tray icon code (loads tray icon in the tray area).
	 */
    private void startUp() {
        trayManager = TrayManager.getInstance();
        trayManager.startManager();
        I2PAppContext.getCurrentContext().addPropertyCallback(new I2PPropertyCallback() {

			@Override
			public String getPropertyKey() {
				return Translate.PROP_LANG;
			}

			@Override
			public void propertyChanged(String arg0, String arg1) {
				trayManager.languageChanged();
			}
        	
        });
    }

    /**
     * Main method launching the application.
     */
    public static void main(String[] args) {
    	System.setProperty("java.awt.headless", "false");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException ex) {
            log.log(Log.ERROR, null, ex);
        } catch (InstantiationException ex) {
            log.log(Log.ERROR, null, ex);
        } catch (IllegalAccessException ex) {
        	log.log(Log.ERROR, null, ex);
        } catch (UnsupportedLookAndFeelException ex) {
        	log.log(Log.ERROR, null, ex);
        }
        
        ConfigurationManager.getInstance().loadArguments(args);
        
        final Main main = new Main();
        
        main.launchForeverLoop();
        //We'll be doing GUI work, so let's stay in the event dispatcher thread.
        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                main.startUp();
                
            }
            
        });
    }
    
    /**
     * Avoids the app terminating because no Window is opened anymore.
     * More info: http://java.sun.com/javase/6/docs/api/java/awt/doc-files/AWTThreadIssues.html#Autoshutdown
     */
    public void launchForeverLoop() {
       Runnable r = new Runnable() {
            public void run() {
                try {
                    Object o = new Object();
                    synchronized (o) {
                        o.wait();
                    }
                } catch (InterruptedException ie) {
                }
            }
        };
        Thread t = new Thread(r);
        t.setDaemon(false);
        t.start();
    }
    
}
