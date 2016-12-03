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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Internal database to relate nicknames to options to values
 *
 * @author sponge
 */
public class NamedDB {

	private final Map<String, Object> data;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(false);

	/**
	 *
	 */
	public NamedDB() {
		this.data = new HashMap<String, Object>();
	}

	public void getReadLock() {
		lock.readLock().lock();
	}

	public void releaseReadLock() {
		lock.readLock().unlock();
	}

	public void getWriteLock() {
		lock.writeLock().lock();
	}

	public void releaseWriteLock() {
		lock.writeLock().unlock();
	}

	/**
	 * Delete an object if it exists
	 *
	 * @param key
	 */
	public void kill(String key) {
		data.remove(key);
	}

	/**
	 * Add object, deletes the old one if it exists
	 *
	 * @param key
	 * @param val
	 */
	public void add(String key, Object val) {
		data.put(key, val);
	}

	/**
	 * Get the object, and return it, throws RuntimeException if not found
	 *
	 * @param key non-null
	 * @return Object non-null
	 * @throws java.lang.RuntimeException if not found
	 */
	public Object get(String key) throws RuntimeException {
		Object rv = data.get(key);
		if (rv != null)
			return rv;
		throw new RuntimeException("Key not found");
	}

	/**
	 * returns true if an object exists, else returns false
	 *
	 * @param key
	 * @return true if an object exists, else returns false
	 */
	public boolean exists(String key) {
		return data.containsKey(key);
	}

	/**
	 * @since 0.9.29 replaces getcount() and getnext(int)
	 */
	public Collection<Object> values() {
		return data.values();
	}
}
