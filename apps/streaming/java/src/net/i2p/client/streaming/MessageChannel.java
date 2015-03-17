package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.AbstractSelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *  As this does not (yet) extend SocketChannel it cannot be returned by StandardSocket.getChannel(),
 *  until we implement an I2P SocketAddress class.		
 *
 *  Warning, this interface and implementation is preliminary and subject to change without notice.
 *
 *  @since 0.8.9
 */
public class MessageChannel extends SelectableChannel implements ReadableByteChannel, WritableByteChannel {

    private final MessageInputStream in;
    private final MessageOutputStream out;
    private boolean _isRegistered;
    private SelectionKey whichKey;
    private SelectorProvider provider;
    private Selector sel;
    private Object lock;
    private final I2PSocket socket;

    MessageChannel(I2PSocket socket) {
        try {
            this.socket = socket;
            in = (MessageInputStream) socket.getInputStream();
            out = (MessageOutputStream) socket.getOutputStream();
            in.setReadTimeout(0);
            out.setWriteTimeout(0);
            out.setBufferSize(0x1000);
        } catch (IOException ex) {
            Logger.getLogger(MessageChannel.class.getName()).log(Level.SEVERE, null, ex);
            // dunno what to do with this for now
            throw new RuntimeException(ex);
        }
    }

    @Override
    public SelectorProvider provider() {
        return provider;
    }

    @Override
    public int validOps() {
        return SelectionKey.OP_READ | SelectionKey.OP_WRITE;
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
        final MessageChannel that = this; // lol java
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
                int readyOps = 0;
                if((operations & OP_READ) != 0) {
                    try {
                        // check the input stream
                        if (in.available() > 0) {
                            readyOps |= OP_READ;
                        }
                    } catch (IOException ex) {}
                }
                if((operations & OP_WRITE) != 0) {
                    if(!out.getClosed())
                        readyOps |= OP_WRITE;
                }
                return readyOps;
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
        this.socket.close();
    }

    /* Read no more than buf.remaining()
     * Continue to read until that, or in.read
     * returns 0, which happens when there's
     * no more data available.
     */
    public int read(ByteBuffer buf) throws IOException {
        int amount = 0;
        for (;;) {
            // TODO if buf.hasArray() ... getArray() ... getArrayOffset() ...
            byte[] lbuf = new byte[buf.remaining()];
            int samount = in.read(lbuf);
            if (samount <= 0) {
                this.close();
            }
            if (samount == 0) {
                break;
            }
            amount += samount;
            buf.put(lbuf, 0, samount);
        }
        return amount;
    }

    /* Write in 0x1000 increments, the MessageOutputStream's
     * already set buffer size. Once it starts to fail
     * (wait timeout is 0) then put the bytes back and return.
     */
    public int write(ByteBuffer buf) throws IOException {
        int written = 0;
        for (;;) {
            if(buf.remaining()==0) 
                return written;
            // TODO if buf.hasArray() ... getArray() ... getArrayOffset() ...
            byte[] lbuf = new byte[Math.min(buf.remaining(), 0x1000)];
            buf.get(lbuf);
            try {
                out.write(lbuf, 0, lbuf.length);
                written += lbuf.length;
            } catch(InterruptedIOException ex) {
                buf.put(lbuf);
                return written;
            }
        }
    }
}
