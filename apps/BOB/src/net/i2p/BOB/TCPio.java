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

/**
 * Shove data from one stream to the other.
 *
 * @author sponge
 */
public class TCPio implements Runnable {

	private InputStream Ain;
	private OutputStream Aout;
	private nickname info,  database;

	/**
	 * Constructor
	 *
	 * @param Ain
	 * @param Aout
	 * @param info
	 * @param database
	 */
	TCPio(InputStream Ain, OutputStream Aout, nickname info, nickname database) {
		this.Ain = Ain;
		this.Aout = Aout;
		this.info = info;
		this.database = database;
	}

	/**
	 * kill off the streams, to hopefully cause an IOException in the thread in order to kill it.
	 */
	/**
	 * Copy from source to destination...
	 * and yes, we are totally OK to block here on writes,
	 * The OS has buffers, and I intend to use them.
	 *
	 */
	public void run() {
		int b;
		byte a[] = new byte[1];
		boolean spin = true;
		try {
			while(spin) {
				database.getReadLock();
				info.getReadLock();
				spin = info.get("RUNNING").equals(Boolean.TRUE);
				info.releaseReadLock();
				database.releaseReadLock();

				b = Ain.read(a, 0, 1);
				// System.out.println(info.get("NICKNAME").toString() + " " + b);
				if(b > 0) {
					Aout.write(a, 0, 1);
				// Aout.flush(); too slow!
				} else if(b == 0) {
					try {
						// Thread.yield();
						Thread.sleep(10);
					} catch(InterruptedException ex) {
					}
				} else {
					/* according to the specs:
					 *
					 * The total number of bytes read into the buffer,
					 * or -1 if there is no more data because the end of
					 * the stream has been reached.
					 *
					 */
					return;
				}
			}
		} catch(Exception e) {
		}
	}
}
