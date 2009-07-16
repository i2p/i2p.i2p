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

import net.i2p.client.streaming.RetransmissionTimer;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer2;

/**
 * Start from command line
 *
 * @author sponge
 *
 */
public class Main {

	/**
	 * @param args the command line arguments, these are not used yet
	 */
	public static void main(String[] args) {
		// THINK THINK THINK THINK THINK THINK
		RetransmissionTimer Y = RetransmissionTimer.getInstance();
		SimpleScheduler Y1 = SimpleScheduler.getInstance();
		SimpleTimer2 Y2 = SimpleTimer2.getInstance();

		BOB.main(args);

		Y2.stop();
		Y1.stop();
		Y.stop();
	}
}
