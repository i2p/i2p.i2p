package org.klomp.snark;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppManagerImpl;
import net.i2p.app.ClientAppState;
import net.i2p.app.NotificationService;
import net.i2p.data.DataHelper;
import net.i2p.servlet.filters.XSSRequestWrapper;
import net.i2p.update.UpdateManager;
import net.i2p.update.UpdateType;
import net.i2p.util.SimpleTimer2;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.web.I2PSnarkServlet;

/** Independent integration regression; no network sockets or downloads. */
public final class SnarkLifecycleRegression extends SnarkIntegrationSupport {
    public static void main(String[] args) throws Exception {
        TestContext context = new TestContext(Files.createTempDirectory("snark-integration-"));
        context.logManager().setDefaultLimit("CRIT");
        try {
            updateLifecycle(context);
            registrationShutdownRace(context);
            System.out.println("SNARKLIFECYCLEREGRESSION_CHECKS=" + checks);
        } finally { context.logManager().shutdown(); }
    }
    private static void updateLifecycle(TestContext context) throws Exception {
        TestManager manager = new TestManager(context, "/i2psnark", "i2psnark");
        Properties config = (Properties) field(manager, "_config");
        config.setProperty(SnarkManager.PROP_AUTO_START, "true");
        config.setProperty(SnarkManager.PROP_STARTUP_DELAY, "10");
        check(manager.getState() == ClientAppState.INITIALIZED, "initial application state");
        manager.stop();
        check(manager.getState() == ClientAppState.INITIALIZED, "stop before start is harmless");
        try {
            manager.startup();
            check(manager.getState() == ClientAppState.RUNNING, "registered application reports RUNNING");
            check(context.apps.getRegisteredApp("i2psnark") == manager, "other apps can find the running manager");
            Object monitor = field(manager, "_monitor");
            SimpleTimer2.TimedEvent registration = (SimpleTimer2.TimedEvent) field(manager, "_updateRegister");
            manager.start();
            check(field(manager, "_monitor") == monitor && field(manager, "_updateRegister") == registration,
                    "duplicate start does not duplicate background work");
            registration.timeReached();
            check(field(manager, "_uhandler") == null, "missing update service is handled");
            UpdateService first = new UpdateService();
            context.apps.register(first.app);
            first.failRegistration = true;
            registration.timeReached();
            check(first.types.isEmpty() && field(manager, "_uhandler") == null,
                    "partial updater registration is rolled back");
            first.failRegistration = false;
            registration.timeReached();
            check(first.types.equals(Set.of(UpdateType.ROUTER_SIGNED, UpdateType.ROUTER_SIGNED_SU3)),
                    "late update service receives both torrent handlers");
            registration.timeReached();
            check(first.registrations == 4, "polling does not register duplicate handlers");
            context.apps.unregister(first.app);
            registration.timeReached();
            check(first.types.isEmpty(), "disabled update service is detached");
            UpdateService replacement = new UpdateService();
            context.apps.register(replacement.app);
            registration.timeReached();
            check(replacement.types.size() == 2, "replacement update service is attached");
            manager.shutdown(null);
            check(manager.getState() == ClientAppState.STOPPED && replacement.types.isEmpty(),
                    "shutdown unregisters updater and reports STOPPED");
            check(context.apps.getRegisteredApp("i2psnark") == null, "shutdown removes app registry entry");
            registration.timeReached();
            check(replacement.registrations == 2, "stale timer cannot register after shutdown");
            manager.start();
            registration.timeReached();
            check(replacement.registrations == 2, "old timer cannot register into a restarted manager");
            ((SimpleTimer2.TimedEvent) field(manager, "_updateRegister")).timeReached();
            check(replacement.registrations == 4, "restarted manager registers its own handlers");
            manager.stop();
            manager.stop();
            check(replacement.types.isEmpty(), "repeated stop is harmless");
            List<ClientAppState> cycle = Arrays.asList(ClientAppState.STARTING, ClientAppState.RUNNING,
                    ClientAppState.STOPPING, ClientAppState.STOPPED);
            check(context.apps.states.subList(0, 4).equals(cycle), "app registry receives ordered lifecycle transitions");
            context.apps.unregister(replacement.app);
        } finally {
            manager.stop();
        }
    }

    private static void registrationShutdownRace(TestContext context) throws Exception {
        TestManager manager = new TestManager(context, "/i2psnark", "i2psnark");
        Properties config = (Properties) field(manager, "_config");
        config.setProperty(SnarkManager.PROP_AUTO_START, "true");
        config.setProperty(SnarkManager.PROP_STARTUP_DELAY, "10");
        UpdateService service = new UpdateService();
        service.entered = new CountDownLatch(1);
        service.proceed = new CountDownLatch(1);
        context.apps.register(service.app);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread register = null;
        Thread stop = null;
        try {
            manager.start();
            SimpleTimer2.TimedEvent event = (SimpleTimer2.TimedEvent) field(manager, "_updateRegister");
            register = new Thread(() -> {
                try { event.timeReached(); }
                catch (Throwable error) { failed.set(error); }
            });
            register.start();
            check(service.entered.await(1, TimeUnit.SECONDS), "update registration is active during shutdown test");
            stop = new Thread(() -> {
                try { manager.stop(); }
                catch (Throwable error) { failed.set(error); }
            });
            stop.start();
            service.proceed.countDown();
            register.join(2000);
            stop.join(2000);
            check(!register.isAlive() && !stop.isAlive() && failed.get() == null,
                    "registration and shutdown finish without deadlock");
            check(service.types.isEmpty() && manager.getState() == ClientAppState.STOPPED,
                    "racing registration cannot leave a live updater after stop");
        } finally {
            service.proceed.countDown();
            if (register != null) register.join(2000);
            if (stop != null) stop.join(2000);
            manager.stop();
            context.apps.unregister(service.app);
        }
    }

}
