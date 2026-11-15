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

/** Shared offline service doubles for independent integration regressions. */
abstract class SnarkIntegrationSupport {
    protected static int checks;
    protected static final class TestContext extends I2PAppContext {
        final Path work;
        final AppManager apps = new AppManager(this);
        TestContext(Path work) {
            super(false, properties(work));
            this.work = work;
        }
        @Override public boolean isRouterContext() { return true; }
        @Override public ClientAppManager clientAppManager() { return apps; }
    }

    protected static Properties properties(Path work) {
        Properties props = new Properties();
        props.setProperty("i2p.dir.config", work.toString());
        props.setProperty("i2p.dir.base", work.toString());
        props.setProperty("routerconsole.lang", "en");
        return props;
    }

    protected static final class AppManager extends ClientAppManagerImpl {
        final List<ClientAppState> states = new ArrayList<>();
        int bubbles;
        AppManager(I2PAppContext context) { super(context); }
        @Override public void notify(ClientApp app, ClientAppState state, String message, Exception error) { states.add(state); }
        @Override public void addBubble(String service, String message) { bubbles++; }
    }

    protected static final class TestManager extends SnarkManager {
        int statusWrites;
        TestManager(I2PAppContext context, String path, String name) { super(context, path, name); }
        @Override public void updateStatus(Snark snark) { statusWrites++; }
    }

    protected static final class UpdateService {
        final Set<UpdateType> types = new HashSet<>();
        int registrations;
        boolean failRegistration;
        CountDownLatch entered;
        CountDownLatch proceed;
        final ClientApp app = (ClientApp) Proxy.newProxyInstance(ClientApp.class.getClassLoader(),
                new Class<?>[] {ClientApp.class, UpdateManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return UpdateManager.APP_NAME;
                    if (method.getName().equals("register")) {
                        registrations++;
                        if (entered != null) entered.countDown();
                        if (proceed != null && !proceed.await(2, TimeUnit.SECONDS))
                            throw new AssertionError("registration test gate timed out");
                        if (failRegistration && args[1] == UpdateType.ROUTER_SIGNED_SU3)
                            throw new IllegalStateException("controlled registration failure");
                        types.add((UpdateType) args[1]);
                        return null;
                    }
                    if (method.getName().equals("unregister")) { types.remove(args[1]); return null; }
                    return defaultValue(proxy, method, args);
                });
    }

    protected static final class StubStorage extends Storage {
        boolean finished;
        StubStorage(I2PSnarkUtil util, File file, MetaInfo meta) { super(util, file, meta, null, true); }
        @Override public boolean complete() { return finished; }
    }

    protected static final class StubSnark extends Snark {
        final Storage storage;
        StubSnark(I2PSnarkUtil util, Storage storage) {
            super(util, "test", new byte[20], null, null, null, null, null);
            this.storage = storage;
        }
        @Override public MetaInfo getMetaInfo() { return storage.getMetaInfo(); }
        @Override public Storage getStorage() { return storage; }
        @Override public String getBaseName() { return storage.getBaseName(); }
        @Override public long getDownloaded() { return 1; }
    }

    protected static MetaInfo meta(boolean multi, String name) throws Exception {
        Map<String, BEValue> info = new HashMap<>();
        info.put("name", new BEValue(DataHelper.getUTF8(name)));
        info.put("piece length", new BEValue(16384));
        info.put("pieces", new BEValue(new byte[20]));
        if (multi) {
            Map<String, BEValue> file = new HashMap<>();
            file.put("length", new BEValue(1));
            file.put("path", new BEValue(List.of(new BEValue(new byte[] {'x'}))));
            info.put("files", new BEValue(List.of(new BEValue(file))));
        } else {
            info.put("length", new BEValue(1));
        }
        return new MetaInfo(Map.of("info", new BEValue(info)));
    }

    protected static HttpServletRequest request(String method, Map<String, String> parameters) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, called, args) -> {
                    if (called.getName().equals("getMethod")) return method;
                    if (called.getName().equals("getParameter")) return parameters.get(args[0]);
                    return defaultValue(proxy, called, args);
                });
    }

    protected static Object defaultValue(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("equals")) return proxy == args[0];
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("toString")) return "Controlled app service";
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == int.class) return 0;
        if (method.getReturnType() == long.class) return 0L;
        return null;
    }

    protected static Field findField(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }

    protected static Object field(Object object, String name) throws Exception { return findField(object, name).get(object); }
    protected static void set(Object object, String name, Object value) throws Exception { findField(object, name).set(object, value); }
    protected static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
