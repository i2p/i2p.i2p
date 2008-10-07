/**
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 * Everyone is permitted to copy and distribute verbatim or modified
 * copies of this license document, and changing it is allowed as long
 * as the name is changed.
 *
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 *  0. You just DO WHAT THE FUCK YOU WANT TO.
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and liscense questions.
 */
package net.i2p.BOB;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.util.Log;

/**
 *
 * Multiplex listeners for TCP and I2P
 * 
 * @author sponge
 */
public class MUXlisten implements Runnable {

	private nickname info;
	private Log _log;
	private I2PSocketManager socketManager;
	private ByteArrayInputStream prikey;
	private ThreadGroup tg;
	private String N;

	/**
	 * Constructor
	 * 
	 * @param info
	 * @param _log
	 * @throws net.i2p.I2PException
	 * @throws java.io.IOException
	 */
	MUXlisten(nickname info, Log _log) throws I2PException, IOException {
		this.info = info;
		this._log = _log;
		this.info.add("STARTING", true);

		N = this.info.get("NICKNAME").toString();
		prikey = new ByteArrayInputStream((byte[])info.get("KEYS"));
		socketManager = I2PSocketManagerFactory.createManager(prikey, (Properties)info.get("PROPERTIES"));
	}

	/**
	 * MUX sockets, fire off a thread to connect, get destination info, and do I/O
	 * 
	 */
	public void run() {

		tg = new ThreadGroup(N);
		info.add("RUNNING", true);
		info.add("STARTING", false);

		// toss the connections to a new threads.
		// will wrap with TCP and UDP when UDP works
		if(info.exists("OUTPORT")) {
			// I2P -> TCP
			I2Plistener conn = new I2Plistener(socketManager, info, _log);
			Thread t = new Thread(tg, conn, "BOBI2Plistener " + N);
			t.start();
		}
		if(info.exists("INPORT")) {
			// TCP -> I2P
			TCPlistener conn = new TCPlistener(socketManager, info, _log);
			Thread q = new Thread(tg, conn,"BOBTCPlistener" + N);
			q.start();
		}

		while(info.get("STOPPING").equals(false)) {
			try {
				Thread.sleep(1000); //sleep for 1000 ms (One second)
			} catch(InterruptedException e) {
				// nop
			}
		}

		info.add("RUNNING", false);
		// wait for child threads and thread groups to die
		while (tg.activeCount() + tg.activeGroupCount() != 0) {
			try {
				Thread.sleep(1000); //sleep for 1000 ms (One second)
			} catch(InterruptedException ex) {
				// nop
			}			
		}
		
		socketManager.destroySocketManager();
		tg.destroy();
		// Zap reference to the ThreadGroup so the JVM can GC it.
		tg = null;
		info.add("STOPPING", false);
		info.add("STARTING", false);

	}
}
