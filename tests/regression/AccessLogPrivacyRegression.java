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
public class AccessLogPrivacyRegression {
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

    private static void privacy() throws Exception {
        Files.createDirectories(Path.of("logs"));
        I2PRequestLog log = new I2PRequestLog("logs/requests.log");
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.addBean(log);
        server.setHandler(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                HttpFields fields = HttpFields.build(request.getHeaders())
                    .put("User-Agent", "agent\"\\forged\u2028line").put("X-I2P-DestB32", "site\nforged").asImmutable();
                Request logged = new Request.Wrapper(request) {
                    public HttpFields getHeaders() { return fields; }
                    public HttpURI getHttpURI() { return HttpURI.from("http://user:URI_PASSWORD_PRIVATE_MARKER@localhost/path"); }
                };
                log.log(logged, response);
                callback.succeeded();
                return true;
            }
        });
        try { server.start(); connector.getResponse(wire("/", "")); }
        finally { server.stop(); server.destroy(); }
        String line = Files.readString(Path.of("logs/requests.log"));
        check(!line.contains("URI_PASSWORD_PRIVATE_MARKER"), "URI credentials never enter access logs");
        check(line.lines().count() == 1 && !line.contains("\u2028"), "request fields cannot forge extra log records");
        check(line.contains("agent\\\"\\\\forged_line"), "quoted log fields escape quotes and backslashes");
    }
    public static void main(String[] args) throws Exception {
        privacy();
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
