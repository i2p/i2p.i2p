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
// import net.i2p.client.I2PSession;
// import net.i2p.client.I2PSessionException;
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

	private void rlock() throws Exception {
		database.getReadLock();
		info.getReadLock();
	}

	private void runlock() throws Exception {
		database.releaseReadLock();
		info.releaseReadLock();
	}

	/**
	 * Simply listen on TCP port, and thread connections
	 *
	 */
	public void run() {
		boolean g = false;
		boolean spin = true;

die:		{
			try {
				rlock();
			} catch (Exception e) {
				break die;
			}
			try {
				if (info.exists("OUTPORT")) {
					tgwatch = 2;
				}
			} catch (Exception e) {
				try {
					runlock();
				} catch (Exception e2) {
					break die;
				}
				break die;
			}
			try {
				runlock();
			} catch (Exception e) {
				break die;
			}
			try {
				Socket server = new Socket();
				listener.setSoTimeout(50); // We don't block, we cycle and check.
				while (spin) {
					try {
						rlock();
					} catch (Exception e) {
						break die;
					}
					try {
						spin = info.get("RUNNING").equals(Boolean.TRUE);
					} catch (Exception e) {
						try {
							runlock();
						} catch (Exception e2) {
							break die;
						}
						break die;
					}
					try {
						server = listener.accept();
						g = true;
					} catch (SocketTimeoutException ste) {
						g = false;
					}
					if (g) {
						// toss the connection to a new thread.
						TCPtoI2P conn_c = new TCPtoI2P(socketManager, server);
						Thread t = new Thread(conn_c, "BOBTCPtoI2P");
						t.start();
						g = false;
					}
				}
				listener.close();
			} catch (IOException ioe) {
				try {
					listener.close();
				} catch (IOException e) {
				}
			}
		}
	//System.out.println("TCPlistener: Done.");
	}
}
