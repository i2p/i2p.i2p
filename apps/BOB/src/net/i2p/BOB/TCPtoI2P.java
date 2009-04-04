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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;

/**
 *
 * Process TCP->I2P
 *
 * @author sponge
 */
public class TCPtoI2P implements Runnable {

	private I2PSocket I2P;
	// private NamedDB info,  database;
	private Socket sock;
	private I2PSocketManager socketManager;

	/**
	 * This is a more forgiving readline,
	 * it works on unbuffered streams
	 *
	 * @param in
	 * @return line of text as a String
	 * @throws Exception
	 */
	private static String lnRead(InputStream in) throws Exception {
		String S;
		int b;
		char c;

		S = new String();

		while(true) {
			b = in.read();
			if(b == 13) {
				//skip CR
				continue;
			}
			if(b < 20 || b > 126) {
				// exit on anything not legal
				break;
			}
			c = (char)(b & 0x7f); // We only really give a fuck about ASCII
			S = new String(S + c);
		}
		return S;
	}

	/**
	 * Constructor
	 * @param i2p
	 * @param socket
	 * param info
	 * param database
	 */
	TCPtoI2P(I2PSocketManager i2p, Socket socket /*, NamedDB info, NamedDB database */) {
		this.sock = socket;
		// this.info = info;
		// this.database = database;
		this.socketManager = i2p;
	}

	/**
	 * Print an error message to out
	 *
	 * @param e
	 * @param out
	 * @throws java.io.IOException
	 */
	private void Emsg(String e, OutputStream out) throws IOException {
// Debugging		System.out.println("ERROR TCPtoI2P: " + e);
		out.write("ERROR".concat(e).getBytes());
		out.write(13); // cr
		out.flush();
	}

	/**
	 * TCP stream to I2P stream thread starter
	 *
	 */
	public void run() {
		String line, input;

		try {

			InputStream in = sock.getInputStream();
			OutputStream out = sock.getOutputStream();
			try {
				line = lnRead(in);
				input = line.toLowerCase();
				Destination dest = null;

				if(input.endsWith(".i2p")) {
					dest = I2PTunnel.destFromName(input);
					line = dest.toBase64();
				}
				dest = new Destination();
				dest.fromBase64(line);

				try {
					// get a client socket
					I2P = socketManager.connect(dest);
					I2P.setReadTimeout(0); // temp bugfix, this *SHOULD* be the default
					// make readers/writers
					InputStream Iin = I2P.getInputStream();
					OutputStream Iout = I2P.getOutputStream();
					// setup to cross the streams
					TCPio conn_c = new TCPio(in, Iout /*, info, database */); // app -> I2P
					TCPio conn_a = new TCPio(Iin, out /*, info, database */); // I2P -> app
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
					// System.out.println("TCPtoI2P: Going away...");

				} catch(I2PException e) {
					Emsg("ERROR " + e.toString(), out);
				} catch(ConnectException e) {
					Emsg("ERROR " + e.toString(), out);
				} catch(NoRouteToHostException e) {
					Emsg("ERROR " + e.toString(), out);
				} catch(InterruptedIOException e) {
					Emsg("ERROR " + e.toString(), out);
				}

			} catch(Exception e) {
				Emsg("ERROR " + e.toString(), out);
			}
		} catch(Exception e) {
			// bail on anything else
		}
		try {
			// System.out.println("TCPtoI2P: Close I2P");
			I2P.close();
		} catch(Exception e) {
		}

		try {
			// System.out.println("TCPtoI2P: Close sock");
			sock.close();
		} catch(Exception e) {
		}
		// System.out.println("TCPtoI2P: Done.");
	}
}
