package net.i2p.client.streaming;

import net.i2p.I2PException;

import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectionKey;
import java.nio.channels.spi.SelectorProvider;

/**
 *  As this does not (yet) extend ServerSocketChannel it cannot be returned by StandardServerSocket.getChannel(),
 *  until we implement an I2P SocketAddress class.		
 *
 *  Warning, this interface and implementation is preliminary and subject to change without notice.
 *
 *  @since 0.8.11
 */
class AcceptingChannelImpl extends AcceptingChannel {
    private boolean _isRegistered;
    private SelectionKey whichKey;
    private SelectorProvider provider;
    private Selector sel;
    private Object lock;
    private volatile I2PSocket next;
    private final I2PServerSocket socket;

    I2PSocket accept() throws I2PException, ConnectException {
        I2PSocket sock;
        try {
            sock = socket.accept();
        } catch(SocketTimeoutException ex) {
            return null;
        }
        synchronized (this) {
            I2PSocket temp = next;
            next = sock;
            return temp;
        }
    }

    AcceptingChannelImpl(I2PSocketManager manager) {
        super(manager);
        // this cheats and just sets the manager timeout low in order to repeatedly poll it.
        // that means we can "only" accept one new connection every 100 milliseconds.
        socket = manager.getServerSocket();
        socket.setSoTimeout(100);
    }

    @Override
    public SelectorProvider provider() {
        return provider;
    }

    @Override
    public int validOps() {
        return SelectionKey.OP_ACCEPT;
    }

    @Override
    public boolean isRegistered() {
        return _isRegistered;
    }

    @Override
    public SelectionKey keyFor(Selector arg0) {
        return whichKey;
    }

    @Override
    public SelectionKey register(final Selector sel, final int ops, Object lock) throws ClosedChannelException {
        this.sel = sel;
        this.provider = sel.provider();
        this.lock = lock;
        this._isRegistered = true;
        final AcceptingChannel that = this; // lol java
        SelectionKey key = new AbstractSelectionKey() {
            int operations = ops;
            @Override
            public SelectableChannel channel() {
                return that;
            }

            @Override
            public Selector selector() {
                return sel;
            }

            @Override
            public int interestOps() {
                return this.operations;
            }

            @Override
            public SelectionKey interestOps(int ops) {
                this.operations = ops;
                return this;
            }

            @Override
            public int readyOps() {
                if((operations & OP_ACCEPT) != 0) {
                    if(next != null) {
                        return OP_ACCEPT;
                    } else {
                        try {
                            accept(); // ping it again.
                        } catch(I2PException ex) {
                        } catch(ConnectException ex) {}                        
                        if(next != null)
                            return OP_ACCEPT;
                    }
                }
                return 0;
            }
        };
        key.attach(lock);
        // I... THINK this is right?
        sel.keys().add(key);
        return key;
    }

    @Override
    public SelectableChannel configureBlocking(boolean blocking) throws IOException {
        if (blocking == false) {
            return this;
        }
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isBlocking() {
        return false;
    }

    @Override
    public Object blockingLock() {
        return this.lock;
    }

    @Override
    protected void implCloseChannel() throws IOException {
        I2PSocket nxt = next;
        if(nxt != null) {
            nxt.close();
        }
        _socketManager.destroySocketManager();
    }
}
