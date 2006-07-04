package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.*;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 *
 */
public class NTCPTransport extends TransportImpl {
    private Log _log;
    private SharedBid _fastBid;
    private SharedBid _slowBid;
    private Object _conLock;
    private Map _conByIdent;
    private NTCPAddress _myAddress;
    private EventPumper _pumper;
    private Reader _reader;
    private net.i2p.router.transport.ntcp.Writer _writer;
    /**
     * list of NTCPConnection of connections not yet established that we
     * want to remove on establishment or close on timeout
     */
    private List _establishing;

    private List _sent;
    private SendFinisher _finisher;
    
    public NTCPTransport(RouterContext ctx) {
        super(ctx);
        
        _log = ctx.logManager().getLog(getClass());

        _context.statManager().createRateStat("ntcp.sendTime", "Total message lifetime when sent completely", "ntcp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("ntcp.transmitTime", "How long after message preparation before the message was fully sent", "ntcp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("ntcp.sendQueueSize", "How many messages were ahead of the current one on the connection's queue when it was first added", "ntcp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("ntcp.receiveTime", "How long it takes to receive an inbound message", "ntcp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("ntcp.receiveSize", "How large the received message was", "ntcp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("ntcp.sendBacklogTime", "How long the head of the send queue has been waiting when we fail to add a new one to the queue (period is the number of messages queued)", "ntcp", new long[] { 60*1000, 10*60*1000 });
        
        _establishing = new ArrayList(4);
        _conLock = new Object();
        _conByIdent = new HashMap(64);
        
        _sent = new ArrayList(4);
        _finisher = new SendFinisher();
        
        _pumper = new EventPumper(ctx, this);
        _reader = new Reader(ctx);
        _writer = new net.i2p.router.transport.ntcp.Writer(ctx);
        
        _fastBid = new SharedBid(25); // best
        _slowBid = new SharedBid(70); // better than ssu unestablished, but not better than ssu established
    }
    
    void inboundEstablished(NTCPConnection con) {
        _context.shitlist().unshitlistRouter(con.getRemotePeer().calculateHash());
        NTCPConnection old = null;
        synchronized (_conLock) {
            old = (NTCPConnection)_conByIdent.put(con.getRemotePeer().calculateHash(), con);
        }
        if (old != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Old connection closed: " + old + " replaced by " + con);
            old.close();
        }
    }
    
    protected void outboundMessageReady() {
        OutNetMessage msg = getNextMessage();
        if (msg != null) {
            RouterIdentity ident = msg.getTarget().getIdentity();
            Hash ih = ident.calculateHash();
            NTCPConnection con = null;
            boolean isNew = false;
            synchronized (_conLock) {
                con = (NTCPConnection)_conByIdent.get(ih);
                if (con == null) {
                    isNew = true;
                    RouterAddress addr = msg.getTarget().getTargetAddress(STYLE);
                    if (addr != null) {
                        NTCPAddress naddr = new NTCPAddress(addr);
                        con = new NTCPConnection(_context, this, ident, naddr);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Send on a new con: " + con + " at " + addr + " for " + ih.toBase64());
                        _conByIdent.put(ih, con);
                    } else {
                        _log.error("we bid on a peer who doesn't have an ntcp address? " + msg.getTarget());
                        return;
                    }
                }
            }
            if (isNew) {
                con.enqueueInfoMessage(); // enqueues a netDb store of our own info
                con.send(msg); // doesn't do anything yet, just enqueues it

                try {
                    SocketChannel channel = SocketChannel.open();
                    con.setChannel(channel);
                    channel.configureBlocking(false);
                    _pumper.registerConnect(con);
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error opening a channel", ioe);
                    con.close();
                }
            } else {
                con.send(msg);
            }
            /*
            NTCPConnection con = getCon(ident);
            remove the race here
            if (con != null) {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Send on an existing con: " + con);
                con.send(msg);
            } else {
                RouterAddress addr = msg.getTarget().getTargetAddress(STYLE);
                if (addr != null) {
                    NTCPAddress naddr = new NTCPAddress(addr);
                    con = new NTCPConnection(_context, this, ident, naddr);
                    Hash ih = ident.calculateHash();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Send on a new con: " + con + " at " + addr + " for " + ih.toBase64());
                    NTCPConnection old = null;
                    synchronized (_conLock) {
                        old = (NTCPConnection)_conByIdent.put(ih, con);
                    }
                    if (old != null) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Multiple connections on out ready, closing " + old + " and keeping " + con);
                        old.close();
                    }
                    con.enqueueInfoMessage(); // enqueues a netDb store of our own info
                    con.send(msg); // doesn't do anything yet, just enqueues it
     
                    try {
                        SocketChannel channel = SocketChannel.open();
                        con.setChannel(channel);
                        channel.configureBlocking(false);
                        _pumper.registerConnect(con);
                    } catch (IOException ioe) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Error opening a channel", ioe);
                        con.close();
                    }
                } else {
                    con.close();
                }
            }
             */
        }
    }
    
    public void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue, long msToSend) {
        super.afterSend(msg, sendSuccessful, allowRequeue, msToSend);
    }
    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        if (_context.shitlist().isShitlisted(toAddress.getIdentity().calculateHash(), STYLE)) {
            // we aren't shitlisted in general (since we are trying to get a bid), but we have
            // recently shitlisted the peer on the NTCP transport, so don't try it
            return null;
        }
        RouterAddress addr = toAddress.getTargetAddress(STYLE);
        
        //if ( (_myAddress != null) && (_myAddress.equals(addr)) ) 
        //    return null; // dont talk to yourself
    
        boolean established = isEstablished(toAddress.getIdentity());
        if (established) // should we check the queue size?  nah, if its valid, use it
            return _fastBid;
        else if (addr != null)
            return _slowBid;
        else
            return null;
    }
    
    void sendComplete(OutNetMessage msg) { _finisher.add(msg); }
    /** async afterSend call, which can take some time w/ jobs, etc */
    private class SendFinisher implements SimpleTimer.TimedEvent {
        public void add(OutNetMessage msg) {
            synchronized (_sent) { _sent.add(msg); }
            SimpleTimer.getInstance().addEvent(SendFinisher.this, 0);
        }
        public void timeReached() {
            int pending = 0;
            OutNetMessage msg = null;
            synchronized (_sent) {
                pending = _sent.size()-1;
                if (pending >= 0)
                    msg = (OutNetMessage)_sent.remove(0);
            }
            if (msg != null)
                afterSend(msg, true, false, msg.getSendTime());
            if (pending > 0)
                SimpleTimer.getInstance().addEvent(SendFinisher.this, 0);
        }
    }

    private boolean isEstablished(RouterIdentity peer) {
        synchronized (_conLock) {
            NTCPConnection con = (NTCPConnection)_conByIdent.get(peer.calculateHash());
            return (con != null) && con.isEstablished() && !con.isClosed();
        }
    }
    
    void removeCon(NTCPConnection con) {
        NTCPConnection removed = null;
        synchronized (_conLock) {
            RouterIdentity ident = con.getRemotePeer();
            if (ident != null)
                removed = (NTCPConnection)_conByIdent.remove(ident.calculateHash());
        }
        if ( (removed != null) && (removed != con) ) {// multiple cons, close 'em both
            if (_log.shouldLog(Log.ERROR))
                _log.error("Multiple connections on remove, closing " + removed + " (already closed " + con + ")");
            removed.close();
        }
    }
    
    /**
     * How many peers can we talk to right now?
     *
     */
    public int countActivePeers() { synchronized (_conLock) { return _conByIdent.size(); } }
    /**
     * How many peers are we actively sending messages to (this minute)
     */
    public int countActiveSendPeers() {
        int active = 0;
        synchronized (_conLock) {
            for (Iterator iter = _conByIdent.values().iterator(); iter.hasNext(); ) {
                NTCPConnection con = (NTCPConnection)iter.next();
                if (con.getTimeSinceSend() <= 60*1000)
                    active++;
            }
        }
        return active;
    }
    
    private static final int NUM_CONCURRENT_READERS = 3;
    private static final int NUM_CONCURRENT_WRITERS = 3;
    
    public RouterAddress startListening() {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Starting ntcp transport listening");
        _pumper.startPumping();
        
        _reader.startReading(NUM_CONCURRENT_READERS);
        _writer.startWriting(NUM_CONCURRENT_WRITERS);
        
        configureLocalAddress();

        if (_myAddress != null) {
            try {
                ServerSocketChannel chan = ServerSocketChannel.open();
                chan.configureBlocking(false);

                InetSocketAddress addr = null;
                //if (bindAllInterfaces())
                    addr = new InetSocketAddress(_myAddress.getPort());
                //else
                //    addr = new InetSocketAddress(_myAddress.getAddress(), _myAddress.getPort());
                chan.socket().bind(addr);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Listening on " + addr);
                _pumper.register(chan);
            } catch (IOException ioe) {
                _log.error("Error listening", ioe);
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Outbound NTCP connections only - no listener configured");
        }
        
        if (_myAddress != null) {
            RouterAddress rv = _myAddress.toRouterAddress();
            if (rv != null)
                replaceAddress(rv);
            return rv;
        } else {
            return null;
        }
    }
    
    Reader getReader() { return _reader; }
    net.i2p.router.transport.ntcp.Writer getWriter() { return _writer; }
    public String getStyle() { return STYLE; }
    EventPumper getPumper() { return _pumper; }
    
    /**
     * how long from initial connection attempt (accept() or connect()) until
     * the con must be established to avoid premature close()ing
     */
    private static final int ESTABLISH_TIMEOUT = 10*1000;
    /** add us to the establishment timeout process */
    void establishing(NTCPConnection con) {
        synchronized (_establishing) {
            _establishing.add(con);
        }
    }
    /**
     * called in the EventPumper no more than once a second or so, closing
     * any unconnected/unestablished connections
     */
    void expireTimedOut() {
        List expired = null;
        synchronized (_establishing) {
            for (int i = 0; i < _establishing.size(); i++) {
                NTCPConnection con = (NTCPConnection)_establishing.get(i);
                if (con.isClosed()) {
                    _establishing.remove(i);
                    i--;
                } else if (con.isEstablished()) {
                    _establishing.remove(i);
                    i--;
                } else if (con.getTimeSinceCreated() > ESTABLISH_TIMEOUT) {
                    _establishing.remove(i);
                    i--;
                    if (expired == null)
                        expired = new ArrayList(2);
                    expired.add(con);
                }
            }
        }
        for (int i = 0; expired != null && i < expired.size(); i++)
            ((NTCPConnection)expired.get(i)).close();
    }
    
    //private boolean bindAllInterfaces() { return true; }
    
    private void configureLocalAddress() {
        RouterContext ctx = getContext();
        if (ctx == null) {
            System.err.println("NIO transport has no context?");
        } else {
            RouterAddress ra = CommSystemFacadeImpl.createNTCPAddress(ctx);
            if (ra != null) {
                _myAddress = new NTCPAddress(ra);
                replaceAddress(ra);
                if (_log.shouldLog(Log.INFO))
                    _log.info("NTCP address configured: " + _myAddress);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("NTCP address is outbound only");
            }
        }
    }
    
    public void stopListening() {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Stopping ntcp transport");
        _pumper.stopPumping();
        _writer.stopWriting();
        _reader.stopReading();
        Map cons = null;
        synchronized (_conLock) {
            cons = new HashMap(_conByIdent);
            _conByIdent.clear();
        }
        for (Iterator iter = cons.keySet().iterator(); iter.hasNext(); ) {
            NTCPConnection con = (NTCPConnection)iter.next();
            con.close();
        }
    }
    public static final String STYLE = "NTCP";
    
    public void renderStatusHTML(java.io.Writer out, int sortFlags) throws IOException {}
    public void renderStatusHTML(java.io.Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet peers = new TreeSet(getComparator(sortFlags));
        synchronized (_conLock) {
            peers.addAll(_conByIdent.values());
        }
        long offsetTotal = 0;

        int bpsIn = 0;
        int bpsOut = 0;
        long uptimeMsTotal = 0;
        long sendTotal = 0;
        long recvTotal = 0;
        int numPeers = 0;
        
        StringBuffer buf = new StringBuffer(512);
        buf.append("<b id=\"ntcpcon\">NTCP connections: ").append(peers.size()).append("</b><br />\n");
        buf.append("<table border=\"1\">\n");
        buf.append(" <tr><td><b><a href=\"#def.peer\">peer</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">uptime</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">idle</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">sent</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">received</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">out/in</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">out queue</a></b></td>");
        buf.append("     <td><b><a href=\"#def.peer\">skew</a></b></td>");
        buf.append(" </tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            NTCPConnection con = (NTCPConnection)iter.next();
            buf.append("<tr><td>").append(con.getRemotePeer().calculateHash().toBase64().substring(0,8));
            buf.append("</td><td>").append(DataHelper.formatDuration(con.getUptime()));
            buf.append("</td><td>").append(DataHelper.formatDuration(con.getTimeSinceSend()));
            buf.append("/").append(DataHelper.formatDuration(con.getTimeSinceReceive()));
            buf.append("</td><td>").append(con.getMessagesSent());
            buf.append("</td><td>").append(con.getMessagesReceived());
            buf.append("</td><td>").append(formatRate(con.getSendRate()/1024));
            buf.append("/").append(formatRate(con.getRecvRate()/1024)).append("KBps");
            buf.append("</td><td>").append(con.getOutboundQueueSize());
            buf.append("</td><td>").append(DataHelper.formatDuration(con.getClockSkew()));
            buf.append("</td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }

        buf.append("</table>\n");
        out.write(buf.toString());
        buf.setLength(0);
    }
    
    private static NumberFormat _rateFmt = new DecimalFormat("#,#00.00");
    private static String formatRate(float rate) {
        synchronized (_rateFmt) { return _rateFmt.format(rate); }
    }
    
    private Comparator getComparator(int sortFlags) {
        Comparator rv = null;
        switch (Math.abs(sortFlags)) {
            default:
                rv = AlphaComparator.instance();
        }
        if (sortFlags < 0)
            rv = new InverseComparator(rv);
        return rv;
    }
    private static class AlphaComparator extends PeerComparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() { return _instance; }
    }
    private static class InverseComparator implements Comparator {
        private Comparator _comp;
        public InverseComparator(Comparator comp) { _comp = comp; }
        public int compare(Object lhs, Object rhs) {
            return -1 * _comp.compare(lhs, rhs);
        }
    }
    private static class PeerComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) || !(lhs instanceof NTCPConnection) || !(rhs instanceof NTCPConnection)) 
                throw new IllegalArgumentException("rhs = " + rhs + " lhs = " + lhs);
            return compare((NTCPConnection)lhs, (NTCPConnection)rhs);
        }
        protected int compare(NTCPConnection l, NTCPConnection r) {
            // base64 retains binary ordering
            return DataHelper.compareTo(l.getRemotePeer().calculateHash().getData(), r.getRemotePeer().calculateHash().getData());
        }
    }
    
    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        public Transport getTransport() { return NTCPTransport.this; }
        public String toString() { return "NTCP bid @ " + getLatencyMs(); }
    }
}
