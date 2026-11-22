package net.i2p.router;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;

/** Exercises real requeueing and clock updates without starting queue workers. */
public final class JobQueueClockShiftRegression {
    private static int checks;
    private static int failures;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        QuietContext context = new QuietContext();
        context.logManager().setDefaultLimit("CRIT");
        JobQueue queue = new JobQueue(context);
        try {
            field(RouterContext.class, "_jobQueue").set(context, queue);
            // Enable addJob(), but do not call startup() or start any runner.
            field(JobQueue.class, "_alive").setBoolean(queue, true);
            Map<Integer, JobQueueRunner> runners = (Map<Integer, JobQueueRunner>)
                    field(JobQueue.class, "_queueRunners").get(queue);
            Field current = field(JobQueueRunner.class, "_currentJob");
            Set<Job> timed = (Set<Job>) field(JobQueue.class, "_timedJobs").get(queue);
            Queue<Job> ready = (Queue<Job>) field(JobQueue.class, "_readyJobs").get(queue);
            List<ProbeJob> jobs = new ArrayList<>();

            ProbeJob timedCurrent = new ProbeJob(context, "timed and current", 63000);
            addRunner(context, runners, current, 1, timedCurrent);
            // A real JobImpl.requeue() occurs before the runner clears its
            // current job, as happens at the end of recurring runJob() methods.
            timedCurrent.runJob();
            jobs.add(timedCurrent);
            long lastStart = timedCurrent.getTiming().getStartAfter();
            for (int n = 0; n < 2; n++) {
                ProbeJob neighbor = new ProbeJob(context, "timed neighbor " + n, 0);
                neighbor.getTiming().setStartAfter(lastStart - 2000 + n * 1000);
                queue.addJob(neighbor);
                jobs.add(neighbor);
            }
            ProbeJob readyCurrent = new ProbeJob(context, "ready and current", 0);
            addRunner(context, runners, current, 2, readyCurrent);
            readyCurrent.runJob();
            jobs.add(readyCurrent);
            ProbeJob onlyCurrent = new ProbeJob(context, "current only", 0);
            addRunner(context, runners, current, 3, onlyCurrent);
            jobs.add(onlyCurrent);
            ProbeJob onlyReady = new ProbeJob(context, "ready only", 0);
            queue.addJob(onlyReady);
            jobs.add(onlyReady);

            for (ProbeJob job : jobs) {
                if (job != onlyReady) {
                    job.getTiming().setActualStart(lastStart - 4000);
                    job.getTiming().setActualEnd(lastStart - 3500);
                }
            }
            // onlyReady retains zero actual-start/end sentinel values.
            check(timed.contains(timedCurrent) && ready.contains(readyCurrent),
                  "requeued current jobs occupy both scheduling views");
            List<Job> readyOrder = new ArrayList<>(ready);
            Method update = JobQueue.class.getDeclaredMethod("updateJobTimings", long.class);
            update.setAccessible(true);
            for (long delta : new long[] { -10000L, 5000L }) {
                List<long[]> before = new ArrayList<>();
                for (ProbeJob job : jobs)
                    before.add(times(job.getTiming()));
                update.invoke(queue, delta);
                for (int n = 0; n < jobs.size(); n++) {
                    ProbeJob job = jobs.get(n);
                    long[] after = times(job.getTiming());
                    String[] names = { "scheduled start", "actual start", "actual end" };
                    for (int field = 0; field < after.length; field++) {
                        long original = before.get(n)[field];
                        long expected = original == 0 ? 0 : original + delta;
                        check(after[field] == expected,
                              job.getName() + " " + names[field] + " shifts once by " + delta
                              + " (expected " + expected + ", got " + after[field] + ")");
                    }
                }
                long previous = Long.MIN_VALUE;
                boolean ordered = true;
                for (Job job : timed) {
                    long when = job.getTiming().getStartAfter();
                    if (when < previous)
                        ordered = false;
                    previous = when;
                }
                check(ordered, "timed jobs remain ordered after shift " + delta);
                check(timed.size() == 3 && ready.size() == 2,
                      "clock shifts preserve queue membership counts");
                check(readyOrder.equals(new ArrayList<>(ready)),
                      "clock shifts preserve ready-queue order");
            }
            for (JobQueueRunner runner : runners.values())
                check(runner.getState() == Thread.State.NEW, "queue runner was never started");
        } finally {
            queue.shutdown();
            context.logManager().shutdown();
        }
        System.out.println("JOB_QUEUE_CLOCK_SHIFT_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0)
            System.exit(1);
    }

    private static long[] times(JobTiming timing) {
        return new long[] { timing.getStartAfter(), timing.getActualStart(), timing.getActualEnd() };
    }

    private static void addRunner(RouterContext context, Map<Integer, JobQueueRunner> runners,
                                  Field current, int id, Job job) throws Exception {
        JobQueueRunner runner = new JobQueueRunner(context, id);
        current.set(runner, job);
        runners.put(id, runner);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static final class QuietContext extends RouterContext {
        QuietContext() { super(null, new Properties()); }
        @Override protected void initializeClock() {
            // Construct the real clock but leave its network timestamper stopped.
            _clock = new RouterClock(this);
            _clockInitialized = true;
        }
        @Override public boolean isRouterContext() {
            // Keep LogManager's background writer dormant during this test.
            return false;
        }
    }

    private static final class ProbeJob extends JobImpl {
        private final String name;
        private final long delay;
        ProbeJob(RouterContext context, String name, long delay) {
            super(context);
            this.name = name;
            this.delay = delay;
        }
        public String getName() { return name; }
        public void runJob() { requeue(delay); }
    }
}
