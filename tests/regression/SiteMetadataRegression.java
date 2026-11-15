package net.i2p.jetty;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppState;
import net.i2p.router.RouterContext;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.servlet.filters.XI2PLocationFilter;

/** Controlled slow I/O and HTTP lifecycle checks, without network sockets. */
public class SiteMetadataRegression {
    private static int checks, failures;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + message); }
    }

    private static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError(label);
    }

    private static int status(String response) { return Integer.parseInt(response.substring(9, 12)); }
    private static String wire(String path, String headers) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost:7658\r\nConnection: close\r\n" + headers + "\r\n";
    }

    private static Handler successful() {
        return new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                response.write(true, BufferUtil.toBuffer("ok"), callback);
                return true;
            }
        };
    }

    private static class SlowLocation extends XI2PLocationFilter {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        SlowLocation(I2PAppContext context) { super(context); }
        protected String getXI2PLocation(String host, String port) {
            calls.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return "example.i2p";
        }
    }

    private static void metadata(I2PAppContext context) throws Exception {
        SlowLocation location = new SlowLocation(context);
        location.setHandler(successful());
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(location);
        try {
            server.start();
            long before = System.nanoTime();
            String first = connector.getResponse(wire("/path?x=1", ""));
            check(status(first) == 200 && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500, "slow metadata I/O does not delay HTTP");
            check(location.entered.await(2, TimeUnit.SECONDS), "metadata lookup runs in background");
            check(!connector.getResponse(wire("/path?x=1", "")).contains("X-I2P-Location"), "response proceeds while lookup remains incomplete");
            check(location.calls.get() == 1, "simultaneous metadata refreshes coalesce");
            location.release.countDown();
            AtomicReference<String> loaded = new AtomicReference<>();
            await(() -> {
                try { loaded.set(connector.getResponse(wire("/path?x=1", ""))); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                return loaded.get().contains("X-I2P-Location");
            }, "metadata did not publish");
            check(loaded.get().contains("X-I2P-Location: http://example.i2p/path?x=1"), "I2P location preserves path and query without leaking backend port");
            check(!connector.getResponse(wire("/", "X-I2P-DestHash: tunnel-client\r\n")).contains("X-I2P-Location"), "tunnel requests do not receive direct-access advertisements");
            server.stop(); server.start();
            check(status(connector.getResponse(wire("/", ""))) == 200, "metadata worker lifecycle supports restart");
        } finally { location.release.countDown(); server.stop(); server.destroy(); }

        Path config = Path.of("memory-jetty.xml").toAbsolutePath();
        Files.writeString(config, "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://jetty.org/configure_10_0.dtd\">" +
                "<Configure class=\"org.eclipse.jetty.server.Server\"><Set name=\"handler\"><New class=\"org.eclipse.jetty.server.handler.DefaultHandler\"/></Set></Configure>");
        JettyStart app = new JettyStart(null, null, new String[] {config.toString()});
        Field servers = JettyStart.class.getDeclaredField("_jettys");
        servers.setAccessible(true);
        try {
            for (int i = 0; i < 2; i++) {
                app.startup();
                await(() -> app.getState() == ClientAppState.RUNNING, "Jetty lifecycle did not start");
                Server managed = (Server) ((List<?>) servers.get(app)).get(0);
                check(managed.getDescendants(XI2PLocationFilter.class).size() == 1, "managed restart installs exactly one metadata filter");
                app.shutdown(null);
                await(() -> app.getState() == ClientAppState.STOPPED, "Jetty lifecycle did not stop");
            }
        } finally { if (app.getState() == ClientAppState.RUNNING) app.shutdown(null); }
    }

    public static void main(String[] args) throws Exception {
        I2PAppContext context = new I2PAppContext();
        try { metadata(context); } finally { context.logManager().shutdown(); }
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
