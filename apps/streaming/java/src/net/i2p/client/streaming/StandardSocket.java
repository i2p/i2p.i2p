package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

import net.i2p.I2PException;

/**
 * Bridge to I2PSocket.
 *
 * This extends Socket to make porting apps easier.
 * Methods throw IOExceptions like Sockets do, rather than returning
 * null for some methods.
 *
 * StandardSockets are always bound, and always start out connected
 * (unless connectDelay is > 0).
 * You may not create an unbound StandardSocket.
 * Create this through the SocketManager.
 *
 * Todo: Make public and add getPeerDestination() ?
 *
 * @author zzz
 * @since 0.8.4
 */
class StandardSocket extends Socket {
    private final I2PSocket _socket;

    StandardSocket(I2PSocket socket) {
        _socket = socket;
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void bind(SocketAddress bindpoint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        if (_socket.isClosed())
            throw new IOException("Already closed");
        _socket.close();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void connect(SocketAddress endpoint) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void connect(SocketAddress endpoint, int timeout) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @return null always
     */
    @Override
    public SocketChannel getChannel() {
        return _socket.getChannel();
    }

    /**
     *  @return null always
     */
    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        InputStream rv = _socket.getInputStream();
        if (rv != null)
            return rv;
        throw new IOException("No stream");
    }

    @Override
    public boolean getKeepAlive() {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return false;
        return opts.getInactivityAction() == ConnectionOptions.INACTIVITY_ACTION_SEND;
    }

    /**
     *  @return null always
     */
    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    /**
     *  @return the port or 0 if unknown
     */
    @Override
    public int getLocalPort() {
        return _socket.getLocalPort();
    }

    /**
     *  @return null always
     */
    @Override
    public SocketAddress getLocalSocketAddress() {
        return null;
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getOOBInline() {
        return false;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        OutputStream rv = _socket.getOutputStream();
        if (rv != null)
            return rv;
        throw new IOException("No stream");
    }

    /**
     *  @return the port or 0 if unknown
     */
    @Override
    public int getPort() {
        return _socket.getPort();
    }

    @Override
    public int getReceiveBufferSize() {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return 64*1024;
        return opts.getInboundBufferSize();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public SocketAddress getRemoteSocketAddress() {
        throw new UnsupportedOperationException();
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getReuseAddress() {
        return false;
    }

    @Override
    public int getSendBufferSize() {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return 64*1024;
        return opts.getInboundBufferSize();
    }

    @Override
    public int getSoLinger() {
        I2PSocketOptions opts = _socket.getOptions();
        if (opts == null)
            return -1;
        return -1;  // fixme really?
    }

    @Override
    public int getSoTimeout() {
        I2PSocketOptions opts = _socket.getOptions();
        if (opts == null)
            return 0;
        return (int) opts.getReadTimeout();
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getTcpNoDelay() {
        // No option yet. See ConnectionDataReceiver
        return false;
    }

    /**
     *  @return 0 always
     */
    @Override
    public int getTrafficClass() {
        return 0;
    }

    /**
     *  @return true always
     */
    @Override
    public boolean isBound() {
        return true;
    }

    @Override
    public boolean isClosed() {
        return _socket.isClosed();
    }

    @Override
    public boolean isConnected() {
        return !_socket.isClosed();
    }

    @Override
    public boolean isInputShutdown() {
        return _socket.isClosed();
    }

    @Override
    public boolean isOutputShutdown() {
        return _socket.isClosed();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void sendUrgentData(int data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setKeepAlive(boolean on) {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return;
        if (on)
            opts.setInactivityAction(ConnectionOptions.INACTIVITY_ACTION_SEND);
        else
            opts.setInactivityAction(ConnectionOptions.INACTIVITY_ACTION_NOOP);  // DISCONNECT?
    }

    /**
     *  @throws UnsupportedOperationException if on is true
     */
    @Override
    public void setOOBInline(boolean on) {
        if (on)
            throw new UnsupportedOperationException();
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setReceiveBufferSize(int size) {
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setReuseAddress(boolean on) {
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setSendBufferSize(int size) {
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setSoLinger(boolean on, int linger) {
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        I2PSocketOptions opts = _socket.getOptions();
        if (opts == null)
            throw new SocketException("No options");
        opts.setReadTimeout(timeout);
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setTcpNoDelay(boolean on) {
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setTrafficClass(int tc) {
    }

    @Override
    public void shutdownInput() throws IOException {
        close();
    }

    @Override
    public void shutdownOutput() throws IOException {
        close();
    }

    @Override
    public String toString() {
        return _socket.toString();
    }
}
