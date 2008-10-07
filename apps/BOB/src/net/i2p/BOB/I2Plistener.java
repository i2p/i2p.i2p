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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.Log;

/**
 * Listen on I2P and connect to TCP
 *
 * @author sponge
 */
public class I2Plistener implements Runnable {

	private nickname info;
	private Log _log;
	private int tgwatch;
	public I2PSocketManager socketManager;
	public I2PServerSocket serverSocket;

	/**
	 * Constructor
	 * @param S
	 * @param info
	 * @param _log
	 */
	I2Plistener(I2PSocketManager S, nickname info, Log _log) {
		this.info = info;
		this._log = _log;
		this.socketManager = S;
		serverSocket = socketManager.getServerSocket();
		tgwatch = 1;
	}

	/**
	 * Simply listen on I2P port, and thread connections
	 * 
	 * @throws RuntimeException 
	 */
	public void run() throws RuntimeException {
		boolean g = false;
		I2PSocket sessSocket = null;

		// needed to hack in this method :-/
		serverSocket.setSoTimeout(1000);
		if(info.exists("INPORT")) {
			tgwatch = 2;
		}
		while(info.get("RUNNING").equals(true)) {
			try {
				try {
					sessSocket = serverSocket.accept();
					g = true;
				} catch(ConnectException ce) {
					g = false;
				} catch (SocketTimeoutException ste) {
					g = false;
				}
				if(g) {
					g = false;
					// toss the connection to a new thread.
					I2PtoTCP conn_c = new I2PtoTCP(sessSocket, info);
					Thread t = new Thread(conn_c, "BOBI2PtoTCP");
					t.start();
				}

			} catch(I2PException e) {
				System.out.println("Exception "+e);
			}
		}

		try {
			serverSocket.close();
		} catch(I2PException e) {
			// nop
		}

		while(Thread.activeCount() > tgwatch) { // wait for all threads in our threadgroup to finish
//			System.out.println("STOP Thread count " + Thread.activeCount());
			try {
				Thread.sleep(1000); //sleep for 1000 ms (One second)
			} catch(Exception e) {
				// nop
				}
		}
//		System.out.println("STOP Thread count " + Thread.activeCount());
		// need to kill off the socket manager too.
		I2PSession session = socketManager.getSession();
		if(session != null) {
			try {
				session.destroySession();
			} catch(I2PSessionException ex) {
				// nop
			}
//			System.out.println("destroySession Thread count " + Thread.activeCount());
		}


	}
}
