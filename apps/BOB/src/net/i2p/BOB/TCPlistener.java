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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.Log;

/**
 * Listen on TCP port and connect to I2P
 *
 * @author sponge
 */
public class TCPlistener implements Runnable {

	private NamedDB info,  database;
	private Log _log;
	public I2PSocketManager socketManager;
	public I2PServerSocket serverSocket;
	private ServerSocket listener;
	private AtomicBoolean lives;

	/**
	 * Constructor
	 * @param S
	 * @param info
	 * @param database
	 * @param _log
	 */
	TCPlistener(ServerSocket listener, I2PSocketManager S, NamedDB info, NamedDB database, Log _log, AtomicBoolean lives) {
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
						Thread t = new Thread(conn_c, Thread.currentThread().getName() + " TCPtoI2P " + conn);
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
