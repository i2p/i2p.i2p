package net.i2p.router.web;

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
public class ConsoleHostRegression {
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

    private static void hosts() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("routerconsole.redirectToHTTPS", "true");
        I2PAppContext context = new I2PAppContext(properties);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        HostCheckHandler hostCheck = new HostCheckHandler(context);
        hostCheck.setListenHosts(Set.of("0.0.0.0", "Console.Example"));
        AtomicInteger calls = new AtomicInteger();
        hostCheck.setHandler(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                calls.incrementAndGet();
                response.setStatus(204);
                callback.succeeded();
                return true;
            }
        });
        server.setHandler(hostCheck);
        try {
            server.start();
            for (String host : new String[] {"localhost:7657", "LOCALHOST:7657", "127.0.0.1:7657",
                                             "[::1]:7657", "192.168.1.3:7657", "console.example:7657", "CONSOLE.EXAMPLE:7657"})
                originCase(connector, "GET", host, "", 204, "allowed console host " + host);
            originCase(connector, "GET", "evil.example:7657", "", 403, "wildcard listener keeps hostname protection");
            for (String request : new String[] {
                "GET http://evil.example/ HTTP/1.1\r\nHost: localhost:7657\r\nConnection: close\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: localhost:7657\r\nHost: evil.example\r\nConnection: close\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: localhost:7657\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n"
            }) {
                int code = status(connector.getResponse(request));
                check(code == 400 || code == 403, "ambiguous authority or request framing rejected");
            }
            server.stop();
            hostCheck.setListenHosts(Collections.emptySet());
            server.start();
            originCase(connector, "GET", "evil.example:7657", "", 403, "empty host set retains protection");
            originCase(connector, "GET", "localhost:7657", "", 204, "empty host set permits local access");
            context.portMapper().register(PortMapper.SVC_HTTPS_CONSOLE, 8443);
            String redirect = connector.getResponse("GET /config?x=1 HTTP/1.1\r\nHost: [::1]:7657\r\nConnection: close\r\n\r\n");
            check(status(redirect) == 307 && redirect.contains("Location: https://[::1]:8443/config?x=1\r\n"),
                  "HTTPS redirect keeps IPv6 authority, path and query");
        } finally {
            server.stop();
            server.destroy();
            context.logManager().shutdown();
        }
    }

    public static void main(String[] args) throws Exception {
        hosts();
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
