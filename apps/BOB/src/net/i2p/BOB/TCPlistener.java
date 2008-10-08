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
import java.net.InetAddress;
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

	private nickname info;
	private Log _log;
	private int tgwatch;
	public I2PSocketManager socketManager;
	public I2PServerSocket serverSocket;
	private int backlog = 50; // should this be more? less?

	/**
	 * Constructor
	 * @param S
	 * @param info
	 * @param _log
	 */
	TCPlistener(I2PSocketManager S, nickname info, Log _log) {
		this.info = info;
		this._log = _log;
		this.socketManager = S;
		tgwatch = 1;
	}

	/**
	 * Simply listen on TCP port, and thread connections
	 * @throws java.lang.RuntimeException
	 */
	public void run() throws RuntimeException {
		boolean g = false;
		if(info.exists("OUTPORT")) {
			tgwatch = 2;
		}
		try {
//			System.out.println("Starting thread count " + Thread.activeCount());
			ServerSocket listener = new ServerSocket(Integer.parseInt(info.get("INPORT").toString()), backlog, InetAddress.getByName(info.get("INHOST").toString()));
			Socket server = new Socket();
			listener.setSoTimeout(1000);
			while(info.get("RUNNING").equals(Boolean.TRUE)) {
//				System.out.println("Thread count " + Thread.activeCount());
				try {
					server = listener.accept();
					g = true;
				} catch(SocketTimeoutException ste) {
					g = false;
				}
				if(g) {
					// toss the connection to a new thread.
					TCPtoI2P conn_c = new TCPtoI2P(socketManager, server, info);
					Thread t = new Thread(conn_c, "BOBTCPtoI2P");
					t.start();
					g = false;
				} 
			}
			listener.close();
		} catch(IOException ioe) {
			// throw new RuntimeException(ioe);
		}

//System.out.println("STOP!");

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


