/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

package org.jrobin.core;

import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Backend to be used to store all RRD bytes in memory.<p>
 */
public class RrdMemoryBackend extends RrdBackend {
	private static final ReadWriteLock m_readWritelock = new ReentrantReadWriteLock();
	private static final Lock m_readLock = m_readWritelock.readLock();
	private static final Lock m_writeLock = m_readWritelock.writeLock();

	private byte[] buffer = new byte[0];

	protected RrdMemoryBackend(String path) {
		super(path);
	}

	protected void write(final long offset, final byte[] b) {
		m_writeLock.lock();
		try {
		    int pos = (int) offset;
			for (final byte singleByte : b) {
				buffer[pos++] = singleByte;
			}
		} finally {
			m_writeLock.unlock();
		}
	}

	protected void read(final long offset, final byte[] b) throws IOException {
		m_readLock.lock();
		try {
			int pos = (int) offset;
			if (pos + b.length <= buffer.length) {
				for (int i = 0; i < b.length; i++) {
					b[i] = buffer[pos++];
				}
			}
			else {
				throw new IOException("Not enough bytes available in memory " + getPath());
			}
		} finally {
			m_readLock.unlock();
		}
	}

	/**
	 * Returns the number of RRD bytes held in memory.
	 *
	 * @return Number of all RRD bytes.
	 */
	public long getLength() {
		m_readLock.lock();
		try {
			return buffer.length;
		} finally {
			m_readLock.unlock();
		}
	}

	/**
	 * Reserves a memory section as a RRD storage.
	 *
	 * @param newLength Number of bytes held in memory.
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected void setLength(final long newLength) throws IOException {
		m_writeLock.lock();
		try {
			if (newLength > Integer.MAX_VALUE) {
				throw new IOException("Cannot create this big memory backed RRD");
			}
			buffer = new byte[(int) newLength];
		} finally {
			m_writeLock.unlock();
		}
	}

	/**
	 * This method is required by the base class definition, but it does not
	 * releases any memory resources at all.
	 */
	public void close() {
		// NOP
	}

	/**
	 * This method is overridden to disable high-level caching in frontend JRobin classes.
	 *
	 * @return Always returns <code>false</code>. There is no need to cache anything in high-level classes
	 *         since all RRD bytes are already in memory.
	 */
	protected boolean isCachingAllowed() {
		return false;
	}
}
