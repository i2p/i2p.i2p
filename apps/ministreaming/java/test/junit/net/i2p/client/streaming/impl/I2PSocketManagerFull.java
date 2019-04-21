package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.data.Destination;

/**
 * Stub for testing I2PSocketManagerFactory.
 *
 * @author str4d
 */
public class I2PSocketManagerFull implements I2PSocketManager {
    private I2PSession _session;
    private Properties _opts;
    private String _name;

    /**
     * This is what I2PSocketManagerFactory.createManager() returns.
     * Direct instantiation by others is deprecated.
     * 
     * @param context non-null
     * @param session non-null
     * @param opts may be null
     * @param name non-null
     */
    public I2PSocketManagerFull(I2PAppContext context, I2PSession session, Properties opts, String name,
                IncomingConnectionFilter connectionFilter) {
        _session = session;
        _opts = opts;
        _name = name;
    }

    @Override
    public I2PSession getSession() {
        return _session;
    }

    public Properties getOpts() {
        return _opts;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public I2PSession addSubsession(InputStream privateKeyStream,
            Properties opts) throws I2PSessionException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeSubsession(I2PSession session) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<I2PSession> getSubsessions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAcceptTimeout(long ms) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getAcceptTimeout() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDefaultOptions(I2PSocketOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public I2PSocketOptions getDefaultOptions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public I2PServerSocket getServerSocket() {
        throw new UnsupportedOperationException();
    }

    @Override
    public I2PSocketOptions buildOptions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public I2PSocketOptions buildOptions(Properties opts) {
        throw new UnsupportedOperationException();
    }

    @Override
    public I2PSocket connect(Destination peer, I2PSocketOptions options)
            throws I2PException, ConnectException, NoRouteToHostException,
            InterruptedIOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public I2PSocket connect(Destination peer) throws I2PException,
            ConnectException, NoRouteToHostException, InterruptedIOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroySocketManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDestroyed() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<I2PSocket> listSockets() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean ping(Destination peer, long timeoutMs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean ping(Destination peer, int localPort, int remotePort,
            long timeoutMs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] ping(Destination peer, int localPort, int remotePort,
            long timeoutMs, byte[] payload) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setName(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void init(I2PAppContext context, I2PSession session,
            Properties opts, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addDisconnectListener(DisconnectListener lsnr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeDisconnectListener(DisconnectListener lsnr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServerSocket getStandardServerSocket() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket connectToSocket(Destination peer) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket connectToSocket(Destination peer, int timeout)
            throws IOException {
        throw new UnsupportedOperationException();
    }

}
