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

public class AcceptingChannelImpl extends AcceptingChannel {
    boolean _isRegistered = false;
    SelectionKey whichKey = null;
    SelectorProvider provider = null;
    Selector sel = null;
    Object lock = null;
    I2PSocket next = null;
    I2PServerSocket socket;

    I2PSocket accept() throws I2PException, ConnectException {
        I2PSocket sock;
        try {
            sock = socket.accept();
        } catch(SocketTimeoutException ex) {
            return null;
        }
        I2PSocket temp = next;
        next = sock;
        return temp;
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
                if((operations & OP_ACCEPT) != 0)
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
        if(next != null) {
            next.close();
        }
        _socketManager.destroySocketManager();
    }
}
