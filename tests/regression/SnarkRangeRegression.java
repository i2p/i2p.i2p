package org.klomp.snark.web;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

/** A bodyless 416 must advertise zero bytes, not the file length. */
public final class SnarkRangeRegression {
    public static void main(String[] args) throws Exception {
        Path files = Files.createTempDirectory("snark-range-");
        Files.writeString(files.resolve("file.txt"), "0123456789");
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler web = new ServletContextHandler();
        web.setContextPath("/i2psnark");
        ServletHolder holder = new ServletHolder(new BasicServlet());
        holder.setInitParameter("resourceBase", files.toString());
        web.addServlet(holder, "/");
        server.setHandler(web.getCoreContextHandler());
        int checks = 0;
        try {
            server.start();
            for (String range : new String[] {"50-60", "10-", "0-1,4-5"}) {
                String response = request(connector, range);
                if (!response.startsWith("HTTP/1.1 416") || !response.contains("Content-Range: bytes */10\r\n") ||
                    !response.contains("Content-Length: 0\r\n") || !response.endsWith("\r\n\r\n"))
                    throw new AssertionError("invalid range response: " + response);
                checks++;
            }
            String response = request(connector, "2-5");
            if (!response.startsWith("HTTP/1.1 206") || !response.endsWith("2345") || !response.contains("Content-Range: bytes 2-5/10"))
                throw new AssertionError("valid range response changed");
            checks++;
            System.out.println("SNARK_RANGE_HTTP_CHECKS=" + checks);
        } finally { server.stop(); server.destroy(); }
    }
    private static String request(LocalConnector connector, String range) throws Exception {
        return connector.getResponse("GET /i2psnark/file.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nRange: bytes=" + range + "\r\n\r\n");
    }
}
