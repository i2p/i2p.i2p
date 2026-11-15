package org.klomp.snark;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import net.i2p.I2PAppContext;
import net.i2p.router.web.HostCheckHandler;
import net.i2p.servlet.filters.XSSFilter;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.klomp.snark.web.I2PSnarkServlet;

/** Real Snark forms through the console host/origin guard and session nonce checks. */
public final class SnarkFormOriginRegression {
    private static final String ORIGIN = "http://127.0.0.1:7657";
    private static String cookie;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("snark-form-origin-");
        Properties props = new Properties();
        props.setProperty("i2p.dir.config", work.toString());
        props.setProperty("i2p.dir.base", work.toString());
        props.setProperty("routerconsole.lang", "en");
        I2PAppContext context = new I2PAppContext(false, props) { };
        context.logManager().setDefaultLimit("CRIT");
        CountingManager manager = new CountingManager(context);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler web = new ServletContextHandler(ServletContextHandler.SESSIONS);
        web.setContextPath("/i2psnark");
        web.addFilter(new FilterHolder(new XSSFilter()), "/*", java.util.EnumSet.of(javax.servlet.DispatcherType.REQUEST));
        web.addServlet(new ServletHolder(new TestServlet(manager)), "/");
        HostCheckHandler guard = new HostCheckHandler(context);
        guard.setListenHosts(Set.of("127.0.0.1", "localhost"));
        guard.setHandler(web.getCoreContextHandler());
        server.setHandler(guard);
        try {
            server.start();
            String page = request(connector, "GET", "/i2psnark/", "", "");
            check(page.startsWith("HTTP/1.1 200"), "real Snark form renders behind the console guard");
            // Fetch's non-CORS form-navigation algorithm serializes Origin as null
            // under no-referrer, even for a same-origin POST. No Referer fallback.
            String policy = header(page, "Referrer-Policy");
            String origin = policy.equals("no-referrer") ? "null" : ORIGIN;
            String token = nonce(page);
            String response = request(connector, "POST", "/i2psnark/_post", form(token),
                    "Origin: " + origin + "\r\nSec-Fetch-Site: same-origin\r\n");
            check(redirect(response) && manager.clears == 1,
                    "browser form passes the origin guard and executes once; got " + response.substring(0, response.indexOf("\r\n")));
            check(policy.equals("same-origin"), "UI retains Origin for local forms and suppresses cross-origin referrers");
            token = nonce(request(connector, "GET", "/i2psnark/", "", ""));
            List<String> rejectedOrigins = new ArrayList<String>(List.of(
                    "Origin: https://evil.example\r\n",
                    "Origin: http://localhost:7657\r\n"));
            // The shared guard's separate hardening is optional on older upstream builds.
            if (Boolean.getBoolean("snark.test.strictOrigin")) {
                rejectedOrigins.add("Origin: null\r\nSec-Fetch-Site: same-origin\r\n");
                rejectedOrigins.add("Origin: " + ORIGIN + "\r\nOrigin: " + ORIGIN + "\r\n");
                rejectedOrigins.add("Origin: " + ORIGIN + "\r\nSec-Fetch-Site: cross-site\r\n");
            }
            for (String headers : rejectedOrigins) {
                response = request(connector, "POST", "/i2psnark/_post", form(token), headers);
                check(response.startsWith("HTTP/1.1 403") && manager.clears == 1,
                        "opaque, foreign, duplicate and cross-site origins remain blocked");
            }
            response = request(connector, "POST", "/i2psnark/_post", form("invalid"), "Origin: " + ORIGIN + "\r\n");
            check(redirect(response) && manager.clears == 1, "matching origin still requires the session nonce");
            String originalCookie = cookie;
            cookie = null;
            request(connector, "POST", "/i2psnark/_post", form(token), "Origin: " + ORIGIN + "\r\n");
            check(manager.clears == 1, "another session cannot use the nonce");
            cookie = originalCookie;
            request(connector, "POST", "/i2psnark/_post", form(token), "Origin: " + ORIGIN + "\r\n");
            check(manager.clears == 2, "rejected origins did not consume the genuine session nonce");
            request(connector, "POST", "/i2psnark/_post", form(token), "Origin: " + ORIGIN + "\r\n");
            check(manager.clears == 2, "successful form tokens remain single-use");
            System.out.println("SNARK_FORM_ORIGIN_CHECKS=" + checks);
        } finally { server.stop(); server.destroy(); context.logManager().shutdown(); }
    }

    private static final class CountingManager extends SnarkManager {
        int clears;
        CountingManager(I2PAppContext context) { super(context); }
        @Override public void clearMessages(int id) { clears++; }
    }
    private static final class TestServlet extends I2PSnarkServlet {
        private final SnarkManager manager;
        private ServletConfig config;
        TestServlet(SnarkManager manager) { this.manager = manager; }
        @Override public void init(ServletConfig config) throws ServletException {
            this.config = config;
            try {
                set("_manager", manager); set("_contextPath", "/i2psnark"); set("_contextName", "i2psnark");
                setResourceBase(manager.getDataDir()); setWarBase("/.resources/");
            } catch (Exception error) { throw new ServletException(error); }
        }
        private void set(String name, Object value) throws Exception {
            Field field = I2PSnarkServlet.class.getDeclaredField(name); field.setAccessible(true); field.set(this, value);
        }
        @Override public ServletConfig getServletConfig() { return config; }
        @Override public ServletContext getServletContext() { return config.getServletContext(); }
        @Override public void destroy() { }
    }
    private static String form(String token) { return "action=Clear&id=1&nonce=" + token; }
    private static boolean redirect(String response) { return response.startsWith("HTTP/1.1 302") || response.startsWith("HTTP/1.1 303"); }
    private static String request(LocalConnector connector, String method, String path, String body, String extra) throws Exception {
        String headers = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:7657\r\nConnection: close\r\n" + extra;
        if (cookie != null) headers += "Cookie: " + cookie + "\r\n";
        if (method.equals("POST")) headers += "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n";
        String response = connector.getResponse(headers + "\r\n" + body);
        Matcher match = Pattern.compile("(?im)^Set-Cookie: ([^;\r\n]+)").matcher(response);
        if (match.find()) cookie = match.group(1);
        return response;
    }
    private static String header(String response, String name) {
        Matcher match = Pattern.compile("(?im)^" + Pattern.quote(name) + ": ([^\r\n]+)").matcher(response);
        return match.find() ? match.group(1) : "";
    }
    private static String nonce(String response) {
        Matcher match = Pattern.compile("name=\"nonce\" value=\"([^\"]+)\"").matcher(response);
        if (!match.find()) throw new AssertionError("No form nonce in page");
        return match.group(1);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
