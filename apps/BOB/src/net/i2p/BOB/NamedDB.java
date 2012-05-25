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

/**
 * Internal database to relate nicknames to options to values
 *
 * @author sponge
 */
public class NamedDB {

	private volatile Object[][] data;
	private volatile int index,  writersWaiting,  readers;

	/**
	 * make initial NULL object
	 *
	 */
	public NamedDB() {
		this.data = new Object[1][2];
		this.index = this.writersWaiting = this.readers = 0;
	}

	synchronized public void getReadLock() {
		while ((writersWaiting != 0)) {
			try {
				wait();
			} catch (InterruptedException ie) {
			}
		}
		readers++;
	}

	synchronized public void releaseReadLock() {
		readers--;
		notifyAll();
	}

	synchronized public void getWriteLock() {
		writersWaiting++;
		while (readers != 0 && writersWaiting != 1) {
			try {
				wait();
			} catch (InterruptedException ie) {
			}
		}
	}

	synchronized public void releaseWriteLock() {
		writersWaiting--;
		notifyAll();
	}

	/**
	 * Find objects in the array, returns it's index or throws exception
	 * @param key
	 * @return an objects index
	 * @throws ArrayIndexOutOfBoundsException when key does not exist
	 */
	public int idx(Object key) throws ArrayIndexOutOfBoundsException {
		for (int i = 0; i < index; i++) {
			if (key.equals(data[i][0])) {
				return i;
			}
		}
		throw new ArrayIndexOutOfBoundsException("Can't locate key for index");
	}

	/**
	 * Delete an object from array if it exists
	 *
	 * @param key
	 */
	public void kill(Object key) {

		int i, j, k, l;
		Object[][] olddata;
		int didsomething = 0;

		try {
			k = idx(key);
		} catch (ArrayIndexOutOfBoundsException b) {
			return;
		}
		olddata = new Object[index + 2][2];
		// copy to olddata, skipping 'k'
		for (i = 0, l = 0; l < index; i++, l++) {
			if (i == k) {
				l++;
				didsomething++;
			}
			for (j = 0; j < 2; j++) {
				olddata[i][j] = data[l][j];
			}
		}
		index -= didsomething;
		data = olddata;
	}

	/**
	 * Add object to the array, deletes the old one if it exists
	 *
	 * @param key
	 * @param val
	 */
	public void add(Object key, Object val) {
		Object[][] olddata;
		int i, j;
		i = 0;
		kill(key);

		olddata = new Object[index + 2][2];
		// copy to olddata
		for (i = 0; i < index; i++) {
			for (j = 0; j < 2; j++) {
				olddata[i][j] = data[i][j];
			}
		}
		data = olddata;
		data[index++] = new Object[]{key, val};
	}

	/**
	 * Get the object, and return it, throws RuntimeException
	 *
	 * @param key
	 * @return Object
	 * @throws java.lang.RuntimeException
	 */
	public Object get(Object key) throws RuntimeException {
		for (int i = 0; i < index; i++) {
			if (key.equals(data[i][0])) {
				return data[i][1];
			}
		}
		throw new RuntimeException("Key not found");
	}

	/**
	 * returns true if an object exists, else returns false
	 *
	 * @param key
	 * @return true if an object exists, else returns false
	 */
	public boolean exists(Object key) {
		for (int i = 0; i < index; i++) {
			if (key.equals(data[i][0])) {
				return true;
			}
		}
		return false;

	}

	/**
	 *
	 * @param i index
	 * @return an indexed Object
	 * @throws java.lang.RuntimeException
	 */
	public Object getnext(int i) throws RuntimeException {
		if (i < index && i > -1) {
			return data[i][1];
		}
		throw new RuntimeException("No more data");
	}

	/**
	 * @return the count of how many objects
	 */
	public int getcount() {
		return index;
	}
}
