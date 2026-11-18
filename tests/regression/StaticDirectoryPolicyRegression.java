package net.i2p.servlet;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;

import net.i2p.router.web.CSSHelper;

/** Sends real HTTP requests through Jetty without opening network sockets. */
public class StaticDirectoryPolicyRegression {
    private static int checks, failures;
    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) { failures++; System.out.println("FAIL: " + message); }
    }

    private static boolean header(String response, String name, String value) {
        String headers = response.substring(0, response.indexOf("\r\n\r\n")).toLowerCase(Locale.US);
        return headers.contains("\r\n" + name.toLowerCase(Locale.US) + ": " + value.toLowerCase(Locale.US));
    }

    private static String request(LocalConnector connector, String method, String path, String extra) throws Exception {
        return connector.getResponse(method + " " + path + " HTTP/1.1\r\nHost: eepsite.test\r\nConnection: close\r\n" + extra + "\r\n");
    }

    private static void http(Path source) throws Exception {
        Path docroot = Path.of("eepsite/docroot").toAbsolutePath();
        Path shipped = source.resolve("installer/resources/eepsite/docroot");
        try (Stream<Path> files = Files.walk(shipped)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                Path target = docroot.resolve(shipped.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Files.createDirectories(docroot.resolve("files"));
        Files.writeString(docroot.resolve("index.html"), "<!DOCTYPE html><title>test site</title><p>public content</p>");
        Files.writeString(docroot.resolve("files/not-for-listing.txt"), "public-file-bytes");
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        XmlConfiguration xml = new XmlConfiguration(ResourceFactory.of(server).newResource(source.resolve("installer/resources/eepsite/contexts/base-context.xml")));
        ServletContextHandler context = (ServletContextHandler) xml.configure();
        context.addFilter(new FilterHolder(new Filter() {
            public void init(FilterConfig config) {}
            public void destroy() {}
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws java.io.IOException, javax.servlet.ServletException {
                if (request.getParameter("site-policy") != null)
                    ((HttpServletResponse) response).setHeader("Referrer-Policy", "same-origin");
                chain.doFilter(request, response);
            }
        }), "/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(context.getCoreContextHandler());
        try {
            server.start();
            String response = request(connector, "GET", "/files/", "");
            check(response.startsWith("HTTP/1.1 403"), "shipped context disables directory enumeration");
            check(!response.contains("not-for-listing.txt"), "directory response does not enumerate filenames");
            response = request(connector, "GET", "/files/not-for-listing.txt", "");
            check(response.startsWith("HTTP/1.1 200") && response.endsWith("public-file-bytes"), "known file URLs still serve the same bytes");
            response = request(connector, "HEAD", "/files/not-for-listing.txt", "");
            check(response.startsWith("HTTP/1.1 200") && !response.contains("public-file-bytes"), "HEAD keeps its body-free response");
            response = request(connector, "GET", "/help/", "");
            check(response.startsWith("HTTP/1.1 200") && response.contains("I2P Anonymous Webserver"), "help remains reachable with directory listings disabled");
            response = request(connector, "GET", "/help/lib/eepsite.css", "");
            check(response.startsWith("HTTP/1.1 200") && header(response, "Content-Type", "text/css"), "shared page and error stylesheet has its CSS MIME type");
            response = request(connector, "GET", "/index.html?site-policy=1", "");
            check(header(response, "Referrer-Policy", "same-origin"), "an explicit site referrer policy is preserved");
            response = request(connector, "GET", "/files/not-for-listing.txt", "Range: bytes=0-5\r\n");
            check(response.startsWith("HTTP/1.1 206") && response.endsWith("public"), "range requests still work");
            server.stop();
            context.setInitParameter("org.eclipse.jetty.servlet.Default.dirAllowed", "true");
            server.start();
            response = request(connector, "GET", "/files/", "");
            check(response.startsWith("HTTP/1.1 200") && response.contains("not-for-listing.txt"), "site owners may explicitly opt into directory listings");
        } finally { server.stop(); server.destroy(); }
    }

    public static void main(String[] args) throws Exception {
        http(Path.of(args[0]).toAbsolutePath());
        System.out.println("DEFAULT_WEB_SECURITY_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
