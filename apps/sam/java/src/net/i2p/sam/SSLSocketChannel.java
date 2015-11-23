package net.i2p.sam;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
/* requires Java 7 */
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

import javax.net.ssl.SSLSocket;

/**
 * Simple wrapper for a SSLSocket.
 * Cannot be used for asynch ops.
 *
 * @since 0.9.24
 */
class SSLSocketChannel extends SocketChannel {

    private final SSLSocket _socket;

    public SSLSocketChannel(SSLSocket socket) {
        super(SelectorProvider.provider());
        _socket = socket;
    }

    //// SocketChannel abstract methods

    public Socket socket() {
        return _socket;
    }

    public boolean connect(SocketAddress remote) {
        throw new UnsupportedOperationException();
    }

    public boolean finishConnect() {
        return true;
    }

    public boolean isConnected() {
        return _socket.isConnected();
    }

    public boolean isConnectionPending() {
        return false;
    }

    /** new in Java 7 */
    public SocketAddress getRemoteAddress() {
        return _socket.getRemoteSocketAddress();
    }

    /** new in Java 7 */
    public SocketChannel shutdownInput() throws IOException {
        _socket.getInputStream().close();
        return this;
    }

    /** new in Java 7 */
    public SocketChannel shutdownOutput() throws IOException {
        _socket.getOutputStream().close();
        return this;
    }

    /** requires Java 7 */
    public <T> SocketChannel setOption(SocketOption<T> name, T value) {
        return this;
    }

    /** requires Java 7 */
    public SocketChannel bind(SocketAddress local) {
        throw new UnsupportedOperationException();
    }

    //// SocketChannel abstract methods

    public int read(ByteBuffer src) throws IOException {
        if (!src.hasArray())
            throw new UnsupportedOperationException();
       int pos = src.position();
       int len = src.remaining();
       int read = _socket.getInputStream().read(src.array(), src.arrayOffset() + pos, len);
       if (read > 0)
           src.position(pos + read);
       return read;
    }

    public long read(ByteBuffer[] srcs, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    public int write(ByteBuffer src) throws IOException {
        if (!src.hasArray())
            throw new UnsupportedOperationException();
       int pos = src.position();
       int len = src.remaining();
       _socket.getOutputStream().write(src.array(), src.arrayOffset() + pos, len);
       src.position(pos + len);
       return len;
    }

    public long write(ByteBuffer[] srcs, int offset, int length) {
        throw new UnsupportedOperationException();
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
