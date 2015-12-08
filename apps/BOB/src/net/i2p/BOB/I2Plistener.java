/**
 *                    WTFPL
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and license questions.
 */
package net.i2p.BOB;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.I2PAppThread;

/**
 * Listen on I2P and connect to TCP
 *
 * @author sponge
 */
public class I2Plistener implements Runnable {

	private final NamedDB info,  database;
	private final Logger _log;
	private final I2PServerSocket serverSocket;
	private final AtomicBoolean lives;

	/**
	 * Constructor
	 * @param SS
	 * @param S unused
	 * @param info
	 * @param database
	 * @param _log
	 */
	I2Plistener(I2PServerSocket SS, I2PSocketManager S, NamedDB info, NamedDB database, Logger _log, AtomicBoolean lives) {
		this.database = database;
		this.info = info;
		this._log = _log;
		this.serverSocket = SS;
		this.lives = lives;
	}

	/**
	 * Simply listen on I2P port, and thread connections
	 *
	 */
	public void run() {
		boolean g = false;
		I2PSocket sessSocket = null;
		int conn = 0;
		try {
			try {
				serverSocket.setSoTimeout(50);

				while (lives.get()) {
					try {
						sessSocket = serverSocket.accept();
						g = true;
					} catch (ConnectException ce) {
						g = false;
					} catch (SocketTimeoutException ste) {
						g = false;
					}
					if (g) {
						g = false;
						conn++;
						// toss the connection to a new thread.
						I2PtoTCP conn_c = new I2PtoTCP(sessSocket, info, database, lives);
						Thread t = new I2PAppThread(conn_c, Thread.currentThread().getName() + " I2PtoTCP " + conn);
						t.start();
					}

				}
			} catch (I2PException e) {
				// bad stuff
				System.out.println("Exception " + e);
			}
		} finally {
			try {
				serverSocket.close();
			} catch (I2PException ex) {
			}
			// System.out.println("I2Plistener: Close");
		}
	}
}
