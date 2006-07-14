package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *
 */
public class EventPumper implements Runnable {
    private RouterContext _context;
    private Log _log;
    private boolean _alive;
    private Selector _selector;
    private List _bufCache;
    private List _wantsRead;
    private List _wantsWrite;
    private List _wantsRegister;
    private List _wantsConRegister;
    private NTCPTransport _transport;
    
    private static final int BUF_SIZE = 8*1024;
    private static final int MAX_CACHE_SIZE = 64;
    
    public EventPumper(RouterContext ctx, NTCPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _alive = false;
        _bufCache = new ArrayList(MAX_CACHE_SIZE);
    }
    
    public void startPumping() {
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting pumper");
        _alive = true;
        _wantsRead = new ArrayList(16);
        _wantsWrite = new ArrayList(4);
        _wantsRegister = new ArrayList(1);
        _wantsConRegister = new ArrayList(4);
        try {
            _selector = Selector.open();
        } catch (IOException ioe) {
            _log.error("Error opening the selector", ioe);
        }
        new I2PThread(this, "NTCP Pumper", true).start();
    }
    
    public void stopPumping() {
        _alive = false;
        if (_selector.isOpen())
            _selector.wakeup();
    }
    
    public void register(ServerSocketChannel chan) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Registering server socket channel");
        synchronized (_wantsRegister) { _wantsRegister.add(chan); }
        _selector.wakeup();
    }
    public void registerConnect(NTCPConnection con) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Registering outbound connection");
        synchronized (_wantsConRegister) { _wantsConRegister.add(con); }
        _selector.wakeup();
    }
    
    public void run() {
        List bufList = new ArrayList(16);
        while (_alive && _selector.isOpen()) {
            try {
                runDelayedEvents(bufList);
                int count = 0;
                try {
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("before select...");
                    count = _selector.select(200);
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error selecting", ioe);
                }
                if (count <= 0)
                    continue;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("select returned " + count);

                Set selected = null;
                try {
                    selected = _selector.selectedKeys();
                } catch (ClosedSelectorException cse) {
                    continue;
                }

                for (Iterator iter = selected.iterator(); iter.hasNext(); ) {
                    try {
                        SelectionKey key = (SelectionKey)iter.next();
                        int ops = key.readyOps();
                        boolean accept = (ops & SelectionKey.OP_ACCEPT) != 0;
                        boolean connect = (ops & SelectionKey.OP_CONNECT) != 0;
                        boolean read = (ops & SelectionKey.OP_READ) != 0;
                        boolean write = (ops & SelectionKey.OP_WRITE) != 0;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("ready ops for : " + key
                                       + " accept? " + accept + " connect? " + connect
                                       + " read? " + read 
                                       + "/" + ((key.interestOps()&SelectionKey.OP_READ)!= 0)
                                       + " write? " + write 
                                       + "/" + ((key.interestOps()&SelectionKey.OP_WRITE)!= 0)
                                       );
                        if (accept) {
                            processAccept(key);
                        }
                        if (connect) {
                            key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);
                            processConnect(key);
                        }
                        if (read) {
                            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                            processRead(key);
                        }
                        if (write) {
                            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                            processWrite(key);
                        }
                    } catch (CancelledKeyException cke) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("key cancelled");
                    }
                }
                selected.clear();
            } catch (RuntimeException re) {
                _log.log(Log.CRIT, "Error in the event pumper", re);
            }
        }
        try {
            if (_selector.isOpen()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Closing down the event pumper with selection keys remaining");
                Set keys = _selector.keys();
                for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
                    SelectionKey key = (SelectionKey)iter.next();
                    try {
                        Object att = key.attachment();
                        if (att instanceof ServerSocketChannel) {
                            ServerSocketChannel chan = (ServerSocketChannel)att;
                            chan.close();
                            key.cancel();
                        } else if (att instanceof NTCPConnection) {
                            NTCPConnection con = (NTCPConnection)att;
                            con.close();
                            key.cancel();
                        }
                    } catch (Exception ke) {
                        _log.error("Error closing key " + key + " on pumper shutdown", ke);
                    }
                }
                _selector.close();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Closing down the event pumper with no selection keys remaining");
            }
        } catch (Exception e) {
            _log.error("Error closing keys on pumper shutdown", e);
        }
        synchronized (_wantsConRegister) { _wantsConRegister.clear(); }
        synchronized (_wantsRead) { _wantsRead.clear(); }
        synchronized (_wantsRegister) { _wantsRegister.clear(); }
        synchronized (_wantsWrite) { _wantsWrite.clear(); }
    }
    
    public void wantsWrite(NTCPConnection con, byte data[]) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestOutbound(data.length, "NTCP write", null, null);//con, buf);
        if (req.getPendingOutboundRequested() > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("queued write on " + con + " for " + data.length);
            con.queuedWrite(buf, req);
        } else {
            // fully allocated
            if (_log.shouldLog(Log.INFO))
                _log.info("fully allocated write on " + con + " for " + data.length);
            con.write(buf);
        }
    }
    /** called by the connection when it has data ready to write (after bw allocation) */
    public void wantsWrite(NTCPConnection con) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Before adding wants to write on " + con);
        synchronized (_wantsWrite) {
            if (!_wantsWrite.contains(con))
                _wantsWrite.add(con);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Wants to write on " + con);
        _selector.wakeup();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("selector awoken for write");
    }
    public void wantsRead(NTCPConnection con) {
        synchronized (_wantsRead) {
            if (!_wantsRead.contains(con))
                _wantsRead.add(con);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("wants to read on " + con);
        _selector.wakeup();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("selector awoken for read");
    }
    
    private static final int MIN_BUFS = 5;
    private static int NUM_BUFS = 5;
    private static int __liveBufs = 0;
    private static int __consecutiveExtra;
    ByteBuffer acquireBuf() {
        if (false) return ByteBuffer.allocate(BUF_SIZE);
        ByteBuffer rv = null;
        synchronized (_bufCache) {
            if (_bufCache.size() > 0)
                rv = (ByteBuffer)_bufCache.remove(0);
        }
        if (rv == null) {
            rv = ByteBuffer.allocate(BUF_SIZE);
            NUM_BUFS = ++__liveBufs;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("creating a new read buffer " + System.identityHashCode(rv) + " with " + __liveBufs + " live: " + rv);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("acquiring existing read buffer " + System.identityHashCode(rv) + " with " + __liveBufs + " live: " + rv);
        }
        return rv;
    }
    
    void releaseBuf(ByteBuffer buf) {
        if (false) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("releasing read buffer " + System.identityHashCode(buf) + " with " + __liveBufs + " live: " + buf);
        buf.clear();
        int extra = 0;
        boolean cached = false;
        synchronized (_bufCache) {
            if (_bufCache.size() < NUM_BUFS) {
                extra = _bufCache.size();
                _bufCache.add(buf);
                cached = true;
                if (extra > 5) {
                    __consecutiveExtra++;
                    if (__consecutiveExtra >= 20) {
                        NUM_BUFS = Math.max(NUM_BUFS - 1, MIN_BUFS);
                        __consecutiveExtra = 0;
                    }
                }
            } else {
                __liveBufs--;
            }
        }
        if (cached && _log.shouldLog(Log.DEBUG))
            _log.debug("read buffer " + System.identityHashCode(buf) + " cached with " + __liveBufs + " live");
    }
    
    private void processAccept(SelectionKey key) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("processing accept");
        ServerSocketChannel servChan = (ServerSocketChannel)key.attachment();
        try {
            SocketChannel chan = servChan.accept();
            chan.configureBlocking(false);
            SelectionKey ckey = chan.register(_selector, SelectionKey.OP_READ);
            NTCPConnection con = new NTCPConnection(_context, _transport, chan, ckey);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("new NTCP connection established: " +con);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Error accepting", ioe);
        }
    }
    
    private void processConnect(SelectionKey key) {
        NTCPConnection con = (NTCPConnection)key.attachment();
        try {
            SocketChannel chan = con.getChannel();
            boolean connected = chan.finishConnect();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("processing connect for " + key + " / " + con + ": connected? " + connected);
            if (connected) {
                con.setKey(key);
                con.outboundConnected();
            } else {
                con.close();
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Error processing connection", ioe);
        }
    }
    
    private void processRead(SelectionKey key) {
        NTCPConnection con = (NTCPConnection)key.attachment();
        ByteBuffer buf = acquireBuf();
        try {
            int read = con.getChannel().read(buf);
            if (read == -1) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("EOF on " + con);
                con.close();
                releaseBuf(buf);
            } else if (read == 0) {
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                releaseBuf(buf);
            } else if (read > 0) {
                byte data[] = new byte[read];
                buf.flip();
                buf.get(data);
                releaseBuf(buf);
                ByteBuffer rbuf = ByteBuffer.wrap(data);
                FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(read, "NTCP read", null, null); //con, buf);
                if (req.getPendingInboundRequested() > 0) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                    con.queuedRecv(rbuf, req);
                } else {
                    // fully allocated
                    key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                    con.recv(rbuf);
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN)) _log.warn("error reading", ioe);
            con.close();
            if (buf != null) releaseBuf(buf);
        } catch (NotYetConnectedException nyce) {
            // ???
        }
    }
    
    private void processWrite(SelectionKey key) {
        int totalWritten = 0;
        int buffers = 0;
        long before = System.currentTimeMillis();
        NTCPConnection con = (NTCPConnection)key.attachment();
        try {
            while (true) {
                ByteBuffer buf = con.getNextWriteBuf();
                if ( (buf != null) && (buf.remaining() > 0) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("writing " + buf.remaining()+"...");
                    int written = con.getChannel().write(buf);
                    totalWritten += written;
                    if (written == 0) {
                        if ( (buf.remaining() > 0) || (con.getWriteBufCount() > 1) ) {
                            if (_log.shouldLog(Log.DEBUG)) _log.debug("done writing, but data remains...");
                            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        } else {
                            if (_log.shouldLog(Log.DEBUG)) _log.debug("done writing, no data remains...");
                        }
                        break;
                    } else if (buf.remaining() > 0) {
                        if (_log.shouldLog(Log.DEBUG)) _log.debug("buffer data remaining...");
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        break;
                    } else {
                        long beforeRem = System.currentTimeMillis();
                        con.removeWriteBuf(buf);
                        long afterRem = System.currentTimeMillis();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("buffer "+ buffers+"/"+written+"/"+totalWritten+" fully written after " +
                                       (beforeRem-before) + ", then removed after " + (afterRem-beforeRem) + "...");
                        //releaseBuf(buf);
                        buf = null;
                        buffers++;
                        //if (buffer time is too much, add OP_WRITe to the interest ops and break?)
                    }
                } else {
                    break;
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN)) _log.warn("error writing", ioe);
            con.close();
        }
        long after = System.currentTimeMillis();
        if (_log.shouldLog(Log.INFO))
            _log.info("Wrote " + totalWritten + " in " + buffers + " buffers on " + con 
                      + " after " + (after-before));
    }
    
    private void runDelayedEvents(List buf) {
        synchronized (_wantsRead) {
            if (_wantsRead.size() > 0) {
                buf.addAll(_wantsRead);
                _wantsRead.clear();
            }
        }
        while (buf.size() > 0) {
            NTCPConnection con = (NTCPConnection)buf.remove(0);
            SelectionKey key = con.getKey();
            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
        }

        synchronized (_wantsWrite) {
            if (_wantsWrite.size() > 0) {
                buf.addAll(_wantsWrite);
                _wantsWrite.clear();
            }
        }
        while (buf.size() > 0) {
            NTCPConnection con = (NTCPConnection)buf.remove(0);
            SelectionKey key = con.getKey();
            try {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } catch (CancelledKeyException cke) {
                // ignore
            }
        }
        
        synchronized (_wantsRegister) {
            if (_wantsRegister.size() > 0) {
                buf.addAll(_wantsRegister);
                _wantsRegister.clear();
            }
        }
        while (buf.size() > 0) {
            ServerSocketChannel chan = (ServerSocketChannel)buf.remove(0);
            try {
                SelectionKey key = chan.register(_selector, SelectionKey.OP_ACCEPT);
                key.attach(chan);
            } catch (ClosedChannelException cce) {
                if (_log.shouldLog(Log.WARN)) _log.warn("Error registering", cce);
            }
        }
        
        synchronized (_wantsConRegister) {
            if (_wantsConRegister.size() > 0) {
                buf.addAll(_wantsConRegister);
                _wantsConRegister.clear();
            }
        }
        while (buf.size() > 0) {
            NTCPConnection con = (NTCPConnection)buf.remove(0);
            try {
                SelectionKey key = con.getChannel().register(_selector, SelectionKey.OP_CONNECT);
                key.attach(con);
                con.setKey(key);
                try {
                    NTCPAddress naddr = con.getRemoteAddress();
                    InetSocketAddress saddr = new InetSocketAddress(naddr.getHost(), naddr.getPort());
                    boolean connected = con.getChannel().connect(saddr);
                    if (connected) {
                        key.interestOps(SelectionKey.OP_READ);
                        processConnect(key);
                    }
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.WARN)) _log.warn("error connecting", ioe);
                    if (ntcpOnly(con)) {
                        _context.shitlist().shitlistRouter(con.getRemotePeer().calculateHash(), "unable to connect: " + ioe.getMessage());
                        con.close(false);
                    } else {
                        _context.shitlist().shitlistRouter(con.getRemotePeer().calculateHash(), "unable to connect: " + ioe.getMessage(), NTCPTransport.STYLE);
                        con.close(true);
                    }
                }
            } catch (ClosedChannelException cce) {
                if (_log.shouldLog(Log.WARN)) _log.warn("Error registering", cce);
            }
        }
        
        long now = System.currentTimeMillis();
        if (_lastExpired + 1000 <= now) {
            expireTimedOut();
            _lastExpired = now;
        }
    }
    
    /**
     * If the other peer only supports ntcp, we should shitlist them when we can't reach 'em,
     * but if they support other transports (eg ssu) we should allow those transports to be
     * tried as well.
     */
    private boolean ntcpOnly(NTCPConnection con) {
        RouterIdentity ident = con.getRemotePeer();
        if (ident == null) return true;
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(ident.calculateHash());
        if (info == null) return true;
        return info.getAddresses().size() == 1;
    }
    
    private long _lastExpired;
    private void expireTimedOut() {
        _transport.expireTimedOut();
    }
}
