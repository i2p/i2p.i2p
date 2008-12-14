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
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
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
	private int tgwatch;
	public I2PSocketManager socketManager;
	public I2PServerSocket serverSocket;
	private ServerSocket listener;

	/**
	 * Constructor
	 * @param S
	 * @param info
	 * @param database
	 * @param _log
	 */
	TCPlistener(ServerSocket listener, I2PSocketManager S, NamedDB info, NamedDB database, Log _log) {
		this.database = database;
		this.info = info;
		this._log = _log;
		this.socketManager = S;
		this.listener = listener;
		tgwatch = 1;
	}

	/**
	 * Simply listen on TCP port, and thread connections
	 *
	 */
	public void run() {
		boolean g = false;
		boolean spin = true;
		database.getReadLock();
		info.getReadLock();
		if(info.exists("OUTPORT")) {
			tgwatch = 2;
		}
		try {
			Socket server = new Socket();
			listener.setSoTimeout(1000);
			info.releaseReadLock();
			database.releaseReadLock();
			while(spin) {
				database.getReadLock();
				info.getReadLock();
				spin = info.get("RUNNING").equals(Boolean.TRUE);
				info.releaseReadLock();
				database.releaseReadLock();
				try {
					server = listener.accept();
					g = true;
				} catch(SocketTimeoutException ste) {
					g = false;
				}
				if(g) {
					// toss the connection to a new thread.
					TCPtoI2P conn_c = new TCPtoI2P(socketManager, server, info, database);
					Thread t = new Thread(conn_c, "BOBTCPtoI2P");
					t.start();
					g = false;
				}
			}
			//System.out.println("TCPlistener: destroySession");
			listener.close();
		} catch(IOException ioe) {
			try {
				listener.close();
			} catch(IOException e) {
			}
			// Fatal failure, cause a stop event
			database.getReadLock();
			info.getReadLock();
			spin = info.get("RUNNING").equals(Boolean.TRUE);
			info.releaseReadLock();
			database.releaseReadLock();
			if(spin) {
				database.getWriteLock();
				info.getWriteLock();
				info.add("STOPPING", new Boolean(true));
				info.add("RUNNING", new Boolean(false));
				info.releaseWriteLock();
				database.releaseWriteLock();
			}
		}

		// need to kill off the socket manager too.
		I2PSession session = socketManager.getSession();
		if(session != null) {
			try {
				session.destroySession();
			} catch(I2PSessionException ex) {
				// nop
			}
		}
		//System.out.println("TCPlistener: Waiting for children");
		while(Thread.activeCount() > tgwatch) { // wait for all threads in our threadgroup to finish
			try {
				Thread.sleep(100); //sleep for 100 ms (One tenth second)
			} catch(Exception e) {
				// nop
				}
		}
		//System.out.println("TCPlistener: Done.");
	}
}


