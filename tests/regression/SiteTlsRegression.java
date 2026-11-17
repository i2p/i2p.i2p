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
public class SiteTlsRegression {
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

    private static void tls(Path root) throws Exception {
        Server server = new Server();
        XmlConfiguration config = new XmlConfiguration(ResourceFactory.root().newResource(root.resolve("installer/resources/eepsite/jetty-ssl.xml")));
        config.getIdMap().put("Server", server);
        try {
            config.configure();
            ServerConnector https = (ServerConnector)server.getConnectors()[0];
            check(https.getSelectorManager().getSelectorCount() == 1, "one TLS selector");
            check(https.getIdleTimeout() == 120000, "bounded TLS idle retention");
            SslContextFactory.Server tls = (SslContextFactory.Server)config.getIdMap().get("sslContextFactory");
            tls.start();
            try {
                check(Set.copyOf(Arrays.asList(tls.getSelectedProtocols())).equals(Set.of("TLSv1.2", "TLSv1.3")), "modern TLS protocols");
                check(Arrays.stream(tls.getSelectedCipherSuites()).noneMatch(c -> c.contains("_NULL_") || c.contains("_anon_") || c.contains("_RC4_") || c.contains("_DES_") || c.contains("_3DES_") || c.startsWith("TLS_RSA_")), "maintained cipher exclusions");
            } finally { tls.stop(); }
            check(https.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().getCustomizer(SecureRequestCustomizer.class) != null, "secure request customizer");
        } finally { server.destroy(); }
    }
    public static void main(String[] args) throws Exception {
        tls(Path.of(args[0]));
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
