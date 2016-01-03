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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.I2PAppThread;

/**
 * Listen on TCP port and connect to I2P
 *
 * @author sponge
 */
public class TCPlistener implements Runnable {

	private final NamedDB info,  database;
	private final Logger _log;
	private final I2PSocketManager socketManager;
	private final ServerSocket listener;
	private final AtomicBoolean lives;

	/**
	 * Constructor
	 * @param S
	 * @param info
	 * @param database
	 * @param _log
	 */
	TCPlistener(ServerSocket listener, I2PSocketManager S, NamedDB info, NamedDB database, Logger _log, AtomicBoolean lives) {
		this.database = database;
		this.info = info;
		this._log = _log;
		this.socketManager = S;
		this.listener = listener;
		this.lives = lives;
	}

	/**
	 * Simply listen on TCP port, and thread connections
	 *
	 */
	public void run() {
		boolean g = false;
		int conn = 0;
		Socket server = null;
		try {
			try {
				listener.setSoTimeout(50); // We don't block, we cycle and check.
				while (lives.get()) {
					try {
						server = listener.accept();
						server.setKeepAlive(true);
						g = true;
					} catch (SocketTimeoutException ste) {
						g = false;
					}
					if (g) {
						conn++;
						// toss the connection to a new thread.
						TCPtoI2P conn_c = new TCPtoI2P(socketManager, server, info, database, lives);
						Thread t = new I2PAppThread(conn_c, Thread.currentThread().getName() + " TCPtoI2P " + conn);
						t.start();
						g = false;
					}
				}
			} catch (IOException ioe) {
			}
		} finally {
			try {
				listener.close();
			} catch (IOException ex) {
			}
		//System.out.println("TCPlistener: " + Thread.currentThread().getName() +  "Done.");
		}
	}
}
