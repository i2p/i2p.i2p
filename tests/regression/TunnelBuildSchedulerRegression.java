package net.i2p.router.tunnel.pool;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.JobQueue;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.SystemVersion;

/** Real executor passes; only peer selection and network sends are replaced. */
public final class TunnelBuildSchedulerRegression {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        System.setProperty("I2P_DISABLE_OUTPUT_OVERRIDE", "true");
        QuietContext ctx = new QuietContext();
        ctx.logManager().setDefaultLimit("CRIT");
        ProbeManager manager = new ProbeManager(ctx);
        ctx.manager = manager;
        try {
            runPass(ctx, manager, 0, 20, 0, 0, 13, "fast requests fill available slots");
            runPass(ctx, manager, 11, 20, 0, 0, 2, "existing requests count toward limit");
            runPass(ctx, manager, 12, 20, 0, 0, 1, "last available slot");
            runPass(ctx, manager, 13, 20, 0, 0, 0, "full capacity waits");
            runPass(ctx, manager, 0, 3, 0, 0, 3, "do not build beyond demand");
            runPass(ctx, manager, 0, 20, (SystemVersion.isSlow() ? 500 : 400) + 20, 0, 2, "slow requests keep short batches");
            runPass(ctx, manager, 0, 20, 0, 3, 13, "failed configuration does not consume a slot");
            ProbeExecutor failedExec = new ProbeExecutor(ctx, manager, 0);
            ProbePool failedPool = new ProbePool(ctx, manager, 6, 6);
            failedPool.configureDelay = (SystemVersion.isSlow() ? 500 : 400) + 20;
            manager.pool = failedPool; manager.passes = 0;
            try { failedExec.run(); } catch (EndPass expected) { } finally { failedExec.shutdown(); }
            check(failedPool.configureAttempts == 2, "slow failed configurations obey the batch budget");
            ctx.comm.disconnected = true;
            runPass(ctx, manager, 0, 20, 0, 0, 0, "disconnected router does not build");
            ctx.comm.disconnected = false;

            ProbeExecutor exec = new ProbeExecutor(ctx, manager, 0);
            ProbePool pool = new ProbePool(ctx, manager, 1, 0);
            PooledTunnelCreatorConfig cfg = pool.config();
            cfg.setExpiration(ctx.clock().now() + 600000 - 1000);
            exec.buildComplete(cfg, BuildExecutor.Result.OTHER_FAILURE);
            Field repoll = field(BuildExecutor.class, "_repoll");
            check(repoll.getBoolean(exec), "completion before wait leaves a persistent wakeup");
            repoll.setBoolean(exec, false);
            cfg.setExpiration(ctx.clock().now() + 600000);
            exec.buildComplete(cfg, BuildExecutor.Result.OTHER_FAILURE);
            check(!repoll.getBoolean(exec), "immediate failure preserves CPU backoff");
            exec.shutdown();

            System.out.println("TUNNEL_BUILD_SCHEDULER_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("tunnel scheduling failure");
        } finally {
            ctx.bandwidthLimiter().shutdown();
            ctx.simpleTimer2().stop();
            ctx.logManager().shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static void runPass(QuietContext ctx, ProbeManager manager, int alreadyBuilding, int wanted,
                                int delay, int nullConfigs, int expected, String name) throws Exception {
        ProbeExecutor exec = new ProbeExecutor(ctx, manager, delay);
        ProbePool pool = new ProbePool(ctx, manager, wanted, nullConfigs);
        manager.pool = pool;
        manager.passes = 0;
        ConcurrentHashMap<Long, PooledTunnelCreatorConfig> pending = (ConcurrentHashMap<Long, PooledTunnelCreatorConfig>)
                field(BuildExecutor.class, "_currentlyBuildingMap").get(exec);
        for (int i = 0; i < alreadyBuilding; i++) pending.put((long) i + 1, pool.config());
        long start = System.nanoTime();
        try {
            exec.run();
            throw new AssertionError("executor did not reach a second pass");
        } catch (EndPass expectedEnd) {
            // Stop after one real scheduling pass, before rebuilding the demand list.
        } finally {
            exec.shutdown();
        }
        long elapsed = (System.nanoTime() - start) / 1000000;
        check(exec.built == expected, name + " (expected " + expected + ", got " + exec.built + ")");
        check(alreadyBuilding + exec.built <= 13, "concurrency bound: " + name);
        System.out.println("SCHEDULER_SAMPLE name=" + name + "; built=" + exec.built + "; elapsed_ms=" + elapsed);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void check(boolean ok, String name) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + name); } }
    private static final class EndPass extends Error { private static final long serialVersionUID = 1L; }

