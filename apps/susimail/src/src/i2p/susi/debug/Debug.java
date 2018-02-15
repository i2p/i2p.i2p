/*
 * Created on Nov 4, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.debug;

import net.i2p.I2PAppContext;

/**
 * @author susi23
 */
public class Debug {

	public static final int ERROR = 1;
	public static final int DEBUG = 2;
	private static int level = ERROR;

	public static void setLevel( int newLevel )
	{
		level = newLevel;
	}

	/** @since 0.9.13 */
	public static int getLevel() {
		return level;
	}

	public static void debug( int msgLevel, String msg ) {
		debug(msgLevel, msg, null);
	}

	/** @since 0.9.34 */
	public static void debug(int msgLevel, String msg, Throwable t)
	{
		if( msgLevel <= level ) {
			System.err.println("SusiMail: " + msg);
			if (t != null)
				t.printStackTrace();
		}
		if (msgLevel <= ERROR)
			I2PAppContext.getGlobalContext().logManager().getLog(Debug.class).error(msg, t);
	}
}
