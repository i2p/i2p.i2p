package i2p.bote.web;

import i2p.bote.I2PBote;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * Starts the I2PBote backend when the web app is initialized
 * @author HungryHobo@mail.i2p
 *
 */
public class ServiceInitializer implements ServletContextListener {
	@Override
	public void contextDestroyed(ServletContextEvent event) {
		I2PBote.shutDown();
	}

	@Override
	public void contextInitialized(ServletContextEvent event) {
		I2PBote.startUp();
	}

}
