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

/**
 * Factory class which creates actual {@link RrdSafeFileBackend} objects.
 */
public class RrdSafeFileBackendFactory extends RrdFileBackendFactory {
	/**
	 * Default time (in milliseconds) this backend will wait for a file lock.
	 */
	public static final long LOCK_WAIT_TIME = 3000L;
	private static long lockWaitTime = LOCK_WAIT_TIME;

	/**
	 * Default time between two consecutive file locking attempts.
	 */
	public static final long LOCK_RETRY_PERIOD = 50L;
	private static long lockRetryPeriod = LOCK_RETRY_PERIOD;

	/**
	 * factory name, "SAFE"
	 */
	public static final String NAME = "SAFE";

	/**
	 * Creates RrdSafeFileBackend object for the given file path.
	 *
	 * @param path	 File path
	 * @param readOnly This parameter is ignored
	 * @return RrdSafeFileBackend object which handles all I/O operations for the given file path
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected RrdBackend open(String path, boolean readOnly) throws IOException {
		return new RrdSafeFileBackend(path, lockWaitTime, lockRetryPeriod);
	}

	/**
	 * Returns the name of this factory.
	 *
	 * @return Factory name (equals to string "SAFE")
	 */
	public String getFactoryName() {
		return NAME;
	}

	/**
	 * Returns time this backend will wait for a file lock.
	 *
	 * @return Time (in milliseconds) this backend will wait for a file lock.
	 */
	public static long getLockWaitTime() {
		return lockWaitTime;
	}

	/**
	 * Sets time this backend will wait for a file lock.
	 *
	 * @param lockWaitTime Maximum lock wait time (in milliseconds)
	 */
	public static void setLockWaitTime(long lockWaitTime) {
		RrdSafeFileBackendFactory.lockWaitTime = lockWaitTime;
	}

	/**
	 * Returns time between two consecutive file locking attempts.
	 *
	 * @return Time (im milliseconds) between two consecutive file locking attempts.
	 */
	public static long getLockRetryPeriod() {
		return lockRetryPeriod;
	}

	/**
	 * Sets time between two consecutive file locking attempts.
	 *
	 * @param lockRetryPeriod time (in milliseconds) between two consecutive file locking attempts.
	 */
	public static void setLockRetryPeriod(long lockRetryPeriod) {
		RrdSafeFileBackendFactory.lockRetryPeriod = lockRetryPeriod;
	}
}
