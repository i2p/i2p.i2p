package net.i2p.jetty;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.xml.XmlConfiguration;

import net.i2p.I2PAppContext;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.PortMapper;

/** Real HTTP and lifecycle regressions; no network sockets are opened. */
public class RequestAdmissionRegression {
    private static int checks, failures;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(5);
        if (!condition.getAsBoolean())
            throw new AssertionError(message);
    }

    private static String wire(String method, String host, String extra) {
        return method + " / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n" +
               extra + "Content-Length: 0\r\n\r\n";
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring(9, 12));
    }

    private static void originCase(LocalConnector connector, String method, String host,
                                   String headers, int expected, String label) throws Exception {
        int actual = status(connector.getResponse(wire(method, host, headers), 5, TimeUnit.SECONDS));
        check(actual == expected, label + " (" + method + ", status " + actual + ")");
    }

    private static String wire(String path, String headers) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost:7658\r\nConnection: close\r\n" + headers + "\r\n";
    }

    private static XmlConfiguration xml(Path root, String file) throws Exception {
        return new XmlConfiguration(ResourceFactory.root().newResource(root.resolve("installer/resources/eepsite/" + file)));
    }

    private static void workerBurst(QueuedThreadPool pool) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        int tasks = pool.getMaxThreads() + 128;
        CountDownLatch done = new CountDownLatch(tasks);
        boolean accepted = true;
        pool.start();
        try {
            for (int i = 0; i < tasks; i++) {
                try {
                    pool.execute(() -> {
                        try { release.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        finally { done.countDown(); }
                    });
                } catch (RejectedExecutionException rejected) {
                    accepted = false;
                    done.countDown();
                }
            }
            check(accepted, "internal task bursts are queued without rejection");
        } finally {
            release.countDown();
            check(done.await(5, TimeUnit.SECONDS), "all accepted internal tasks complete");
            pool.stop();
        }
    }

    private static class Channel extends AbstractSelectableChannel {
        Channel() { super(SelectorProvider.provider()); }
        public int validOps() { return SelectionKey.OP_READ; }
        protected void implCloseSelectableChannel() {}
        protected void implConfigureBlocking(boolean block) {}
    }

    private static class Pending {
        final Response response;
        final Callback callback;
        Pending(Response response, Callback callback) { this.response = response; this.callback = callback; }
        void complete() { response.write(true, BufferUtil.toBuffer("done"), callback); }
    }

    private static class HoldingHandler extends Handler.Abstract {
        final BlockingQueue<Pending> pending = new LinkedBlockingQueue<>();
        final AtomicInteger calls = new AtomicInteger();
        public boolean handle(Request request, Response response, Callback callback) {
            calls.incrementAndGet();
            if (request.getHttpURI().getPath().equals("/fast")) {
                response.write(true, BufferUtil.toBuffer("fast"), callback);
            } else {
                pending.add(new Pending(response, callback));
            }
            return true;
        }
        Pending next() throws Exception {
            Pending next = pending.poll(5, TimeUnit.SECONDS);
            if (next == null) throw new AssertionError("application request did not arrive");
            return next;
        }
    }

    private static void admission(Path root, Path fixtures) throws Exception {
        XmlConfiguration config = xml(root, "jetty.xml");
        Server server = (Server) config.configure();
        QueuedThreadPool pool = (QueuedThreadPool) server.getThreadPool();
        try {
            workerBurst(pool);
            ServerConnector http = (ServerConnector) server.getConnectors()[0];
            check(http.getSelectorManager().getSelectorCount() == 1, "HTTP connector leases one selector");
            check(http.getIdleTimeout() == 120000, "HTTP idle retention is bounded");
            NetworkConnectionLimit connections = server.getBean(NetworkConnectionLimit.class);
            check(connections != null && connections.getMaxNetworkConnectionCount() == 256, "global connection cap configured");
            Object configuredLimit = config.getIdMap().get("RequestLimit");
            check(configuredLimit instanceof QoSHandler, "shipped handler tree limits HTTP requests asynchronously");
            if (!(configuredLimit instanceof QoSHandler)) return;
            QoSHandler limit = (QoSHandler) configuredLimit;
            check(limit.getMaxRequestCount() == 12 && limit.getMaxSuspendedRequestCount() == 64 &&
                  limit.getMaxSuspend().equals(Duration.ofSeconds(10)), "request admission has finite defaults");
            // Keep the actual configured pool, limits and handler. Replace only
            // network/deployment/file-logging I/O with in-memory test fixtures.
            server.setConnectors(new Connector[0]);
            for (DeploymentManager deployment : server.getBeans(DeploymentManager.class))
                server.removeBean(deployment);
            server.setRequestLog(null);
            LocalConnector connector = new LocalConnector(server);
            LocalConnector second = new LocalConnector(server);
            server.addConnector(connector);
            server.addConnector(second);
            HoldingHandler application = new HoldingHandler();
            limit.setHandler(application);
            server.start();
            List<Channel> channels = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                Channel channel = new Channel();
                channels.add(channel);
                connections.onAccepting(channel);
                connections.onAccepted(channel);
            }
            check(!connector.isAccepting() && !second.isAccepting(), "connection saturation pauses all connectors");
            connections.onClosed(channels.remove(0));
            check(connector.isAccepting() && second.isAccepting(), "closing a connection restores admission");
            for (Channel channel : channels) {
                connections.onClosed(channel);
                channel.close();
            }
            List<LocalConnector.LocalEndPoint> clients = new ArrayList<>();
            List<Pending> active = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                clients.add(connector.executeRequest("GET /hold HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n"));
                active.add(application.next());
            }
            for (int i = 0; i < 64; i++)
                clients.add(connector.executeRequest("GET /wait HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n"));
            await(() -> limit.getSuspendedRequestCount() == 64, "requests did not suspend");
            check(application.calls.get() == 12, "only admitted requests reach the application");
            CountDownLatch ioTask = new CountDownLatch(1);
            pool.execute(ioTask::countDown);
            check(ioTask.await(2, TimeUnit.SECONDS), "suspended requests leave workers free for I/O tasks");
            long before = System.nanoTime();
            String overflow = connector.getResponse("GET /overflow HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n", 5, TimeUnit.SECONDS);
            check(status(overflow) == 503, "full request queue rejects overflow with 503");
            System.out.println("OVERFLOW_RESPONSE_MILLIS=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
            active.remove(0).complete();
            Pending resumed = application.next();
            check(application.calls.get() == 13, "completing one request resumes exactly one waiter");
            resumed.complete();
            for (Pending request : active) request.complete();
            for (int i = 1; i < 64; i++) application.next().complete();
            boolean complete = true;
            for (LocalConnector.LocalEndPoint client : clients) {
                String response = client.getResponse(false, 5, TimeUnit.SECONDS);
                complete &= status(response) == 200 && response.contains("done");
            }
            check(complete, "all admitted and queued clients complete successfully");
            await(() -> limit.getSuspendedRequestCount() == 0, "queue did not drain");

            // Exercise failure and expiry independently, accelerating only the
            // timeout so the regression does not wait ten seconds per run.
            limit.setMaxSuspend(Duration.ofMillis(150));
            active.clear();
            for (int i = 0; i < 12; i++) {
                connector.executeRequest("GET /hold HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n");
                active.add(application.next());
            }
            LocalConnector.LocalEndPoint waiter = connector.executeRequest("GET /wait HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n");
            await(() -> limit.getSuspendedRequestCount() == 1, "failure test did not suspend");
            active.remove(0).callback.failed(new IOException("controlled application failure"));
            application.next().complete();
            check(status(waiter.getResponse(false, 5, TimeUnit.SECONDS)) == 200, "failed application releases its admission slot");
            connector.executeRequest("GET /hold HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n");
            active.add(application.next());
            int calls = application.calls.get();
            String expired = connector.getResponse("GET /expired HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n", 5, TimeUnit.SECONDS);
            check(status(expired) == 503 && calls == application.calls.get(), "expired waiter returns 503 without calling application");
            for (Pending request : active) request.complete();
            await(() -> limit.getSuspendedRequestCount() == 0, "expired wait retained a slot");
            check(status(connector.getResponse("GET /fast HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n")) == 200, "server recovers after overload and failures");
            server.stop();
            server.start();
            check(status(connector.getResponse("GET /fast HTTP/1.1\r\nHost: site.i2p\r\nConnection: close\r\n\r\n")) == 200, "configured request limiter survives restart");
            server.stop();
        } finally {
            server.stop();
            server.destroy();
        }
    }

    private static class ClockRate extends RequestRateLimitHandler {
        long now;
        protected long nanoTime() { return now; }
    }

    private static void rates() throws Exception {
        ClockRate rate = new ClockRate();
        rate.setRequestsPerSecond(1);
        rate.setBurst(2);
        AtomicInteger calls = new AtomicInteger();
        rate.setHandler(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                calls.incrementAndGet();
                callback.succeeded();
                return true;
            }
        });
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(rate);
        try {
            server.start();
            check(status(connector.getResponse(wire("/", ""))) == 200, "first burst token admitted");
            check(status(connector.getResponse(wire("/", ""))) == 200, "second burst token admitted");
            String denied = connector.getResponse(wire("/", ""));
            check(status(denied) == 429 && denied.contains("Retry-After: 1") && denied.contains("Cache-Control: no-store"), "rate rejection gives a non-cacheable retry response");
            check(calls.get() == 2, "rate-rejected work never reaches the application");
            rate.now += TimeUnit.MILLISECONDS.toNanos(500);
            check(status(connector.getResponse(wire("/", ""))) == 429, "partial token does not admit a request");
            rate.now += TimeUnit.MILLISECONDS.toNanos(500);
            check(status(connector.getResponse(wire("/", ""))) == 200, "monotonic refill restores one token");
            check(status(connector.getResponse(wire("/", ""))) == 429, "one refill cannot be spent twice");
            check(rate.getRejectedRequests() == 3, "rate rejections are observable");
            server.stop();
            server.start();
            check(status(connector.getResponse(wire("/", ""))) == 200, "restart resets the token bucket");
        } finally { server.stop(); server.destroy(); }
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try { new ClockRate().setRequestsPerSecond(invalid); check(false, "invalid rate rejected"); }
            catch (IllegalArgumentException expected) { check(true, "invalid rate rejected"); }
        }
    }

    private static void bodyAndDrain() throws Exception {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        AtomicReference<Response> heldResponse = new AtomicReference<>();
        AtomicReference<Callback> heldCallback = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server.setHandler(WebServerPolicy.protect(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                calls.incrementAndGet();
                if (request.getHttpURI().getPath().equals("/hold")) {
                    heldResponse.set(response);
                    heldCallback.set(callback);
                } else {
                    if (request.getHttpURI().getPath().equals("/custom"))
                        response.getHeaders().put("Referrer-Policy", "same-origin");
                    Content.Source.consumeAll(request, Callback.from(
                        () -> response.write(true, BufferUtil.toBuffer("ok"), callback),
                        failure -> Response.writeError(request, response, callback, failure)));
                }
                return true;
            }
        }, 10000, 128, 8));
        server.setStopTimeout(2000);
        try {
            server.start();
            String headers = "POST / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n";
            check(status(connector.getResponse(headers + "Content-Length: 8\r\n\r\n12345678")) == 200, "body at the limit is accepted");
            int before = calls.get();
            check(status(connector.getResponse(headers + "Content-Length: 9\r\n\r\n123456789")) == 413, "declared oversized body rejected with 413");
            check(calls.get() == before, "declared oversized body rejected before application");
            check(status(connector.getResponse(headers + "Transfer-Encoding: chunked\r\n\r\n9\r\n123456789\r\n0\r\n\r\n")) == 413, "streamed chunked bodies obey the same limit");
            String response = connector.getResponse(wire("/", ""));
            check(response.contains("X-Content-Type-Options: nosniff") && response.contains("Referrer-Policy: no-referrer"), "application responses receive default privacy headers");
            check(connector.getResponse(wire("/custom", "")).contains("Referrer-Policy: same-origin"), "application policy can override the default");
            LocalConnector.LocalEndPoint held = connector.executeRequest(wire("/hold", ""));
            await(() -> heldCallback.get() != null, "held request not entered");
            GracefulHandler graceful = server.getDescendant(GracefulHandler.class);
            var draining = graceful.shutdown();
            check(!draining.isDone(), "graceful shutdown waits for in-flight work");
            check(status(connector.getResponse(wire("/", ""))) == 503, "shutdown rejects new work with 503");
            heldResponse.get().write(true, BufferUtil.toBuffer("finished"), heldCallback.get());
            check(status(held.getResponse(false, 5, TimeUnit.SECONDS)) == 200, "in-flight response finishes during drain");
            draining.get(5, TimeUnit.SECONDS);
            check(graceful.getCurrentRequestCount() == 0, "draining releases all request accounting");
            StatisticsHandler statistics = server.getDescendant(StatisticsHandler.class);
            check(statistics.getResponses4xx() >= 2 && statistics.getResponses5xx() >= 1, "standard response metrics include rejections");
        } finally { server.stop(); server.destroy(); }
    }

    public static void main(String[] args) throws Exception {
        admission(Path.of(args[0]), Path.of(args[1])); rates(); bodyAndDrain();
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
