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

	private NamedDB info,  database;
	private Log _log;
	private int tgwatch;
	public I2PSocketManager socketManager;
	public I2PServerSocket serverSocket;

	/**
	 * Constructor
	 * @param S
	 * @param info
	 * @param database
	 * @param _log
	 */
	I2Plistener(I2PSocketManager S, NamedDB info, NamedDB database, Log _log) {
		this.database = database;
		this.info = info;
		this._log = _log;
		this.socketManager = S;
		serverSocket = this.socketManager.getServerSocket();
		tgwatch = 1;
	}

	/**
	 * Simply listen on I2P port, and thread connections
	 *
	 */
	public void run() {
		boolean g = false;
		I2PSocket sessSocket = null;

		serverSocket.setSoTimeout(50);
		database.getReadLock();
		info.getReadLock();
		if(info.exists("INPORT")) {
			tgwatch = 2;
		}
		info.releaseReadLock();
		database.releaseReadLock();
		boolean spin = true;
		while(spin) {

			database.getReadLock();
			info.getReadLock();
			spin = info.get("RUNNING").equals(Boolean.TRUE);
			info.releaseReadLock();
			database.releaseReadLock();
			try {
				try {
					sessSocket = serverSocket.accept();
					g = true;
				} catch(ConnectException ce) {
					g = false;
				} catch(SocketTimeoutException ste) {
					g = false;
				}
				if(g) {
					g = false;
					// toss the connection to a new thread.
					I2PtoTCP conn_c = new I2PtoTCP(sessSocket, info, database);
					Thread t = new Thread(conn_c, "BOBI2PtoTCP");
					t.start();
				}

			} catch(I2PException e) {
				//	System.out.println("Exception " + e);
			}
		}
		// System.out.println("I2Plistener: Close");
		try {
			serverSocket.close();
		} catch(I2PException e) {
			// nop
		}
		// need to kill off the socket manager too.
		I2PSession session = socketManager.getSession();
		if(session != null) {
			// System.out.println("I2Plistener: destroySession");
			try {
				session.destroySession();
			} catch(I2PSessionException ex) {
				// nop
			}
		}
		// System.out.println("I2Plistener: Waiting for children");
		while(Thread.activeCount() > tgwatch) { // wait for all threads in our threadgroup to finish
			try {
				Thread.sleep(100); //sleep for 100 ms (One tenth second)
			} catch(Exception e) {
				// nop
			}
		}

		// System.out.println("I2Plistener: Done.");
	}
}
