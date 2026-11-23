package net.i2p.jetty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/** Optional Jetty QUIC adapter. Ordinary builds do not load native QUIC code. */
public final class Http3Support {
    private Http3Support() {}

    /** Configure a UDP listener using the same TLS identity and handler policy. */
    public static AbstractNetworkConnector createConnector(Server server, SslContextFactory.Server tls,
                                                           String host, int port, String pemDirectory) {
        return createConnector(server, tls, host, port, pemDirectory, 128);
    }

    public static AbstractNetworkConnector createConnector(Server server, SslContextFactory.Server tls,
                                                           String host, int port, String pemDirectory, int maxSessions) {
        if (maxSessions <= 0 || maxSessions > 4096)
            throw new IllegalArgumentException("QUIC session limit must be between 1 and 4096");
        if (tls == null || host == null || host.trim().isEmpty() || port <= 0 || port > 65535 ||
            pemDirectory == null || pemDirectory.trim().isEmpty())
            throw new IllegalArgumentException("HTTP/3 requires TLS, an explicit bind host, UDP port and private PEM directory");
        try {
            ClassLoader loader = Http3Support.class.getClassLoader();
            Class<?> configurationType = Class.forName("org.eclipse.jetty.quic.server.ServerQuicConfiguration", true, loader);
            Class<?> factoryType = Class.forName("org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory", true, loader);
            Class<?> connectorType = Class.forName("org.eclipse.jetty.quic.server.QuicServerConnector", true, loader);
            Class<?> sessionType = Class.forName("org.eclipse.jetty.quic.common.QuicSession", true, loader);
            Class<?> secureConnectorType = Class.forName("net.i2p.jetty.http3.SecureQuicServerConnector", true, loader);
            for (Class<?> type : new Class<?>[] {configurationType, factoryType, connectorType, sessionType, secureConnectorType}) {
                if (!Server.getVersion().equals(type.getPackage().getImplementationVersion()))
                    throw new IllegalStateException("HTTP/3 modules must match Jetty " + Server.getVersion());
            }
            Path pem = privateDirectory(Path.of(pemDirectory));
            Object quic = configurationType.getConstructor(SslContextFactory.Server.class, Path.class).newInstance(tls, pem);
            configurationType.getMethod("setSessionRecvWindow", int.class).invoke(quic, 1024 * 1024);
            configurationType.getMethod("setBidirectionalStreamRecvWindow", int.class).invoke(quic, 256 * 1024);
            configurationType.getMethod("setDisableActiveMigration", boolean.class).invoke(quic, true);
            HttpConfiguration http = new HttpConfiguration();
            http.setSendServerVersion(false);
            http.setSendXPoweredBy(false);
            http.setRequestHeaderSize(8192);
            http.setMinRequestDataRate(16);
            http.addCustomizer(new SecureRequestCustomizer());
            ConnectionFactory factory = (ConnectionFactory) factoryType.getConstructor(configurationType, HttpConfiguration.class)
                    .newInstance(quic, http);
            // The HTTP/3 factory initializes protocol-required stream defaults.
            configurationType.getMethod("setMaxBidirectionalRemoteStreams", int.class).invoke(quic, 32);
            configurationType.getMethod("setUnidirectionalStreamRecvWindow", int.class).invoke(quic, 65536);
            Object h3 = factoryType.getMethod("getHTTP3Configuration").invoke(factory);
            h3.getClass().getMethod("setStreamIdleTimeout", long.class).invoke(h3, 15000L);
            h3.getClass().getMethod("setMaxRequestHeadersSize", int.class).invoke(h3, 8192);
            h3.getClass().getMethod("setMaxBlockedStreams", int.class).invoke(h3, 16);
            AbstractNetworkConnector connector = (AbstractNetworkConnector) secureConnectorType
                    .getConstructor(Server.class, configurationType, ConnectionFactory[].class)
                    .newInstance(server, quic, new ConnectionFactory[] {factory});
            connector.setHost(host);
            connector.setPort(port);
            connector.setIdleTimeout(30000);
            connector.setName("I2P HTTP3");
            secureConnectorType.getMethod("setMaxSessions", int.class).invoke(connector, maxSessions);
            return connector;
        } catch (ReflectiveOperationException | LinkageError | IOException unavailable) {
            throw new IllegalStateException("HTTP/3 requires matching Jetty HTTP/3, the I2P secure adapter, QUIC, JNA and native Quiche dependencies; " +
                    "run :apps:jetty:copyHttp3Runtime and install its optional jars", unavailable);
        }
    }

    /** Exported PEM keys must live in an owner-private, non-symlink directory. */
    static Path privateDirectory(Path directory) throws IOException {
        Path path = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)) throw new IOException("QUIC PEM directory must not be a symbolic link");
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid QUIC PEM directory");
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        try {
            Files.setPosixFilePermissions(path, permissions);
            if (!Files.getPosixFilePermissions(path).equals(permissions)) throw new IOException("Unsafe QUIC PEM permissions");
        } catch (UnsupportedOperationException platform) {
            throw new IOException("QUIC PEM directory requires verified owner-only filesystem permissions", platform);
        }
        return path;
    }

    /** Advertise only a running direct UDP service, never to I2P tunnel clients. */
    public static void advertise(Server server, AbstractNetworkConnector connector) {
        Handler.Wrapper advertisement = new Handler.Wrapper(server.getHandler()) {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String name = Request.getServerName(request);
                if (connector.isStarted() && request.isSecure() && name != null &&
                    !name.toLowerCase(Locale.ROOT).endsWith(".i2p") &&
                    request.getHeaders().get("X-I2P-DestHash") == null &&
                    request.getHeaders().get("X-I2P-DestB32") == null && connector.getLocalPort() > 0)
                    response.getHeaders().put("Alt-Svc", "h3=\":" + connector.getLocalPort() + "\"; ma=60");
                return super.handle(request, response, callback);
            }
        };
        server.setHandler(advertisement);
    }

    /** Entry point for an optional Jetty XML file, after its TLS configuration. */
    public static void configure(Server server, SslContextFactory.Server tls, String host, int port, String pemDirectory) {
        if (server.isRunning()) throw new IllegalStateException("Configure HTTP/3 before starting");
        configure(server, tls, host, port, pemDirectory, 128);
    }

    public static void configure(Server server, SslContextFactory.Server tls, String host, int port, String pemDirectory, int maxSessions) {
        if (server.isRunning()) throw new IllegalStateException("Configure HTTP/3 before starting");
        AbstractNetworkConnector connector = createConnector(server, tls, host, port, pemDirectory, maxSessions);
        server.addConnector(connector);
        advertise(server, connector);
    }
}
