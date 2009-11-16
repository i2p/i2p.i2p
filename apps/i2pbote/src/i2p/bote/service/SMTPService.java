package i2p.bote.service;

import net.i2p.util.I2PAppThread;

public class SMTPService extends I2PAppThread {

	public SMTPService() {
		super("Background thread for receiving email from email clients");
	}
	
	public void shutDown() {
	}
}