package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.servlet.filters.XSSFilter;

import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.web.I2PSnarkServlet;

/** HTTP checks through real servlet sessions and the shared filter, without sockets. */
public final class PeerCatalogWebRegression {
    private static int checks;
    private static String cookie;

    public static void main(String[] args) throws Throwable {
        Path work = Files.createTempDirectory("catalog-web-");
        TestContext context = new TestContext(work);
        context.logManager().setDefaultLimit("CRIT");
        SnarkManager manager = new SnarkManager(context);
        manager.getPeerCatalog().start();
        StubSnark publicTorrent = new StubSnark(manager, PeerCatalogRegression.publicMeta("A public torrent"));
        StubSnark privateTorrent = new StubSnark(manager, PeerCatalogRegression.meta("Private local torrent", true));
        @SuppressWarnings("unchecked")
        Map<String, Snark> torrents = (Map<String, Snark>) field(SnarkManager.class, "_snarks").get(manager);
        torrents.put(publicTorrent.getName(), publicTorrent);
        torrents.put(privateTorrent.getName(), privateTorrent);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler web = new ServletContextHandler(ServletContextHandler.SESSIONS);
        web.setContextPath("/i2psnark");
        web.addFilter(new FilterHolder(new XSSFilter()), "/*", java.util.EnumSet.of(javax.servlet.DispatcherType.REQUEST));
        web.addServlet(new ServletHolder(new TestServlet(manager)), "/");
        server.setHandler(web.getCoreContextHandler());
        try {
            server.start();
            String first = request(connector, "GET", "/i2psnark/", "");
            check(first.startsWith("HTTP/1.1 200"), "main torrent page renders with peer sidebar");
            check(first.contains("id=\"peer-catalog\"") && first.contains("id=\"catalog-results\""), "search sidebar is present");
            check(first.contains(".resources/catalog.css") && first.contains(".resources/js/catalog.js"), "sidebar assets are loaded");
            String sharing = first.substring(first.indexOf("<details id=\"catalog-sharing\""), first.indexOf("</details>"));
            String hash = I2PSnarkUtil.toHex(publicTorrent.getInfoHash()).toLowerCase(java.util.Locale.US);
            check(sharing.contains("share_" + hash) && !sharing.contains("Private local torrent"), "only public torrents have share controls");
            check(!manager.getPeerCatalog().isShared(publicTorrent.getInfoHash()), "sharing starts disabled");
            String nonce = nonce(first);
            request(connector, "GET", "/i2psnark/?action=CatalogShare&nonce=" + nonce + "&share_" + hash + "=on", "");
            check(!manager.getPeerCatalog().isShared(publicTorrent.getInfoHash()), "GET cannot enable sharing even with a valid nonce");
            request(connector, "POST", "/i2psnark/_post", "action=CatalogShare&nonce=invalid&share_" + hash + "=on");
            check(!manager.getPeerCatalog().isShared(publicTorrent.getInfoHash()), "invalid nonce cannot enable sharing");
            String otherCookie = cookie;
            cookie = null;
            request(connector, "POST", "/i2psnark/_post", "action=CatalogShare&nonce=" + nonce + "&share_" + hash + "=on");
            check(!manager.getPeerCatalog().isShared(publicTorrent.getInfoHash()), "nonce is bound to its browser session");
            cookie = otherCookie;
            nonce = nonce(request(connector, "GET", "/i2psnark/", ""));
            String privateHash = I2PSnarkUtil.toHex(privateTorrent.getInfoHash()).toLowerCase(java.util.Locale.US);
            String saved = request(connector, "POST", "/i2psnark/_post", "action=CatalogShare&nonce=" + nonce +
                    "&share_" + hash + "=on&share_" + privateHash + "=on");
            check(saved.startsWith("HTTP/1.1 302") || saved.startsWith("HTTP/1.1 303"), "sharing changes use post-redirect-get");
            check(manager.getPeerCatalog().isShared(publicTorrent.getInfoHash()) &&
                    !manager.getPeerCatalog().isShared(privateTorrent.getInfoHash()), "POST persists only a valid public selection");
            SnarkManager reloaded = new SnarkManager(context);
            reloaded.loadConfig("i2psnark.config");
            check(reloaded.getPeerCatalog().isShared(publicTorrent.getInfoHash()), "explicit sharing selection survives configuration reload");
            RemotePeer remote = new RemotePeer(manager, publicTorrent.getMetaInfo());
            publicTorrent.peers = List.of(remote);
            nonce = nonce(request(connector, "GET", "/i2psnark/", ""));
            request(connector, "POST", "/i2psnark/_post", "action=CatalogRefresh&nonce=" + nonce);
            check(remote.requests == 1, "explicit refresh starts one peer request");
            String response = request(connector, "GET", "/i2psnark/.ajax/catalog.html?nf_catalogq=unsafe", "");
            check(response.startsWith("HTTP/1.1 200") && response.contains("Cache-Control: no-store"), "cached results are served without storage caching");
            check(response.contains("&lt;img") && !response.contains("<img src=x onerror"), "peer-controlled names are HTML escaped");
            check(response.contains("?nofilter_newURL=magnet%3A") && response.contains("Review torrent"), "results link to review rather than automatic download");
            request(connector, "GET", "/i2psnark/.ajax/catalog.html?nf_catalogq=different", "");
            check(remote.requests == 1, "polling and searching never initiate peer requests");
            check(manager.getTorrents().size() == 2, "receiving listings never adds a torrent");
            nonce = nonce(request(connector, "GET", "/i2psnark/", ""));
            request(connector, "POST", "/i2psnark/_post", "action=CatalogShare&nonce=" + nonce);
            check(!manager.getPeerCatalog().isShared(publicTorrent.getInfoHash()), "unchecked selection disables sharing");
            response = request(connector, "POST", "/i2psnark/.ajax/catalog.html", "action=CatalogRefresh");
            check(response.startsWith("HTTP/1.1 405"), "read-only cache endpoint refuses POST");
            System.out.println("PEER_CATALOG_HTTP_CHECKS=" + checks);
        } finally {
            server.stop();
            server.destroy();
            manager.getPeerCatalog().stop();
            context.logManager().shutdown();
        }
    }

