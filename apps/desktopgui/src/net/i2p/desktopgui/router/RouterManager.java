package net.i2p.desktopgui.router;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.desktopgui.i18n.DesktopguiTranslator;
import net.i2p.desktopgui.util.ConfigurationManager;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Handle communications with the router instance.
 * @author mathias
 *
 */
public class RouterManager {
	
	private final static Log log = new Log(RouterManager.class);
	private static I2PAppContext context = I2PAppContext.getCurrentContext();
	
	public static I2PAppContext getAppContext() {
		return context;
	}
	
	public static RouterContext getRouterContext() throws Exception {
		if(context.isRouterContext()) {
			return (RouterContext) context;
		}
		else {
			throw new Exception("No RouterContext available!");
		}
	}
	
	private static Router getRouter() {
		try {
			return getRouterContext().router();
		} catch (Exception e) {
			log.error("Failed to get router. Why did we request it if no RouterContext is available?", e);
			return null;
		}
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
			
			Runtime.getRuntime().exec(location + separator + "i2psvc " + location + separator + "wrapper.config");
		} catch (IOException e) {
			log.log(Log.WARN, "Failed to start I2P", e);
		}
	}
	
	/**
	 * Restart the running I2P instance.
	 */
	public static void restart() {
		if(inI2P()) {
			getRouter().restart();
		}
	}

	/**
	 * Stop the running I2P instance.
	 */
	public static void shutDown() {
		if(inI2P()) {
			getRouter().shutdownGracefully();
		}
    }
	
	/**
	 * Check if we are running inside I2P.
	 */
	public static boolean inI2P() {
		return context.isRouterContext();
	}
}
