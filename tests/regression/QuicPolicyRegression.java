package net.i2p.jetty;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppState;
import net.i2p.router.RouterContext;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.servlet.filters.XI2PLocationFilter;

/** Controlled slow I/O and HTTP lifecycle checks, without network sockets. */
public class QuicPolicyRegression {
    private static int checks, failures;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + message); }
    }

    private static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError(label);
    }

    private static int status(String response) { return Integer.parseInt(response.substring(9, 12)); }
    private static String wire(String path, String headers) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost:7658\r\nConnection: close\r\n" + headers + "\r\n";
    }

    private static Handler successful() {
        return new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                response.write(true, BufferUtil.toBuffer("ok"), callback);
                return true;
            }
        };
    }

    private static class AdvertisementConnector extends ServerConnector {
        AdvertisementConnector(Server server) { super(server, 0, 1); }
        protected void doStart() {}
        protected void doStop() {}
        public int getLocalPort() { return 8443; }
    }

    private static void http3Policy() throws Exception {
        Path directory = Http3Support.privateDirectory(Path.of("quic-private"));
        check(Files.getPosixFilePermissions(directory).equals(Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)), "QUIC PEM directory is owner-only");
        Path link = Path.of("quic-link");
        Files.createSymbolicLink(link, directory);
        try { Http3Support.privateDirectory(link); check(false, "symlink PEM directory rejected"); }
        catch (IOException expected) { check(true, "symlink PEM directory rejected"); }
        Server missing = new Server();
        try {
            Http3Support.createConnector(missing, new SslContextFactory.Server(), "127.0.0.1", 8443, "unused-quic");
            check(false, "missing optional QUIC dependencies fail explicitly");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("HTTP/3 requires matching"), "missing optional QUIC dependencies fail explicitly");
            check(missing.getConnectors().length == 0 && !Files.exists(Path.of("unused-quic")), "failed QUIC setup leaves no half-configured listener or key export");
        } finally { missing.destroy(); }

        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(successful());
        AdvertisementConnector advertised = new AdvertisementConnector(server);
        Http3Support.advertise(server, advertised);
        Handler advertisement = server.getHandler();
        server.setHandler(new Handler.Wrapper(advertisement) {
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                boolean secure = request.getHeaders().get("X-Plain") == null;
                return super.handle(new Request.Wrapper(request) {
                    public boolean isSecure() { return secure; }
                }, response, callback);
            }
        });
        try {
            server.start();
            check(!connector.getResponse(wire("/", "")).contains("Alt-Svc"), "unstarted UDP listener is never advertised");
            advertised.start();
            check(connector.getResponse(wire("/", "")).contains("Alt-Svc: h3=\":8443\"; ma=60"), "direct secure requests receive same-host HTTP/3 advertisement");
            check(!connector.getResponse(wire("/", "X-Plain: 1\r\n")).contains("Alt-Svc"), "plain HTTP is not advertised as secure HTTP/3");
            check(!connector.getResponse(wire("/", "X-I2P-DestHash: client\r\n")).contains("Alt-Svc"), "I2P tunnel clients are not sent to direct UDP");
            check(!connector.getResponse(wire("/", "").replace("localhost:7658", "example.i2p")).contains("Alt-Svc"), "I2P hostnames are not sent to direct UDP");
        } finally { advertised.stop(); server.stop(); server.destroy(); }
    }

    private static void retryTokens() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte)7);
        long[] clock = {1_000_000};
        QuicTokenManager manager = new QuicTokenManager(key, () -> clock[0]);
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 12345);
        byte[] cid = new byte[20];
        for (int i = 0; i < cid.length; i++) cid[i] = (byte)i;
        byte[] token = manager.mint(peer, cid, cid.length);
        check(token.length <= 48, "authenticated Retry token fits the native QUIC buffer");
        check(Arrays.equals(manager.validate(peer, token, token.length), cid), "valid Retry token returns the original connection ID");
        check(manager.validate(new InetSocketAddress("127.0.0.2", 12345), token, token.length) == null, "Retry token is bound to client IP");
        check(manager.validate(new InetSocketAddress("127.0.0.1", 12346), token, token.length) == null, "Retry token is bound to client UDP port");
        boolean rejected = true;
        for (int i = 0; i < token.length; i++) {
            byte[] tampered = token.clone();
            tampered[i] ^= 1;
            rejected &= manager.validate(peer, tampered, tampered.length) == null;
        }
        check(rejected, "every single-byte token modification is rejected");
        byte[] otherKey = key.clone(); otherKey[0] ^= 1;
        check(new QuicTokenManager(otherKey, () -> clock[0]).validate(peer, token, token.length) == null,
              "one listener's Retry token cannot authenticate to another listener");
        clock[0] += 59000;
        check(Arrays.equals(manager.validate(peer, token, token.length), cid), "Retry token remains valid within its lifetime");
        clock[0] += 1001;
        check(manager.validate(peer, token, token.length) == null, "expired Retry tokens are rejected");
        clock[0] = 999999;
        check(manager.validate(peer, token, token.length) == null, "tokens outside the allowed future window are rejected");
        boolean bounded = true;
        for (int length = -1; length <= 100; length++)
            bounded &= manager.validate(peer, token, length) == null || length == token.length;
        check(bounded, "malformed token lengths cannot bypass validation or access outside the buffer");
        InetSocketAddress ipv6 = new InetSocketAddress("::1", 12345);
        byte[] six = manager.mint(ipv6, cid, cid.length);
        check(Arrays.equals(manager.validate(ipv6, six, six.length), cid), "IPv6 addresses support authenticated Retry");
        check(manager.validate(peer, six, six.length) == null, "IPv4 and IPv6 Retry identities remain distinct");
    }

    public static void main(String[] args) throws Exception {
        http3Policy(); retryTokens();
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
