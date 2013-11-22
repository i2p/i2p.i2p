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

import java.util.Map;
import java.util.Properties;

/**
 * Sets of "friendly" utilities to make life easier.
 * Any "Lifted" code will apear here, and credits given.
 * It's better to "Lift" a small chunk of "free" code than add in piles of
 * code we don't need, and don't want.
 *
 * @author sponge
 */
public class Lifted {

	/**
	 * Copy a set of properties from one Property to another.
	 * Lifted from Apache Derby code svn repository.
	 * Liscenced as follows:
	 * http://svn.apache.org/repos/asf/db/derby/code/trunk/LICENSE
	 *
	 * @param src_prop  Source set of properties to copy from.
	 * @param dest_prop Dest Properties to copy into.
	 *
	 **/
	public static void copyProperties(Properties src_prop, Properties dest_prop) {
		for (Map.Entry<Object, Object> e : src_prop.entrySet()) {
			dest_prop.put((String)e.getKey(), (String)e.getValue());
		}
	}
}
