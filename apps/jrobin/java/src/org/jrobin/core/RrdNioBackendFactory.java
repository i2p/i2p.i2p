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
 * Factory class which creates actual {@link RrdNioBackend} objects. This is
 * the default factory since 1.4.0 version
 */
public class RrdNioBackendFactory extends RrdFileBackendFactory {
    /**
     * Period in seconds between consecutive synchronizations when sync-mode
     * is set to SYNC_BACKGROUND. By default in-memory cache will be
     * transferred to the disc every 300 seconds (5 minutes). Default value
     * can be changed via {@link #setSyncPeriod(int)} method.
     */
    public static final int DEFAULT_SYNC_PERIOD = 300; // seconds

    private static SyncManager s_syncManager = new SyncManager(DEFAULT_SYNC_PERIOD);

    /**
     * factory name, "NIO"
     */
    public static final String NAME = "NIO";

    /**
     * Returns time between two consecutive background synchronizations. If
     * not changed via {@link #setSyncPeriod(int)} method call, defaults to
     * {@link #DEFAULT_SYNC_PERIOD}. See {@link #setSyncPeriod(int)} for more
     * information.
     * 
     * @return Time in seconds between consecutive background
     *         synchronizations.
     */
    public static int getSyncPeriod() {
        return s_syncManager.getSyncPeriod();
    }

    /**
     * Sets time between consecutive background synchronizations.
     * 
     * @param syncPeriod
     *            Time in seconds between consecutive background
     *            synchronizations.
     */
    public synchronized static void setSyncPeriod(final int syncPeriod) {
        s_syncManager.setSyncPeriod(syncPeriod);
    }

    /**
     * Creates RrdNioBackend object for the given file path.
     * 
     * @param path
     *            File path
     * @param readOnly
     *            True, if the file should be accessed in read/only mode.
     *            False otherwise.
     * @return RrdNioBackend object which handles all I/O operations for the
     *         given file path
     * @throws IOException
     *             Thrown in case of I/O error.
     */
    protected RrdBackend open(final String path, final boolean readOnly) throws IOException {
        return new RrdNioBackend(path, readOnly, s_syncManager);
    }

    public void shutdown() {
        s_syncManager.shutdown();
    }

    /**
     * Returns the name of this factory.
     * 
     * @return Factory name (equals to string "NIO")
     */
    public String getFactoryName() {
        return NAME;
    }

    @Override
    protected void finalize() throws Throwable {
        shutdown();
        super.finalize();
    }

    SyncManager getSyncManager() {
        return s_syncManager;
    }
}
