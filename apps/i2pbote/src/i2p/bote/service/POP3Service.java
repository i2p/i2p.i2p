package i2p.bote.service;

import net.i2p.util.I2PAppThread;

public class POP3Service extends I2PAppThread {

	public POP3Service() {
		super("Background thread for delivering email to email clients");
	}
	
	public void shutDown() {
	}
}