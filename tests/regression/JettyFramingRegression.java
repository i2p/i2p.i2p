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
public class JettyFramingRegression {
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

    private static void chunkFraming() throws Exception {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                Content.Source.consumeAll(request, Callback.from(
                    () -> response.write(true, BufferUtil.toBuffer("consumed"), callback),
                    failure -> Response.writeError(request, response, callback, 400)));
                return true;
            }
        });
        try {
            server.start();
            String headers = "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
            check(status(connector.getResponse(headers + "3;name=\"safe\"\r\nabc\r\n0\r\n\r\n")) == 200,
                  "valid quoted chunk extensions are consumed");
            for (String body : new String[] {"3;!\nabc\r\n0\r\n\r\n", "3;name=\"unterminated\r\nabc\r\n0\r\n\r\n"})
                check(status(connector.getResponse(headers + body)) == 400,
                      "malformed chunk extension terminators are rejected");
        } finally {
            server.stop();
            server.destroy();
        }
    }

    public static void main(String[] args) throws Exception {
        check(Server.getVersion().equals("12.0.41"), "pinned Jetty version");
        chunkFraming();
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
