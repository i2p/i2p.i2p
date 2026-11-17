package net.i2p.i2ptunnel.access;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/** Local counter processing and purge cost; does not measure I2P throughput. */
public class AccessCounterFloodBenchmark {
    private static final Threshold LIMIT = new Threshold(100, 60);
    private static final com.sun.management.ThreadMXBean ALLOCATION =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        int attempts = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
        if (attempts < 100)
            throw new IllegalArgumentException("At least 100 attempts required");
        if (!ALLOCATION.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("Thread allocation counters unavailable");
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 4; i++)
            sample(attempts);
        long[][] samples = new long[4][7];
        for (int i = 0; i < 7; i++) {
            long[] sample = sample(attempts);
            for (int j = 0; j < sample.length; j++)
                samples[j][i] = sample[j];
        }
        for (long[] values : samples)
            Arrays.sort(values);
        System.out.printf(java.util.Locale.US,
            "attempts=%d record_Mops=%.3f purge_ms=%.3f retained=%d allocated_bytes=%d%n",
            attempts, attempts * 1000.0 / samples[0][3], samples[1][3] / 1000000.0,
            samples[2][3], samples[3][3]);
    }

    private static long[] sample(int attempts) throws Exception {
        AccessCounter counter;
        try {
            Constructor<AccessCounter> constructor = AccessCounter.class.getDeclaredConstructor(int.class);
            counter = constructor.newInstance(100);
        } catch (NoSuchMethodException original) {
            counter = AccessCounter.class.getDeclaredConstructor().newInstance();
        }
        long id = Thread.currentThread().getId();
        long allocated = ALLOCATION.getThreadAllocatedBytes(id);
        long start = System.nanoTime();
        for (int i = 0; i < attempts; i++) {
            long now = 1000000000L + i / 100;
            counter.recordAccess(now);
            if (counter.isBreached(LIMIT, now) != (i >= 99))
                throw new AssertionError("Incorrect admission decision at " + i);
        }
        long recordNanos = System.nanoTime() - start;
        allocated = ALLOCATION.getThreadAllocatedBytes(id) - allocated;
        Field history = AccessCounter.class.getDeclaredField("accesses");
        history.setAccessible(true);
        Object stored = history.get(counter);
        int retained;
        if (stored instanceof List) {
            retained = ((List<?>) stored).size();
        } else {
            Field size = AccessCounter.class.getDeclaredField("size");
            size.setAccessible(true);
            retained = size.getInt(counter);
        }
        start = System.nanoTime();
        if (!counter.purge(1000000000L + attempts / 100 + 60001))
            throw new AssertionError("Expired history was retained");
        long purgeNanos = System.nanoTime() - start;
        return new long[] { recordNanos, purgeNanos, retained, allocated };
    }
}
