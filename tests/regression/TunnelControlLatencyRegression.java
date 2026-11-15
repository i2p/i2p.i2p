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
public class TunnelControlLatencyRegression {
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

    private static void webLatency(TunnelControllerGroup group, Path config) throws Exception {
        reset(group, config);
        Scenario scenario = new Scenario(group, 1);
        scenario.release.countDown();
        state(scenario.tunnels.get(0), "STOPPED");
        IndexBean bean = new IndexBean();
        bean.setTunnel("0");
        for (String operation : new String[] { "start", "stop" }) {
            Method method = IndexBean.class.getDeclaredMethod(operation);
            method.setAccessible(true);
            long before = System.nanoTime();
            String message = (String) method.invoke(bean);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            check(elapsed < 500, operation + " has no artificial one-second response delay");
            check(message.contains("controlled-0"), operation + " still returns an immediate tunnel message");
            System.out.println("WEB_" + operation.toUpperCase(java.util.Locale.US) + "_MS=" + elapsed);
            scenario.finished.await(2, TimeUnit.SECONDS);
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
            webLatency(group, config);
        } finally { group.unloadControllers(); }
        System.out.println("TUNNEL_RESTART_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
