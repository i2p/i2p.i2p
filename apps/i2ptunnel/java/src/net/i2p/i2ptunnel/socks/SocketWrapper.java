package net.i2p.i2ptunnel.socks;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SelectableChannel;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;

/**
 *  Wrapper around the Socket obtained from the Outproxy, which is a
 *  wrapper around the Orchid Stream.
 *
 *  @since 0.9.27
 */
class SocketWrapper implements I2PSocket {

    private final Socket socket;

    private static final Destination DUMMY_DEST = new Destination();
    static {
        try {
           DUMMY_DEST.fromByteArray(new byte[387]);
        } catch (DataFormatException dfe) {
           throw new RuntimeException(dfe);
        }
    }

    public SocketWrapper(Socket sock) {
        socket = sock;
    }

    /**
     * @return the Destination of this side of the socket.
     */
    public Destination getThisDestination() {
        return DUMMY_DEST;
    }

    /**
     * @return the destination of the peer.
     */
    public Destination getPeerDestination() {
        return DUMMY_DEST;
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    /**
     *  @return null always
     */
    @Deprecated
    public SelectableChannel getChannel() {
        return null;
    }

    /**
     *  @return null always
     */
    public I2PSocketOptions getOptions() {
        return null;
    }

    /** 
     * Does nothing
     */
    public void setOptions(I2PSocketOptions options) {}
    
    public long getReadTimeout() {
        return -1;
    }

    public void setReadTimeout(long ms) {}

    public void close() throws IOException {
        socket.close();
    }

    /** 
     * Just calls close()
     * @since 0.9.30
     */
    public void reset() throws IOException {
        close();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    /**
     *  Deprecated, unimplemented, does nothing
     */
    public void setSocketErrorListener(SocketErrorListener lsnr) {}

    /**
     *  The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     */
    public int getPort() {
        try {
            return socket.getPort();
        } catch (UnsupportedOperationException uoe) {
            // prior to 1.2.2-0.2
            return 0;
        }
    }

    /**
     *  The local port.
     *  @return 0 always
     */
    public int getLocalPort() {
        return 0;
    }
}
