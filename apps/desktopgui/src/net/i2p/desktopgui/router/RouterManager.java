package net.i2p.desktopgui.router;

import java.io.IOException;

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
	
	private static Router getRouter() {
		return RouterContext.listContexts().get(0).router();
	}
	
	/**
	 * Start an I2P router instance.
	 * This method has limited knowledge
	 * (there is no I2P instance running to collect information from).
	 * 
	 * It needs to determine itself where the I2P instance is located,
	 * except if the location has been given through command-line arguments.
	 */
	public static void start() {
		try {
			String location = ConfigurationManager.getInstance().getStringConfiguration("I2PLocation", "/home/i2p");
			//TODO: detect I2P directory
			//TODO: crossplatform
			Runtime.getRuntime().exec(location + "/i2psvc " + location + "/wrapper.config");
		} catch (IOException e) {
			log.log(Log.WARN, "Failed to start I2P", e);
		}
	}
	
	/**
	 * Restart the running I2P instance.
	 */
	public static void restart() {
		getRouter().restart();
	}

	/**
	 * Stop the running I2P instance.
	 */
	public static void shutDown() {
		getRouter().shutdownGracefully();
    }
}
