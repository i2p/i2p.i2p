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
public class OriginProtectionRegression {
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

    private static void origins(boolean servlet) throws Exception {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        if (servlet) {
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.addServlet(new ServletHolder(new HttpServlet() {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) {
                    if ("h3".equals(request.getHeader("X-Test-Protocol"))) {
                        request = new HttpServletRequestWrapper(request) {
                            public String getProtocol() { return "HTTP/3.0"; }
                            public String getHeader(String name) { return name.equalsIgnoreCase("Host") ? null : super.getHeader(name); }
                        };
                    }
                    if ("1".equals(request.getHeader("X-Test-Secure"))) {
                        request = new HttpServletRequestWrapper(request) {
                            @Override
                            public boolean isSecure() { return true; }
                        };
                    }
                    response.setStatus(ServletUtil.allowOrigin(request) ? 204 : 403);
                }
            }), "/*");
            server.setHandler(context.getCoreContextHandler());
        } else {
            server.setHandler(new Handler.Abstract() {
                public boolean handle(Request request, Response response, Callback callback) {
                    if ("1".equals(request.getHeaders().get("X-Test-Secure"))) {
                        request = new Request.Wrapper(request) {
                            @Override
                            public boolean isSecure() { return true; }
                        };
                    }
                    if ("h3".equals(request.getHeaders().get("X-Test-Protocol"))) {
                        HttpURI uri = HttpURI.from((request.isSecure() ? "https://" : "http://") + request.getHeaders().get("Host") + "/");
                        HttpFields fields = HttpFields.build(request.getHeaders()).remove("Host").asImmutable();
                        request = new Request.Wrapper(request) {
                            public HttpURI getHttpURI() { return uri; }
                            public HttpFields getHeaders() { return fields; }
                        };
                    }
                    response.setStatus(ServletUtil.allowOrigin(request) ? 204 : 403);
                    callback.succeeded();
                    return true;
                }
            });
        }
        try {
            server.start();
            String label = servlet ? "servlet: " : "core: ";
            originCase(connector, "POST", "localhost:7657", "X-Test-Protocol: h3\r\nOrigin: http://localhost:7657\r\n", 204,
                       label + "authority without Host supports same-origin writes");
            originCase(connector, "POST", "localhost:7657", "X-Test-Protocol: h3\r\nOrigin: http://evil.example\r\n", 403,
                       label + "authority without Host rejects cross-origin writes");
            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "PROPFIND"}) {
                originCase(connector, method, "localhost:7657", "", 204, label + "clients without Origin work");
                originCase(connector, method, "localhost:7657", "Sec-Fetch-Site: cross-site\r\n", 403, label + "cross-site browser writes without Origin rejected");
                originCase(connector, method, "localhost:7657", "Origin: http://localhost:7657\r\n", 204, label + "same origin works");
                originCase(connector, method, "localhost:7657", "Origin: http://evil.example\r\n", 403, label + "cross origin rejected");
                originCase(connector, method, "localhost:7657", "Origin: null\r\n", 403, label + "opaque origin rejected");
            }
            for (String method : new String[] {"GET", "HEAD", "OPTIONS", "TRACE"})
                originCase(connector, method, "localhost:7657", "Origin: http://evil.example\r\n", 204, label + "safe methods can be read");
            String[][] valid = {
                {"localhost", "http://localhost:80"}, {"localhost:80", "http://localhost"},
                {"LOCALHOST:7657", "HTTP://localhost:7657"},
                {"127.0.0.1:7657", "http://127.0.0.1:7657"},
                {"[::1]", "http://[::1]:80"}, {"[::1]:80", "http://[::1]"},
                {"[::1]:7657", "http://[::1]:7657"}
            };
            for (String[] pair : valid)
                originCase(connector, "POST", pair[0], "Origin: " + pair[1] + "\r\n", 204, label + "normalized origin " + pair[1]);
            for (String host : new String[] {"localhost", "localhost:443", "[::1]", "[::1]:443"}) {
                String origin = host.startsWith("[") ? "https://[::1]" : "https://localhost";
                originCase(connector, "POST", host, "X-Test-Secure: 1\r\nOrigin: " + origin + "\r\n", 204, label + "HTTPS default port");
            }
            for (String origin : new String[] {
                "", "NULL", "http://localhost:7658", "https://localhost:7657", "http://localhost.evil:7657",
                "http://user@localhost:7657", "http://localhost:7657/", "http://localhost:7657/path",
                "http://localhost:7657?x", "http://localhost:7657#x", "http://localhost:",
                "http://localhost:0", "http://localhost:65536", "http://localhost:-1", "http://[::1",
                "file://localhost:7657", "http://localhost:7657 http://evil.example",
                "http://localhost:7657,http://evil.example"
            })
                originCase(connector, "POST", "localhost:7657", "Origin: " + origin + "\r\n", 403, label + "invalid origin " + origin);
            for (String pair : new String[] {
                "Origin: http://localhost:7657\r\nOrigin: http://evil.example\r\n",
                "Origin: http://evil.example\r\nOrigin: http://localhost:7657\r\n",
                "Origin: http://localhost:7657\r\nOrigin: http://localhost:7657\r\n"
            })
                originCase(connector, "POST", "localhost:7657", pair, 403, label + "multiple origins rejected");
            String noHost = "POST / HTTP/1.0\r\nOrigin: http://localhost\r\nContent-Length: 0\r\n\r\n";
            check(status(connector.getResponse(noHost)) == 403, label + "Origin without Host rejected");
        } finally {
            server.stop();
            server.destroy();
        }
    }

    public static void main(String[] args) throws Exception {
        origins(false); origins(true);
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