    private static final class ProbeExecutor extends BuildExecutor {
        private final int delay;
        int built;
        ProbeExecutor(RouterContext ctx, TunnelPoolManager manager, int delay) { super(ctx, manager); this.delay = delay; }
        @Override void buildTunnel(PooledTunnelCreatorConfig cfg) {
            built++;
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }
    }
    private static final class ProbeManager extends TunnelPoolManager {
        ProbePool pool;
        int passes;
        ProbeManager(RouterContext ctx) { super(ctx); }
        @Override public void listPools(List<TunnelPool> pools) { if (passes++ > 0) throw new EndPass(); pools.add(pool); }
        @Override public int getFreeTunnelCount() { return 1; }
        @Override public int getOutboundTunnelCount() { return 1; }
    }
    private static final class ProbePool extends TunnelPool {
        private final int wanted;
        private int nullConfigs;
        int configureDelay;
        int configureAttempts;
        ProbePool(RouterContext ctx, TunnelPoolManager manager, int wanted, int nullConfigs) {
            super(ctx, manager, new TunnelPoolSettings(new Hash(new byte[32]), false), null);
            getSettings().setLength(2);
            this.wanted = wanted;
            this.nullConfigs = nullConfigs;
            _alive = true;
        }
        @Override int countHowManyToBuild() { return wanted; }
        @Override public boolean isAlive() { return true; }
        @Override PooledTunnelCreatorConfig configureNewTunnel() {
            configureAttempts++;
            if (configureDelay > 0) {
                try { Thread.sleep(configureDelay); } catch (InterruptedException e) { throw new AssertionError(e); }
            }
            if (nullConfigs-- > 0) return null;
            return config();
        }
        PooledTunnelCreatorConfig config() {
            PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_context, 3, false, getSettings().getDestination(), this);
            cfg.setExpiration(_context.clock().now() + 600000);
            return cfg;
        }
    }
    private static Properties properties() {
        Properties result = new Properties();
        String directory = new File(".").getAbsolutePath();
        result.setProperty("i2p.dir.base", directory);
        result.setProperty("i2p.dir.config", directory);
        result.setProperty("time.disabled", "true");
        result.setProperty("i2np.allowLocal", "true");
        result.setProperty("i2np.bandwidth.outboundKBytesPerSecond", "1024");
        result.setProperty("i2np.bandwidth.inboundKBytesPerSecond", "1024");
        return result;
    }
    private static final class QuietContext extends RouterContext {
        private FIFOBandwidthLimiter limiter;
        private JobQueue jobs;
        private InNetMessagePool inbound;
        final ProbeComm comm = new ProbeComm();
        ProbeManager manager;
        QuietContext() { super(new Router("scheduler-test.config", properties()), properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
        @Override public synchronized FIFOBandwidthLimiter bandwidthLimiter() { if (limiter == null) limiter = new FIFOBandwidthLimiter(this); return limiter; }
        @Override public synchronized JobQueue jobQueue() { if (jobs == null) jobs = new JobQueue(this); return jobs; }
        @Override public synchronized InNetMessagePool inNetMessagePool() { if (inbound == null) inbound = new InNetMessagePool(this); return inbound; }
        @Override public CommSystemFacade commSystem() { return comm; }
        @Override public TunnelManagerFacade tunnelManager() { return manager; }
    }
    private static final class ProbeComm extends CommSystemFacade {
        boolean disconnected;
        @Override public Status getStatus() { return disconnected ? Status.DISCONNECTED : Status.OK; }
        public void processMessage(OutNetMessage message) { throw new AssertionError("network access"); }
        public int countActivePeers() { return 1; }
        public int countActiveSendPeers() { return 1; }
        public boolean isEstablished(Hash peer) { return true; }
        public List<Hash> getEstablished() { return Collections.emptyList(); }
        public void startup() { throw new AssertionError("network startup"); }
        public void restart() { throw new AssertionError("network restart"); }
        public void shutdown() { }
    }
}
