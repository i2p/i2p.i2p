package net.i2p.sam;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
/* requires Java 7 */
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Simple wrapper for a SSLServerSocket.
 * Cannot be used for asynch ops.
 *
 * @since 0.9.24
 */
class SSLServerSocketChannel extends ServerSocketChannel {

    private final SSLServerSocket _socket;

    public SSLServerSocketChannel(SSLServerSocket socket) {
        super(SelectorProvider.provider());
        _socket = socket;
    }

    //// ServerSocketChannel abstract methods

    public SocketChannel accept() throws IOException {
        return new SSLSocketChannel((SSLSocket)_socket.accept());
    }

    public ServerSocket socket() {
        return _socket;
    }

    /** requires Java 7 */
    public ServerSocketChannel bind(SocketAddress local, int backlog) {
        throw new UnsupportedOperationException();
    }

    /** requires Java 7 */
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
        return this;
    }

    //// AbstractSelectableChannel abstract methods

    public void implCloseSelectableChannel() throws IOException {
        _socket.close();
    }

    public void implConfigureBlocking(boolean block) throws IOException {
        if (!block)
            throw new UnsupportedOperationException();
    }

    //// NetworkChannel interface methods

    public SocketAddress getLocalAddress() {
        return _socket.getLocalSocketAddress();
    }

    public <T> T getOption(SocketOption<T> name) {
        return null;
    }

    public Set<SocketOption<?>> supportedOptions() {
        return Collections.emptySet();
    }
}
