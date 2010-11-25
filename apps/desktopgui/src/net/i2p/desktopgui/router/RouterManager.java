package net.i2p.desktopgui.router;

import java.io.IOException;

import net.i2p.desktopgui.util.ConfigurationManager;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

public class RouterManager {
	
	private static Router getRouter() {
		return RouterContext.listContexts().get(0).router();
	}
	
	public static void start() {
		try {
			String location = ConfigurationManager.getInstance().getStringConfiguration("I2PLocation", "/home/i2p");
			//TODO: detect I2P directory
			//TODO: crossplatform
			Runtime.getRuntime().exec(location + "/i2psvc " + location + "/wrapper.config");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static void restart() {
		getRouter().restart();
	}

	public static void shutDown() {
		getRouter().shutdownGracefully();
    }
}
