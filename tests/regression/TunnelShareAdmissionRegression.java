package net.i2p.router;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Properties;

import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.RandomSource;

/** Exercises the actual bandwidth admission calculation with controlled rates. */
public final class TunnelShareAdmissionRegression {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        System.setProperty("I2P_DISABLE_OUTPUT_OVERRIDE", "true");
        QuietContext ctx = new QuietContext();
        ctx.logManager().setDefaultLimit("CRIT");
        RouterThrottleImpl throttle = new RouterThrottleImpl(ctx);
        Method allow = RouterThrottleImpl.class.getDeclaredMethod("allowTunnel", double.class, int.class);
        allow.setAccessible(true);
        ProbeRouter router = (ProbeRouter) ctx.router();
        try {
            for (double share : new double[] {1, .8, .2, .1, .05, .01}) {
                router.share = share;
                for (float random : new float[] {0, .1f, .5f, .99f}) {
                    ctx.choice.value = random;
                    router.used = router.minute = 0;
                    check((boolean) allow.invoke(throttle, 0d, 0), "idle shared capacity: " + share + "/" + random);
                    router.used = (int) (1024 * 1024 * share / 2);
                    check((boolean) allow.invoke(throttle, router.used * 600d, 10), "half shared capacity: " + share + "/" + random);
                    router.used = (int) Math.ceil(1024 * 1024 * share);
                    check(!(boolean) allow.invoke(throttle, router.used * 600d, 10), "exhausted shared capacity: " + share);
                }
            }
            for (float random : new float[] {0, .5f, .99f}) {
                ctx.choice.value = random;
                router.share = 0;
                router.used = router.minute = 0;
                check(!(boolean) allow.invoke(throttle, 0d, 0), "disabled sharing remains disabled");
                router.share = .1;
                router.used = (int) (1024 * 1024 * .9) - 4096 + 1;
                check(!(boolean) allow.invoke(throttle, 0d, 0), "local-client load retains total headroom");
                router.used = 0;
                router.minute = 2 * 1024 * 1024;
                check(!(boolean) allow.invoke(throttle, 0d, 0), "minute-rate overload still rejects");
            }
            router.minute = 0;
            router.share = .1;
            router.used = (int) (1024 * 1024 * router.share * .95);
            ctx.choice.value = 0;
            check(!(boolean) allow.invoke(throttle, router.used * 600d, 10), "near-full share retains probabilistic rejection");
            ctx.choice.value = .99f;
            check((boolean) allow.invoke(throttle, router.used * 600d, 10), "near-full share retains probabilistic acceptance");
            System.out.println("TUNNEL_SHARE_ADMISSION_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("shared bandwidth admission");
        } finally {
            ctx.bandwidthLimiter().shutdown();
            ctx.simpleTimer2().stop();
            ctx.logManager().shutdown();
        }
    }

    private static void check(boolean ok, String name) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + name); } }
    private static Properties properties() {
        Properties p = new Properties();
        String directory = new File(".").getAbsolutePath();
        p.setProperty("i2p.dir.base", directory); p.setProperty("i2p.dir.config", directory);
        p.setProperty("time.disabled", "true");
        p.setProperty("i2np.bandwidth.inboundKBytesPerSecond", "1024");
        p.setProperty("i2np.bandwidth.outboundKBytesPerSecond", "1024");
        return p;
    }
    private static final class ProbeRouter extends Router {
        double share;
        int used;
        int minute;
        ProbeRouter() { super("share-admission.config", properties()); }
        @Override public double getSharePercentage() { return share; }
        @Override public int get1sRateIn() { return used; }
        @Override public int get15sRateIn() { return used; }
        @Override public int get1sRate(boolean outboundOnly) { return used; }
        @Override public int get15sRate(boolean outboundOnly) { return used; }
        @Override public int get1mRateIn() { return minute; }
        @Override public int get1mRate(boolean outboundOnly) { return minute; }
    }
    private static final class Choice extends RandomSource {
        private static final long serialVersionUID = 1L;
        float value;
        Choice(RouterContext ctx) { super(ctx); }
        @Override public float nextFloat() { return value; }
    }
    private static final class QuietContext extends RouterContext {
        private FIFOBandwidthLimiter limiter;
        final Choice choice = new Choice(this);
        QuietContext() { super(new ProbeRouter(), properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
        @Override public RandomSource random() { return choice == null ? super.random() : choice; }
        @Override public synchronized FIFOBandwidthLimiter bandwidthLimiter() {
            if (limiter == null) limiter = new FIFOBandwidthLimiter(this);
            return limiter;
        }
    }
}
