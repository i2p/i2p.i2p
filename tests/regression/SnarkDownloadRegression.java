package org.klomp.snark.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.ResourceFactory;

/** Real HTTP responses for untrusted downloaded files and trusted WAR assets. */
public final class SnarkDownloadRegression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        Path work = Files.createTempDirectory("snark-download-");
        Path files = Files.createDirectory(work.resolve("files"));
        Path war = Files.createDirectories(work.resolve("war/.resources"));
        for (String file : new String[] {"attack.html", "attack.svg", "attack.js", "data.xml", "opaque.unknownextension", "sample.png", "sample.mp4"})
            Files.writeString(files.resolve(file), "0123456789", StandardCharsets.US_ASCII);
        Files.writeString(war.resolve("trusted.js"), "const safe = true;", StandardCharsets.US_ASCII);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler web = new ServletContextHandler();
        web.setContextPath("/i2psnark");
        web.setBaseResource(ResourceFactory.of(web).newResource(work.resolve("war")));
        ServletHolder holder = new ServletHolder(new BasicServlet());
        holder.setInitParameter("resourceBase", files.toString());
        holder.setInitParameter("warBase", "/.resources/");
        web.addServlet(holder, "/");
        server.setHandler(web.getCoreContextHandler());
        try {
            server.start();
            for (String file : new String[] {"attack.html", "attack.svg", "attack.js", "data.xml", "opaque.unknownextension"}) {
                String response = request(connector, "GET", file, "");
                check(response.startsWith("HTTP/1.1 200") && body(response).equals("0123456789"), "active download retains bytes");
                check(header(response, "Content-Disposition").equals("attachment"), "active/unknown document is an attachment");
                check(header(response, "Content-Type").startsWith("application/octet-stream"), "active files cannot execute as script subresources");
                isolated(response);
            }
            String unknown = request(connector, "GET", "opaque.unknownextension", "");
            check(header(unknown, "Content-Type").startsWith("application/octet-stream"), "unknown type cannot be browser-sniffed");
            for (String file : new String[] {"sample.png", "sample.mp4"}) {
                String response = request(connector, "GET", file, "");
                check(header(response, "Content-Disposition").isEmpty(), "ordinary media retains inline support");
                isolated(response);
            }
            String response = request(connector, "HEAD", "attack.html", "");
            check(response.startsWith("HTTP/1.1 200") && body(response).isEmpty() && header(response, "Content-Length").equals("10"), "HEAD retains length without body");
            isolated(response);
            response = request(connector, "GET", "sample.mp4", "Range: bytes=2-5\r\n");
            check(response.startsWith("HTTP/1.1 206") && body(response).equals("2345") && header(response, "Content-Range").equals("bytes 2-5/10"), "media byte ranges are unchanged");
            isolated(response);
            response = request(connector, "GET", "attack.html", "");
            String modified = header(response, "Last-Modified");
            response = request(connector, "GET", "attack.html", "If-Modified-Since: " + modified + "\r\n");
            check(response.startsWith("HTTP/1.1 304") && body(response).isEmpty(), "conditional response has no body");
            isolated(response);
            response = request(connector, "GET", ".resources/trusted.js", "");
            check(response.startsWith("HTTP/1.1 200") && body(response).equals("const safe = true;"), "trusted WAR asset is served normally");
            check(header(response, "Content-Security-Policy").isEmpty() && header(response, "Content-Disposition").isEmpty(), "download sandbox does not affect application scripts");
            System.out.println("SNARK_DOWNLOAD_HTTP_CHECKS=" + checks);
        } finally {
            server.stop();
            server.destroy();
            context.logManager().shutdown();
        }
    }
    private static void isolated(String response) {
        String policy = header(response, "Content-Security-Policy");
        check(policy.startsWith("sandbox;") && policy.contains("script-src 'none'") && policy.contains("form-action 'none'"), "untrusted documents have no origin/script/form privileges");
        check(header(response, "X-Content-Type-Options").equals("nosniff") && header(response, "Referrer-Policy").equals("no-referrer"), "download has MIME/referrer protections");
        check(header(response, "Cache-Control").startsWith("private,") && header(response, "Cross-Origin-Resource-Policy").equals("same-origin"), "download is private and restricted to same-origin embedding");
    }
    private static String request(LocalConnector connector, String method, String file, String headers) throws Exception {
        return connector.getResponse(method + " /i2psnark/" + file + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + headers + "\r\n");
    }
    private static String header(String response, String name) {
        for (String line : response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n"))
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return line.substring(name.length() + 1).trim();
        return "";
    }
    private static String body(String response) { return response.substring(response.indexOf("\r\n\r\n") + 4); }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
