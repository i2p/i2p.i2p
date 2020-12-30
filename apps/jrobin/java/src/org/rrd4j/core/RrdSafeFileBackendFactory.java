package org.rrd4j.core;

import java.io.IOException;

/**
 * Factory class which creates actual {@link org.rrd4j.core.RrdSafeFileBackend} objects.
 *
 */
@RrdBackendAnnotation(name="SAFE", shouldValidateHeader=true, cachingAllowed=false)
public class RrdSafeFileBackendFactory extends RrdRandomAccessFileBackendFactory {

    /**
     * Default time (in milliseconds) this backend will wait for a file lock.
     */
    public static final long LOCK_WAIT_TIME = 3000L;
    private static long defaultLockWaitTime = LOCK_WAIT_TIME;

    /**
     * Default time between two consecutive file locking attempts.
     */
    public static final long LOCK_RETRY_PERIOD = 50L;
    private static long defaultLockRetryPeriod = LOCK_RETRY_PERIOD;

    private final long lockWaitTime;
    private final long lockRetryPeriod;

    /**
     * Generate a factory using the default system wide lock settings
     */
    public RrdSafeFileBackendFactory() {
        lockWaitTime = defaultLockWaitTime;
        lockRetryPeriod = defaultLockRetryPeriod;
    }

    /**
     * Generate a factory with custom lock settings
     * @param lockWaitTime wait time in ms
     * @param lockRetryPeriod retry period in ms
     */
    public RrdSafeFileBackendFactory(long lockWaitTime, long lockRetryPeriod) {
        this.lockWaitTime = lockWaitTime;
        this.lockRetryPeriod = lockRetryPeriod;
    }

    /**
     * {@inheritDoc}
     *
     * Creates RrdSafeFileBackend object for the given file path.
     */
    @Override
    protected RrdBackend open(String path, boolean readOnly) throws IOException {
        return new RrdSafeFileBackend(path, lockWaitTime, lockRetryPeriod);
    }

    /**
     * Returns time this backend will wait for a file lock.
     *
     * @return Time (in milliseconds) this backend will wait for a file lock.
     */
    public static long getLockWaitTime() {
        return defaultLockWaitTime;
    }

    /**
     * Sets time this backend will wait for a file lock.
     *
     * @param lockWaitTime Maximum lock wait time (in milliseconds)
     */
    public static void setLockWaitTime(long lockWaitTime) {
        RrdSafeFileBackendFactory.defaultLockWaitTime = lockWaitTime;
    }

    /**
     * Returns time between two consecutive file locking attempts.
     *
     * @return Time (im milliseconds) between two consecutive file locking attempts.
     */
    public static long getLockRetryPeriod() {
        return defaultLockRetryPeriod;
    }

    /**
     * Sets time between two consecutive file locking attempts.
     *
     * @param lockRetryPeriod time (in milliseconds) between two consecutive file locking attempts.
     */
    public static void setLockRetryPeriod(long lockRetryPeriod) {
        RrdSafeFileBackendFactory.defaultLockRetryPeriod = lockRetryPeriod;
    }

}
