package net.i2p.router.tunnel.pool;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ProfileManager;
import net.i2p.router.Router;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.dummy.DummyNetworkDatabaseFacade;
import net.i2p.router.peermanager.ProfileManagerImpl;
import net.i2p.router.transport.FIFOBandwidthLimiter;

/** Deterministically delivers replies while the timeout iterator is in flight. */
public final class TunnelBuildAccountingRegression {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        System.setProperty("I2P_DISABLE_OUTPUT_OVERRIDE", "true");
        QuietContext ctx = new QuietContext();
        ctx.logManager().setDefaultLimit("CRIT");
        Method allowed = BuildExecutor.class.getDeclaredMethod("allowed"); allowed.setAccessible(true);
        try {
            for (boolean inbound : new boolean[] {false, true}) {
                ProbePool pool = new ProbePool(ctx, inbound);
                BuildExecutor exec = new BuildExecutor(ctx, null);
                RacingMap pending = new RacingMap();
                field("_currentlyBuildingMap").set(exec, pending);
                PooledTunnelCreatorConfig first = config(ctx, pool, 41, true);
                pending.put(41L, first);
                pending.beforeReturn = () -> check(exec.removeFromBuilding(41) == first, "reply claims pending build");
                int blamed = ctx.blamed;
                allowed.invoke(exec);
                check(pool.timeouts == 0, "reply already claimed: no phantom timeout, inbound=" + inbound);
                check(ctx.blamed == blamed, "reply already claimed: no peer penalty");
                check(exec.removeFromBuilding(41) == null, "completed build is not resurrected as late");

                pool.timeouts = 0;
                PooledTunnelCreatorConfig old = config(ctx, pool, 42, true);
                PooledTunnelCreatorConfig replacement = config(ctx, pool, 42, false);
                pending.put(42L, old);
                pending.beforeReturn = () -> { pending.remove(42L); pending.put(42L, replacement); };
                allowed.invoke(exec);
                check(pool.timeouts == 0, "stale iterator does not time out a replaced entry");
                check(exec.removeFromBuilding(42) == replacement, "replacement keeps its pending slot");
                check(exec.removeFromBuilding(42) == null, "stale build is not resurrected");

                pool.timeouts = 0;
                PooledTunnelCreatorConfig expired = config(ctx, pool, 43, true);
                pending.put(43L, expired);
                allowed.invoke(exec);
                check(pool.timeouts == 1, "genuinely expired build times out once");
                allowed.invoke(exec);
                check(pool.timeouts == 1, "repeated poll does not duplicate timeout");
                check(exec.removeFromBuilding(43) == expired, "late reply remains usable during grace period");
                check(exec.removeFromBuilding(43) == null, "late reply is claimed once");
                for (int attempt = 0; attempt < 32; attempt++) {
                    long id = 1000 + attempt;
                    PooledTunnelCreatorConfig concurrent = config(ctx, pool, id, true);
                    pending.put(id, concurrent);
                    pool.timeouts = 0;
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicReference<PooledTunnelCreatorConfig> claimed = new AtomicReference<>();
                    AtomicReference<Throwable> error = new AtomicReference<>();
                    Thread expire = new Thread(() -> {
                        try { start.await(); allowed.invoke(exec); } catch (Throwable failure) { error.set(failure); }
                    });
                    Thread reply = new Thread(() -> {
                        try { start.await(); claimed.set(exec.removeFromBuilding(id)); } catch (Throwable failure) { error.set(failure); }
                    });
                    expire.start(); reply.start(); start.countDown();
                    expire.join(5000); reply.join(5000);
                    check(error.get() == null && !expire.isAlive() && !reply.isAlive(), "concurrent accounting completes");
                    check(claimed.get() == concurrent, "reply survives concurrent timeout transition");
                    check(exec.removeFromBuilding(id) == null, "concurrent reply is claimed exactly once");
                }
                exec.shutdown();
            }
            System.out.println("TUNNEL_BUILD_ACCOUNTING_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("tunnel timeout accounting");
        } finally {
            ctx.bandwidthLimiter().shutdown(); ctx.simpleTimer2().stop(); ctx.logManager().shutdown();
        }
    }

