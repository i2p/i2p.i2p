package net.i2p.client.streaming;

import net.i2p.I2PException;
import java.net.ConnectException;
import java.nio.channels.SelectableChannel;

/**
 *  As this does not (yet) extend ServerSocketChannel it cannot be returned by StandardServerSocket.getChannel(),
 *  until we implement an I2P SocketAddress class.		
 *
 *  Warning, this interface and implementation is preliminary and subject to change without notice.
 *
 *  Unimplemented, unlikely to ever be implemented.
 *
 *  @since 0.8.11
 */
public abstract class AcceptingChannel extends SelectableChannel {

    protected abstract I2PSocket accept() throws I2PException, ConnectException;

    protected final I2PSocketManager _socketManager;

    protected AcceptingChannel(I2PSocketManager manager) {
        this._socketManager = manager;
    }
}
