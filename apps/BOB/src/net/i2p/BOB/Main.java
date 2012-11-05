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
		SimpleScheduler Y1 = SimpleScheduler.getInstance();
		SimpleTimer2 Y2 = SimpleTimer2.getInstance();

		BOB.main(args);

		Y2.stop();
		Y1.stop();
	}
}
