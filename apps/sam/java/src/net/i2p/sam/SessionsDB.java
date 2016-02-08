package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;

/**
 *  basically a HashMap from String to SessionRecord
 *
 *  @since 0.9.25 moved from SAMv3Handler
 */
class SessionsDB {
	private static final long serialVersionUID = 0x1;

	static class ExistingIdException extends Exception {
		private static final long serialVersionUID = 0x1;
	}

	static class ExistingDestException extends Exception {
		private static final long serialVersionUID = 0x1;
	}
	
	private final HashMap<String, SessionRecord> map;

	public SessionsDB() {
		map = new HashMap<String, SessionRecord>() ;
	}

	public synchronized void put(String nick, SessionRecord session)
		throws ExistingIdException, ExistingDestException
	{
		if ( map.containsKey(nick) ) {
			throw new ExistingIdException();
		}
		for ( SessionRecord r : map.values() ) {
			if (r.getDest().equals(session.getDest())) {
				throw new ExistingDestException();
			}
		}
		session.createThreadGroup("SAM session "+nick);
		map.put(nick, session) ;
	}

	/** @since 0.9.25 */
	public synchronized void putDupDestOK(String nick, SessionRecord session)
		throws ExistingIdException
	{
		if (map.containsKey(nick)) {
			throw new ExistingIdException();
		}
		session.createThreadGroup("SAM session "+nick);
		map.put(nick, session) ;
	}

	/** @return true if removed */
	synchronized public boolean del( String nick )
	{
		return map.remove(nick) != null;
	}

	synchronized public SessionRecord get(String nick)
	{
		return map.get(nick);
	}

	synchronized public boolean containsKey( String nick )
	{
		return map.containsKey(nick);
	}
}
