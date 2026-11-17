package net.i2p.i2ptunnel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.i2ptunnel.web.IndexBean;

/** Exercises the real group and background starter with controlled connection work. */
public class ParallelTunnelRestartRegression {
    private static int checks, failures;
    private static final Field STATE;
    static {
        try {
            STATE = TunnelController.class.getDeclaredField("_state");
            STATE.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static void check(boolean passed, String description) {
        checks++;
        if (!passed) {
            failures++;
            System.out.println("FAIL: " + description);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void state(TunnelController controller, String value) {
        try {
            STATE.set(controller, Enum.valueOf((Class) STATE.getType(), value));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static class Scenario {
        final List<ControlledTunnel> tunnels = new ArrayList<ControlledTunnel>();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started;
        final CountDownLatch finished;
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximum = new AtomicInteger();
        final AtomicInteger earlyStarts = new AtomicInteger();

        Scenario(TunnelControllerGroup group, int count) {
            started = new CountDownLatch(count);
            finished = new CountDownLatch(count);
            for (int i = 0; i < count; i++) {
                ControlledTunnel tunnel = new ControlledTunnel(this, i);
                tunnels.add(tunnel);
                group.addController(tunnel);
            }
        }

        int starts() {
            int count = 0;
            for (ControlledTunnel tunnel : tunnels) count += tunnel.starts.get();
            return count;
        }
    }

    private static class ControlledTunnel extends TunnelController {
        final Scenario scenario;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        ControlledTunnel(Scenario owner, int id) {
            super(config(id), "", false);
            scenario = owner;
            state(this, "RUNNING");
        }

        private static Properties config(int id) {
            Properties config = new Properties();
            config.setProperty(PROP_TYPE, TYPE_HTTP_CLIENT);
            config.setProperty(PROP_NAME, "controlled-" + id);
            config.setProperty(PROP_START, "false");
            config.setProperty(PROP_SHARED, "false");
            return config;
        }

        @Override public void stopTunnel() {
            stops.incrementAndGet();
            super.stopTunnel();
            state(this, "STOPPED");
        }

        @Override public void destroyTunnel() {
            super.destroyTunnel();
            state(this, "DESTROYED");
        }

        @Override public void startTunnel() {
            starts.incrementAndGet();
            state(this, "RUNNING");
            for (ControlledTunnel tunnel : scenario.tunnels) {
                if (tunnel.stops.get() == 0) scenario.earlyStarts.incrementAndGet();
            }
            int count = scenario.active.incrementAndGet();
            scenario.maximum.accumulateAndGet(count, Math::max);
            scenario.started.countDown();
            try {
                if (!scenario.release.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("test connection gate timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                scenario.active.decrementAndGet();
                scenario.finished.countDown();
            }
        }
    }

    private static void reset(TunnelControllerGroup group, Path config) {
        group.unloadControllers();
        group.loadControllers(config.toFile());
    }

    private static boolean parallel(TunnelControllerGroup group, Path config) throws Exception {
        reset(group, config);
        Scenario scenario = new Scenario(group, 4);
        FutureTask<List<String>> request = new FutureTask<List<String>>(group::restartAllControllers);
        Thread thread = new Thread(request, "controlled restart request");
        thread.setDaemon(true);
        long before = System.nanoTime();
        thread.start();
        boolean returned = false;
        try {
            request.get(300, TimeUnit.MILLISECONDS);
            returned = true;
        } catch (java.util.concurrent.TimeoutException expected) {}
        long requestMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        check(returned, "restart request returns while connection work is blocked");
        FutureTask<Integer> listing = new FutureTask<Integer>(() -> group.getControllers().size());
        Thread reader = new Thread(listing, "controlled status request");
        reader.setDaemon(true);
        reader.start();
        boolean readable = false;
        try { readable = listing.get(300, TimeUnit.MILLISECONDS) == 4; }
        catch (java.util.concurrent.TimeoutException expected) {}
        check(readable, "status remains readable during blocked starts");
        boolean allStarted = scenario.started.await(1200, TimeUnit.MILLISECONDS);
        check(allStarted, "one slow tunnel does not prevent other tunnels from starting");
        check(scenario.maximum.get() == 4, "independent connection work overlaps");
        check(scenario.earlyStarts.get() == 0, "all old tunnels stop before any new start");
        System.out.println("RESTART_REQUEST_MS=" + requestMillis + "; MAX_CONCURRENT=" + scenario.maximum.get());
        scenario.release.countDown();
        request.get(5, TimeUnit.SECONDS);
        check(scenario.finished.await(3, TimeUnit.SECONDS), "all controlled starts finish");
        return returned && allStarted;
    }

    private static void cancellations(TunnelControllerGroup group, Path config) throws Exception {
        for (String action : new String[] { "stop", "unload", "restart", "remove", "start", "single-stop", "single-destroy" }) {
            reset(group, config);
            Scenario scenario = new Scenario(group, 2);
            try {
                group.restartAllControllers();
                if (action.equals("stop")) group.stopAllControllers();
                else if (action.equals("unload")) group.unloadControllers();
                else if (action.equals("restart")) group.restartAllControllers();
                else if (action.equals("remove")) group.removeController(scenario.tunnels.get(0));
                else if (action.equals("single-stop")) scenario.tunnels.get(0).stopTunnel();
                else if (action.equals("single-destroy")) scenario.tunnels.get(0).destroyTunnel();
                else group.startAllControllers();
                Thread.sleep(800);
                int expected = action.equals("stop") || action.equals("unload") ? 0 :
                               action.equals("remove") || action.startsWith("single-") ? 1 : 2;
                check(scenario.starts() == expected, action + " supersedes the pending restart without duplicate starts");
                if (action.equals("remove") || action.startsWith("single-"))
                    check(scenario.tunnels.get(0).starts.get() == 0, action + " tunnel never starts");
            } finally { scenario.release.countDown(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scenario.active.get() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
            check(scenario.active.get() == 0, action + " leaves no controlled work running");
        }
    }

    public static void main(String[] args) throws Exception {
        Path config = Path.of("empty-tunnels.config").toAbsolutePath();
        Files.writeString(config, "# controlled offline fixtures\nfixture=true\n");
        Properties properties = new Properties();
        properties.setProperty("i2p.dir.config", config.getParent().toString());
        properties.setProperty("i2p.dir.base", config.getParent().toString());
        I2PAppContext context = new I2PAppContext(properties);
        TunnelControllerGroup group = new TunnelControllerGroup(context, null, new String[] { config.toString() });
        try {
            if (parallel(group, config)) cancellations(group, config);
        } finally { group.unloadControllers(); }
        System.out.println("TUNNEL_RESTART_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
