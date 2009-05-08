package net.i2p.desktopgui.desktopgui;

/*
 * Main.java
 */



import net.i2p.desktopgui.gui.Tray;
import net.i2p.desktopgui.gui.SpeedSelector;
import java.awt.SystemTray;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.jdesktop.application.Application;
import org.jdesktop.application.SingleFrameApplication;
import net.i2p.desktopgui.persistence.PropertyManager;

/**
 * The main class of the application.
 */
public class Main extends SingleFrameApplication {

    /**
     * At startup create and show the main frame of the application.
     */
    @Override protected void startup() {
        Properties props = PropertyManager.loadProps();
        
        //First load: present screen with information (to help choose I2P settings)
        if(props.getProperty(FIRSTLOAD).equals("true")) {
            props.setProperty(FIRSTLOAD, "false");
            PropertyManager.saveProps(props);
            new SpeedSelector(); //Start speed selector GUI
        }
        
        if(SystemTray.isSupported()) {
            tray = new Tray();
        }
        else { //Alternative if SystemTray is not supported on the platform
        }
    }

    /**
     * This method is to initialize the specified window by injecting resources.
     * Windows shown in our application come fully initialized from the GUI
     * builder, so this additional configuration is not needed.
     */
    @Override protected void configureWindow(java.awt.Window root) {
    }

    /**
     * A convenient static getter for the application instance.
     * @return the instance of Main
     */
    public static Main getApplication() {
        return Application.getInstance(Main.class);
    }

    /**
     * Main method launching the application.
     */
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");  //Make sure I2P is running in GUI mode for our application
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (UnsupportedLookAndFeelException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        Main main = getApplication();
        main.launchForeverLoop();
        main.startup();
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
    
    private Tray tray = null;
    ///Indicates if this is the first time the application loads
    ///(is only true at the very start of loading the first time!)
    private static final String FIRSTLOAD = "firstLoad";
}