    private static final class TestServlet extends I2PSnarkServlet {
        private final SnarkManager manager;
        private ServletConfig config;
        TestServlet(SnarkManager manager) { this.manager = manager; }
        @Override public void init(ServletConfig config) throws ServletException {
            this.config = config;
            try {
                field(I2PSnarkServlet.class, "_manager").set(this, manager);
                field(I2PSnarkServlet.class, "_contextPath").set(this, "/i2psnark");
                field(I2PSnarkServlet.class, "_contextName").set(this, "i2psnark");
                setResourceBase(manager.getDataDir());
                setWarBase("/.resources/");
            } catch (Exception failure) { throw new ServletException(failure); }
        }
        @Override public ServletConfig getServletConfig() { return config; }
        @Override public ServletContext getServletContext() { return config.getServletContext(); }
        @Override public void destroy() { }
    }

    private static final class TestContext extends I2PAppContext {
        TestContext(Path work) { super(false, properties(work)); }
        @Override public boolean isRouterContext() { return true; }
    }

    private static Properties properties(Path work) {
        Properties props = new Properties();
        props.setProperty("i2p.dir.config", work.toString());
        props.setProperty("i2p.dir.base", work.toString());
        props.setProperty("routerconsole.lang", "en");
        return props;
    }

    private static final class StubSnark extends Snark {
        final MetaInfo info;
        final Storage storage;
        List<Peer> peers = List.of();
        StubSnark(SnarkManager manager, MetaInfo info) {
            super(manager.util(), info.getName() + ".torrent", info.getInfoHash(), null, null, null, null, null);
            this.info = info;
            storage = new Storage(manager.util(), new java.io.File(manager.getDataDir(), info.getName()), info, null, true);
        }
        @Override public MetaInfo getMetaInfo() { return info; }
        @Override public Storage getStorage() { return storage; }
        @Override public boolean isStopped() { return false; }
        @Override public String getBaseName() { return info.getName(); }
        @Override public List<Peer> getPeerList() { return peers; }
        @Override public long getNeededLength() { return 1; }
    }

    private static final class RemotePeer extends Peer {
        final SnarkManager manager;
        int requests;
        RemotePeer(SnarkManager manager, MetaInfo info) throws Exception {
            super(new PeerID(new byte[32], manager.util()), new byte[20], info.getInfoHash(), info);
            this.manager = manager;
            setHandshakeMap(Map.of("m", new BEValue(Map.of(PeerCatalog.EXTENSION, new BEValue(5)))));
        }
        @Override public boolean isConnected() { return true; }
        @Override public void sendExtension(int id, byte[] bytes) {
            try {
                requests++;
                Map<String, BEValue> request = new BDecoder(new ByteArrayInputStream(bytes), PeerCatalog.MAX_MESSAGE).bdecodeMap().getMap();
                byte[] response = BEncoder.bencode(Map.of("v", 1, "msg_type", 1, "id", request.get("id").getBytes(),
                        "page", 0, "more", 0, "items", List.of(Map.of("ih", new byte[20], "name", "unsafe <img src=x onerror=alert(1)>"))));
                manager.getPeerCatalog().receive(this, response);
            } catch (Throwable failure) { throw new AssertionError(failure); }
        }
    }

    private static String request(LocalConnector connector, String method, String path, String body) throws Exception {
        String headers = method + ' ' + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n";
        if (cookie != null) headers += "Cookie: " + cookie + "\r\n";
        if (method.equals("POST")) headers += "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n";
        String response = connector.getResponse(headers + "\r\n" + body);
        Matcher setCookie = Pattern.compile("(?im)^Set-Cookie: ([^;\r\n]+)").matcher(response);
        if (setCookie.find()) cookie = setCookie.group(1);
        return response;
    }

    private static String nonce(String response) {
        Matcher match = Pattern.compile("name=\"nonce\" value=\"([^\"]+)\"").matcher(response);
        if (!match.find()) throw new AssertionError("no form nonce in response: " + response.substring(0, Math.min(500, response.length())));
        return match.group(1);
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean success, String message) { if (!success) throw new AssertionError(message); checks++; }
}
