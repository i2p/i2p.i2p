package net.i2p.desktopgui.router;

import java.io.IOException;

import org.tanukisoftware.wrapper.WrapperManager;

import net.i2p.I2PAppContext;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Handle communications with the router instance.
 * @author mathias
 *
 */
public class RouterManager {
	
    /** @return non-null */
    private static I2PAppContext getAppContext() {
        return I2PAppContext.getGlobalContext();
    }
    
    /**
     * Start an I2P router instance.
     * This method has limited knowledge
     * (there is no I2P instance running to collect information from).
     * 
     * It determines the I2P location using the I2PAppContext.
     */
    public static void start() {
        try {
            //TODO: set/get PID
            String separator = System.getProperty("file.separator");
            String location = getAppContext().getBaseDir().getAbsolutePath();
            String[] args = new String[] { location + separator + "i2psvc", location + separator + "wrapper.config" };
            Runtime.getRuntime().exec(args);
        } catch (IOException e) {
            Log log = getAppContext().logManager().getLog(RouterManager.class);
            log.log(Log.WARN, "Failed to start I2P", e);
        }
    }
    
    /**
     * Restart the running I2P instance.
     */
    public static void restart(RouterContext ctx) {
        ctx.router().restart();
    }

    /**
     * Stop the running I2P instance.
     */
    public static void shutDown(RouterContext ctx) {
            Thread t = new Thread(new Runnable() {

                @Override
                public void run() {
                    WrapperManager.signalStopped(Router.EXIT_HARD);    
                }
                
            });
            t.start();
            ctx.router().shutdown(Router.EXIT_HARD);
    }
}
