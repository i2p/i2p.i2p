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

import java.util.Enumeration;
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
		for (Enumeration propertyNames = src_prop.propertyNames();
			propertyNames.hasMoreElements();) {
			Object key = propertyNames.nextElement();
			dest_prop.put(key, src_prop.get(key));
		}
	}
}
