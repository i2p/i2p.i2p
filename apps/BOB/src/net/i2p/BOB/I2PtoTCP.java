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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import net.i2p.client.streaming.I2PSocket;

/**
 * Process I2P->TCP
 * 
 * @author sponge
 */
public class I2PtoTCP implements Runnable {

	private I2PSocket I2P;
	private nickname info;
	private Socket sock;

	/**
	 * Constructor
	 * 
	 * @param I2Psock
	 * @param db
	 */
	I2PtoTCP(I2PSocket I2Psock, nickname db) {
		this.I2P = I2Psock;
		this.info = db;
	}

	/**
	 * I2P stream to TCP stream thread starter
	 * 
	 */
	public void run() {

		try {
			sock = new Socket(info.get("OUTHOST").toString(), Integer.parseInt(info.get("OUTPORT").toString()));
			// make readers/writers
			InputStream in = sock.getInputStream();
			OutputStream out = sock.getOutputStream();
			InputStream Iin = I2P.getInputStream();
			OutputStream Iout = I2P.getOutputStream();
			I2P.setReadTimeout(0); // temp bugfix, this *SHOULD* be the default

			if(info.get("QUIET").equals(false)) {
				// tell who is connecting
				out.write(I2P.getPeerDestination().toBase64().getBytes());
				out.write(10); // nl
				out.flush(); // not really needed, but...
			}
			// setup to cross the streams
			TCPio conn_c = new TCPio(in, Iout, info); // app -> I2P
			TCPio conn_a = new TCPio(Iin, out, info); // I2P -> app
			Thread t = new Thread(conn_c, "TCPioA");
			Thread q = new Thread(conn_a, "TCPioB");
			// Fire!
			t.start();
			q.start();
			while(t.isAlive() && q.isAlive()) { // AND is used here to kill off the other thread
				try {
					Thread.sleep(10); //sleep for 10 ms
				} catch(InterruptedException e) {
					// nop
				}
			}

		} catch(Exception e) {
		}
		try {
			I2P.close();
		} catch(Exception e) {
		}
		try {
			sock.close();
		} catch(Exception e) {
		}
	}
}
