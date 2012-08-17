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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shove data from one stream to the other.
 *
 * @author sponge
 */
public class TCPio implements Runnable {

	private InputStream Ain;
	private OutputStream Aout;
	private AtomicBoolean lives;

	/**
	 * Constructor
	 *
	 * @param Ain InputStream
	 * @param Aout OutputStream
	 *
	 * param database
	 */
	TCPio(InputStream Ain, OutputStream Aout, AtomicBoolean lives) {
		this.Ain = Ain;
		this.Aout = Aout;
		this.lives = lives;
	}

	/**
	 * Copy from source to destination...
	 * and yes, we are totally OK to block here on writes,
	 * The OS has buffers, and I intend to use them.
	 * We send an interrupt signal to the threadgroup to
	 * unwedge any pending writes.
	 *
	 */
	public void run() {
		/*
		 * NOTE:
		 * The write method of OutputStream calls the write method of
		 * one argument on each of the bytes to be written out.
		 * Subclasses are encouraged to override this method and provide
		 * a more efficient implementation.
		 *
		 * So, is this really a performance problem?
		 * Should we expand to several bytes?
		 * I don't believe there would be any gain, since read method
		 * has the same reccomendations. If anyone has a better way to
		 * do this, I'm interested in performance improvements.
		 *
		 * --Sponge
		 *
		 * Tested with 128 bytes, and there was no performance gain.
		 * 8192 bytes did lower load average across many connections.
		 * Should I raise it higer? The correct thing to do would be to
		 * override... perhaps use NTCP, but I2P's streaming lib lacks
		 * anything NTCP compatable.
		 *
		 * --Sponge
		 */

		int b;
		byte a[] = new byte[8192];
		try {
			try {
				while (lives.get()) {
					b = Ain.read(a, 0, 8192);
					if (b > 0) {
						Aout.write(a, 0, b);
					} else if (b == 0) {
						while(Ain.available() == 0) {
							Thread.sleep(20);
						}
					} else {
						/* according to the specs:
						 *
						 * The total number of bytes read into the buffer,
						 * or -1 if there is no more data because the end of
						 * the stream has been reached.
						 *
						 */
						// System.out.println("TCPio: End Of Stream");
						break;
					}
				}
			} catch (Exception e) {
			}
		// System.out.println("TCPio: Leaving.");
		} finally {
			// Eject!!! Eject!!!
			//System.out.println("TCPio: Caught an exception " + e);
			try {
				Ain.close();
			} catch (IOException ex) {
			}
			try {
				Aout.close();
			} catch (IOException ex) {
			}
			return;
		}
	}
}