    private static Field field(String name) throws Exception { Field f = BuildExecutor.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void check(boolean ok, String name) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + name); } }
    private static PooledTunnelCreatorConfig config(QuietContext ctx, ProbePool pool, long id, boolean expired) {
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(ctx, 3, pool.getSettings().isInbound(), new Hash(new byte[32]), pool);
        cfg.setPeer(0, ctx.routerHash()); cfg.setPeer(1, new Hash(new byte[32])); cfg.setPeer(2, new Hash(new byte[32]));
        cfg.setExpiration(ctx.clock().now() + 600000 - (expired ? BuildRequestor.REQUEST_TIMEOUT + 1 : 0));
        cfg.setReplyMessageId(id);
        return cfg;
    }
    private static final class RacingMap extends ConcurrentHashMap<Long, PooledTunnelCreatorConfig> {
        private static final long serialVersionUID = 1L;
        Runnable beforeReturn;
        @Override public Collection<PooledTunnelCreatorConfig> values() {
            Collection<PooledTunnelCreatorConfig> values = super.values();
            return new AbstractCollection<PooledTunnelCreatorConfig>() {
                public int size() { return values.size(); }
                public Iterator<PooledTunnelCreatorConfig> iterator() {
                    Iterator<PooledTunnelCreatorConfig> iter = values.iterator();
                    return new Iterator<PooledTunnelCreatorConfig>() {
                        public boolean hasNext() { return iter.hasNext(); }
                        public PooledTunnelCreatorConfig next() {
                            PooledTunnelCreatorConfig result = iter.next();
                            Runnable action = beforeReturn; beforeReturn = null;
                            if (action != null) action.run();
                            return result;
                        }
                        public void remove() { iter.remove(); }
                    };
                }
            };
        }
    }
    private static final class ProbePool extends TunnelPool {
        int timeouts;
        ProbePool(RouterContext ctx, boolean inbound) { super(ctx, null, new TunnelPoolSettings(new Hash(new byte[32]), inbound), null); }
        @Override void buildComplete(PooledTunnelCreatorConfig cfg, BuildExecutor.Result result) { if (result == BuildExecutor.Result.TIMEOUT) timeouts++; }
    }
    private static Properties properties() {
        Properties p = new Properties(); String d = new File(".").getAbsolutePath();
        p.setProperty("i2p.dir.base", d); p.setProperty("i2p.dir.config", d); p.setProperty("time.disabled", "true");
        p.setProperty("i2np.bandwidth.outboundKBytesPerSecond", "1024"); p.setProperty("i2np.bandwidth.inboundKBytesPerSecond", "1024");
        return p;
    }
    private static final class QuietContext extends RouterContext {
        private FIFOBandwidthLimiter limiter;
        private JobQueue jobs;
        private NetworkDatabaseFacade db;
        private ProfileManager profiles;
        int blamed;
        QuietContext() { super(new Router("build-accounting.config", properties()), properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
        @Override public Hash routerHash() { byte[] b = new byte[32]; b[0] = 1; return new Hash(b); }
        @Override public CommSystemFacade commSystem() {
            return new CommSystemFacade() {
                public Status getStatus() { return Status.OK; }
                public int countActivePeers() { return 1; }
                public int countActiveSendPeers() { return 1; }
                public boolean isEstablished(Hash peer) { return true; }
                public List<Hash> getEstablished() { return Collections.emptyList(); }
                public void processMessage(OutNetMessage msg) { throw new AssertionError("network send"); }
                public void startup() { throw new AssertionError("network startup"); }
                public void restart() { throw new AssertionError("network restart"); }
                public void shutdown() { }
            };
        }
        @Override public synchronized FIFOBandwidthLimiter bandwidthLimiter() { if (limiter == null) limiter = new FIFOBandwidthLimiter(this); return limiter; }
        @Override public synchronized JobQueue jobQueue() { if (jobs == null) jobs = new JobQueue(this); return jobs; }
        @Override public synchronized NetworkDatabaseFacade netDb() { if (db == null) db = new DummyNetworkDatabaseFacade(this); return db; }
        @Override public synchronized ProfileManager profileManager() { if (profiles == null) profiles = new ProfileManagerImpl(this) { public void tunnelTimedOut(Hash peer) { blamed++; } }; return profiles; }
    }
}
