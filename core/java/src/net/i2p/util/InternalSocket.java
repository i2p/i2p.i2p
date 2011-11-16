package net.i2p.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

/**
 *  A simple in-JVM Socket using Piped Streams.
 *  We use port numbers just like regular sockets.
 *  Can only connect to InternalServerSocket.
 * @since 0.7.9
 */
public class InternalSocket extends Socket {
    private InputStream _is;
    private OutputStream _os;

    /** server side */
    InternalSocket(InputStream is, OutputStream os) {
        _is = is;
        _os = os;
    }

    /**
     *  client side
     *  @param port > 0
     */
    public InternalSocket(int port) throws IOException {
         if (port <= 0)
             throw new IOException("bad port number");
         InternalServerSocket.internalConnect(port, this);
    }

    /**
     *  Convenience method to return either a Socket or an InternalSocket
     *  @param port > 0
     */
    public static Socket getSocket(String host, int port) throws IOException {
        if (System.getProperty("router.version") != null &&
            (host.equals("127.0.0.1") || host.equals("localhost"))) {
            try {
                return new InternalSocket(port);
            } catch (IOException ioe) {}
            // guess it wasn't really internal...
        }
        return new Socket(host, port);
    }

    @Override
    public InputStream getInputStream() {
        return _is;
    }

    @Override
    public OutputStream getOutputStream() {
        return _os;
    }

    void setInputStream(InputStream is) {
        _is = is;
    }

    void setOutputStream(OutputStream os) {
        _os = os;
    }

    @Override
    public void close() {
        try {
            if (_is != null) {
                _is.close();
                _is = null;
            }
        } catch (IOException ie) {}
        try {
            if (_os != null) {
                _os.close();
                _os = null;
            }
        } catch (IOException ie) {}
    }

    @Override
    public boolean isClosed() {
        return _is == null || _os == null;
    }

    @Override
    public String toString() {
        return ("Internal socket");
    }

    // ignored stuff
    /** warning - unsupported */
    @Override
    public void setSoTimeout(int timeout) {}

    @Override
    public int getSoTimeout () {
        return 0;
    }

    // everything below here unsupported
    /** @deprecated unsupported */
    @Override
    public void bind(SocketAddress endpoint) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void connect(SocketAddress endpoint) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void connect(SocketAddress endpoint, int timeout) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public SocketChannel getChannel() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public InetAddress getInetAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean getKeepAlive() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public InetAddress getLocalAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getLocalPort() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public SocketAddress getLocalSocketAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean getOOBInline() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getPort() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getReceiveBufferSize() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public SocketAddress getRemoteSocketAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean getReuseAddress() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getSendBufferSize() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getSoLinger() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean getTcpNoDelay() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public int getTrafficClass() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean isBound() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean isConnected() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean isInputShutdown() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public boolean isOutputShutdown() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void sendUrgentData(int data) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setKeepAlive(boolean on) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setOOBInline(boolean on) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setReceiveBufferSize(int size) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setReuseAddress(boolean on) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setSendBufferSize(int size) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setSoLinger(boolean on, int linger) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setTcpNoDelay(boolean on) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void setTrafficClass(int cize) {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void shutdownInput() {
        throw new IllegalArgumentException("unsupported");
    }
    /** @deprecated unsupported */
    @Override
    public void shutdownOutput() {
        throw new IllegalArgumentException("unsupported");
    }
}
