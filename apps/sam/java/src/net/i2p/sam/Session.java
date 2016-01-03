package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;

/**
 * A V3 session.
 *
 * @since 0.9.25 moved from SAMv3Handler
 */
interface Session {
	String getNick();
	void close();
	boolean sendBytes(String dest, byte[] data, int proto,
	                  int fromPort, int toPort) throws DataFormatException, I2PSessionException;
}
	
