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
public class ConsoleShutdownRegression {
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

    private static void consoleShutdown(RouterContext context) throws Exception {
        RouterConsoleRunner console = new RouterConsoleRunner(context, null, new String[0]);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        AtomicReference<Response> heldResponse = new AtomicReference<>();
        AtomicReference<Callback> heldCallback = new AtomicReference<>();
        server.setHandler(new GracefulHandler(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                heldResponse.set(response); heldCallback.set(callback); return true;
            }
        }));
        server.setStopTimeout(2000);
        Field field = RouterConsoleRunner.class.getDeclaredField("_server");
        field.setAccessible(true); field.set(console, server);
        try {
            server.start();
            LocalConnector.LocalEndPoint client = connector.executeRequest(wire("/", ""));
            await(() -> heldCallback.get() != null, "console request did not enter");
            long before = System.nanoTime();
            console.shutdown(null);
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500, "console shutdown returns while requests drain in background");
            await(() -> server.getDescendant(GracefulHandler.class).isShutdown(), "console did not initiate drain");
            check(console.getState() == ClientAppState.STOPPING, "console stays STOPPING while draining");
            check(connector.isShutdown(), "console shutdown stops admission of new connections");
            heldResponse.get().write(true, BufferUtil.toBuffer("done"), heldCallback.get());
            check(status(client.getResponse(false, 5, TimeUnit.SECONDS)) == 200, "console drain lets the current response finish");
            await(() -> console.getState() == ClientAppState.STOPPED, "console shutdown did not complete");
            check(server.isStopped(), "console reports STOPPED after the server stops");
        } finally {
            if (heldCallback.get() != null && !server.isStopped()) heldCallback.get().failed(new IOException("test cleanup"));
            server.stop(); server.destroy();
        }

        Server failing = new Server() {
            protected void doStop() throws Exception {
                super.doStop();
                throw new IOException("controlled shutdown failure");
            }
        };
        RouterConsoleRunner broken = new RouterConsoleRunner(context, null, new String[0]);
        field.set(broken, failing);
        try {
            failing.start();
            broken.shutdown(null);
            await(() -> broken.getState() == ClientAppState.CRASHED, "failed shutdown reported success");
            check(failing.isFailed(), "controlled server stop failure is retained");
            check(broken.getState() != ClientAppState.STOPPED, "failed console shutdown does not report a normal stop");
        } finally { failing.destroy(); }
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties(); properties.setProperty("router.enablePlugins", "false");
        RouterContext context = new RouterContext(null, properties);
        try { consoleShutdown(context); } finally { context.logManager().shutdown(); }
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
