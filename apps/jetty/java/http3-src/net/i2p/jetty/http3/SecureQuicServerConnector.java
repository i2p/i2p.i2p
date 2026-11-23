// Adapted from Eclipse Jetty 12.0.41 ServerQuicConnection and QuicServerConnectionFactory.
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
package net.i2p.jetty.http3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.quic.server.ServerQuicConnection;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

import net.i2p.jetty.QuicTokenManager;

/** Uses authenticated Retry callbacks instead of Jetty's example address tokens. */
public class SecureQuicServerConnector extends QuicServerConnector {
    private final QuicTokenManager tokens = new QuicTokenManager();
    private final Set<QuicSession> sessions = Collections.newSetFromMap(new IdentityHashMap<QuicSession, Boolean>());
    private int maxSessions = 128;

    public SecureQuicServerConnector(Server server, ServerQuicConfiguration configuration, ConnectionFactory... factories) {
        super(server, configuration, factories);
        if (QuicTokenManager.MAX_TOKEN_BYTES > QuicheConnection.TokenMinter.MAX_TOKEN_LENGTH)
            throw new IllegalStateException("QUIC Retry token exceeds the native buffer");
        addEventListener(new SessionBudget());
    }

    public void setMaxSessions(int maximum) {
        if (isRunning()) throw new IllegalStateException("Configure before starting");
        if (maximum <= 0 || maximum > 4096) throw new IllegalArgumentException("Invalid QUIC session limit");
        synchronized (sessions) { maxSessions = maximum; }
    }

    public int getMaxSessions() { synchronized (sessions) { return maxSessions; } }
    public int getOpenSessionCount() { synchronized (sessions) { return sessions.size(); } }

    private class SessionBudget implements Connection.Listener, QuicSession.Listener {
        @Override
        public void onOpened(QuicSession session) {
            synchronized (sessions) {
                if (sessions.size() < maxSessions) { sessions.add(session); return; }
            }
            session.outwardClose(0x0107L, "session limit");
        }

        @Override
        public void onClosed(QuicSession session) {
            synchronized (sessions) { sessions.remove(session); }
        }
    }

    @Override
    protected ServerQuicConnection newConnection(EndPoint endPoint) {
        ServerQuicConfiguration config = getQuicConfiguration();
        ServerQuicConnection connection = new AuthenticatedConnection(this, config, endPoint);
        getEventListeners().forEach(connection::addEventListener);
        connection.setInputBufferSize(config.getInputBufferSize());
        connection.setOutputBufferSize(config.getOutputBufferSize());
        connection.setUseInputDirectByteBuffers(config.isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(config.isUseOutputDirectByteBuffers());
        return connection;
    }

    private class AuthenticatedConnection extends ServerQuicConnection {
        private final ServerQuicConfiguration configuration;
        AuthenticatedConnection(SecureQuicServerConnector connector, ServerQuicConfiguration configuration, EndPoint endPoint) {
            super(connector, configuration, endPoint);
            this.configuration = configuration;
        }

        @Override
        protected QuicSession createSession(SocketAddress remote, ByteBuffer input) throws IOException {
            if (!(remote instanceof InetSocketAddress address) || address.getAddress() == null)
                throw new IOException("QUIC requires an IP datagram peer");
            QuicheConnection accepted = QuicheConnection.tryAccept(nativeConfiguration(),
                    (token, length) -> tokens.validate(address, token, length), input, getLocalInetSocketAddress(), address);
            if (accepted != null) {
                ServerQuicSession session = newQuicSession(remote, accepted);
                session.flush();
                return session;
            }
            RetainableByteBuffer lease = getByteBufferPool().acquire(getOutputBufferSize(), true);
            ByteBuffer output = lease.getByteBuffer();
            int position = BufferUtil.flipToFill(output);
            boolean negotiated;
            try {
                negotiated = QuicheConnection.negotiate((dcid, length) -> tokens.mint(address, dcid, length), input, output);
            } catch (Throwable failure) {
                lease.release();
                throw failure;
            }
            if (!negotiated) {
                lease.release();
                return null;
            }
            BufferUtil.flipToFlush(output, position);
            write(Callback.from(lease::release), remote, output);
            return null;
        }

        private QuicheConfig nativeConfiguration() {
            QuicheConfig result = new QuicheConfig();
            Map<String, Object> implementation = configuration.getImplementationConfiguration();
            result.setPrivKeyPemPath(((Path)implementation.get(QuicConfiguration.PRIVATE_KEY_PEM_PATH_KEY)).toString());
            result.setCertChainPemPath(((Path)implementation.get(QuicConfiguration.CERTIFICATE_CHAIN_PEM_PATH_KEY)).toString());
            Path trust = (Path)implementation.get(QuicConfiguration.TRUSTED_CERTIFICATES_PEM_PATH_KEY);
            if (trust != null) result.setTrustedCertsPemPath(trust.toString());
            var tls = configuration.getSslContextFactory();
            result.setVerifyPeer(tls.getNeedClientAuth() || tls.getWantClientAuth());
            result.setMaxIdleTimeout(0L); // Jetty owns the idle timers.
            result.setInitialMaxData((long)configuration.getSessionRecvWindow());
            result.setInitialMaxStreamDataBidiLocal((long)configuration.getBidirectionalStreamRecvWindow());
            result.setInitialMaxStreamDataBidiRemote((long)configuration.getBidirectionalStreamRecvWindow());
            result.setInitialMaxStreamDataUni((long)configuration.getUnidirectionalStreamRecvWindow());
            result.setInitialMaxStreamsUni((long)configuration.getMaxUnidirectionalRemoteStreams());
            result.setInitialMaxStreamsBidi((long)configuration.getMaxBidirectionalRemoteStreams());
            result.setDisableActiveMigration(configuration.isDisableActiveMigration());
            result.setMaxConnectionWindow((long)configuration.getSessionRecvWindow());
            result.setMaxStreamWindow((long)configuration.getBidirectionalStreamRecvWindow());
            result.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);
            result.setApplicationProtos(new String[] {"h3"});
            return result;
        }
    }
}
