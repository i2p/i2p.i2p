package net.i2p.client.streaming;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;

import net.i2p.I2PException;

/**
 * Bridge to I2PServerSocket.
 *
 * This extends ServerSocket to make porting apps easier.
 * accept() returns a real Socket (a StandardSocket).
 * accept() throws IOExceptions like ServerSockets do, rather than returning
 * null or throwing I2PExceptions.
 *
 * StandardServerSockets are always bound.
 * You may not create an unbound StandardServerSocket.
 * Create this through the SocketManager.
 *
 * @author zzz
 * @since 0.8.4
 */
class StandardServerSocket extends ServerSocket {
    private final I2PServerSocketFull _socket;

    /**
     *  Doesn't really throw IOE but super() does
     */
    StandardServerSocket(I2PServerSocketFull socket) throws IOException {
        _socket = socket;
    }

    @Override
    public Socket accept() throws IOException {
        try {
            I2PSocket sock = _socket.accept();
            if (sock == null)
                throw new IOException("No socket");
            return new StandardSocket(sock);
        } catch (I2PException i2pe) {
            // fixme in 1.6 change to cause
            throw new IOException(i2pe.toString());
        }
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void bind(SocketAddress endpoint) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void bind(SocketAddress endpoint, int backlog) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        if (isClosed())
            throw new IOException("Already closed");
        _socket.close();
    }

    /**
     *  @return null always, see AcceptingChannelImpl for more info
     */
    @Override
    public ServerSocketChannel getChannel() {
        //return _socket.getChannel();
        return null;
    }

    /**
     *  @return null always
     */
    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    /**
     *  @return -1 always
     */
    @Override
    public int getLocalPort() {
        return -1;
    }

    /**
     *  @return null always
     */
    @Override
    public SocketAddress getLocalSocketAddress() {
        return null;
    }

    @Override
    public int getReceiveBufferSize() {
        ConnectionOptions opts = (ConnectionOptions) ((I2PSocketManagerFull)_socket.getManager()).getDefaultOptions();
        if (opts == null)
            return 64*1024;
        return opts.getInboundBufferSize();
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getReuseAddress() {
        return false;
    }

    @Override
    public int getSoTimeout() {
        return (int) _socket.getSoTimeout();
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
        return ((I2PSocketManagerFull)_socket.getManager()).getConnectionManager().getAllowIncomingConnections();
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

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        _socket.setSoTimeout(timeout);
    }

    @Override
    public String toString() {
        return _socket.toString();
    }
}
