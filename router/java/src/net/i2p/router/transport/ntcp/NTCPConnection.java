package net.i2p.router.transport.ntcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.Adler32;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Coordinate the connection to a single peer.
 *
 * The NTCP transport sends individual I2NP messages AES/256/CBC encrypted with
 * a simple checksum.  The unencrypted message is encoded as follows:
 *<pre>
 *  +-------+-------+--//--+---//----+-------+-------+-------+-------+
 *  | sizeof(data)  | data | padding | adler checksum of sz+data+pad |
 *  +-------+-------+--//--+---//----+-------+-------+-------+-------+
 *</pre>
 * That message is then encrypted with the DH/2048 negotiated session key
 * (station to station authenticated per the EstablishState class) using the
 * last 16 bytes of the previous encrypted message as the IV.
 *
 * One special case is a metadata message where the sizeof(data) is 0.  In
 * that case, the unencrypted message is encoded as:
 *<pre>
 *  +-------+-------+-------+-------+-------+-------+-------+-------+
 *  |       0       |      timestamp in seconds     | uninterpreted             
 *  +-------+-------+-------+-------+-------+-------+-------+-------+
 *          uninterpreted           | adler checksum of sz+data+pad |
 *  +-------+-------+-------+-------+-------+-------+-------+-------+
 *</pre>
 *
 */
class NTCPConnection implements FIFOBandwidthLimiter.CompleteListener {
    private final RouterContext _context;
    private final Log _log;
    private SocketChannel _chan;
    private SelectionKey _conKey;
    /** list of ByteBuffer containing data we have read and are ready to process, oldest first */
    private final LinkedBlockingQueue<ByteBuffer> _readBufs;
    /**
     * list of ByteBuffers containing fully populated and encrypted data, ready to write,
     * and already cleared through the bandwidth limiter.
     */
    private final LinkedBlockingQueue<ByteBuffer> _writeBufs;
    /** Requests that were not granted immediately */
    private final Set<FIFOBandwidthLimiter.Request> _bwRequests;
    private boolean _established;
    private long _establishedOn;
    private EstablishState _establishState;
    private NTCPTransport _transport;
    private final boolean _isInbound;
    private boolean _closed;
    private NTCPAddress _remAddr;
    private RouterIdentity _remotePeer;
    private long _clockSkew; // in seconds
    /**
     * pending unprepared OutNetMessage instances
     */
    private final LinkedBlockingQueue<OutNetMessage> _outbound;
    /** current prepared OutNetMessage, or null - synchronize on _outbound to modify */
    private OutNetMessage _currentOutbound;
    private SessionKey _sessionKey;
    /** encrypted block of the current I2NP message being read */
    private byte _curReadBlock[];
    /** next byte to which data should be placed in the _curReadBlock */
    private int _curReadBlockIndex;
    private final byte _decryptBlockBuf[];
    /** last AES block of the encrypted I2NP message (to serve as the next block's IV) */
    private byte _prevReadBlock[];
    private byte _prevWriteEnd[];
    /** current partially read I2NP message */
    private final ReadState _curReadState;
    private long _messagesRead;
    private long _messagesWritten;
    private long _lastSendTime;
    private long _lastReceiveTime;
    private final long _created;
    private long _nextMetaTime;
    /** unencrypted outbound metadata buffer */
    private final byte _meta[] = new byte[16];
    private boolean _sendingMeta;
    /** how many consecutive sends were failed due to (estimated) send queue time */
    private int _consecutiveBacklog;
    private long _nextInfoTime;
    
    private static final int META_FREQUENCY = 10*60*1000;
    /** how often we send our routerinfo unsolicited */
    private static final int INFO_FREQUENCY = 90*60*1000;
    /**
     *  Why this is 16K, and where it is documented, good question?
     *  We claim we can do 32K datagrams so this is a problem.
     *  Needs to be fixed. But SSU can handle it?
     *  In the meantime, don't let the transport bid on big messages.
     */
    public static final int BUFFER_SIZE = 16*1024;
    /** 2 bytes for length and 4 for CRC */
    public static final int MAX_MSG_SIZE = BUFFER_SIZE - (2 + 4);
    
    /**
     * Create an inbound connected (though not established) NTCP connection
     *
     */
    public NTCPConnection(RouterContext ctx, NTCPTransport transport, SocketChannel chan, SelectionKey key) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _created = System.currentTimeMillis();
        _transport = transport;
        _chan = chan;
        _readBufs = new LinkedBlockingQueue();
        _writeBufs = new LinkedBlockingQueue();
        _bwRequests = new ConcurrentHashSet(2);
        _outbound = new LinkedBlockingQueue();
        _isInbound = true;
        _decryptBlockBuf = new byte[16];
        _curReadState = new ReadState();
        _establishState = new EstablishState(ctx, transport, this);
        _conKey = key;
        _conKey.attach(this);
        initialize();
    }

    /**
     * Create an outbound unconnected NTCP connection
     *
     */
    public NTCPConnection(RouterContext ctx, NTCPTransport transport, RouterIdentity remotePeer, NTCPAddress remAddr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _created = System.currentTimeMillis();
        _transport = transport;
        _remotePeer = remotePeer;
        _remAddr = remAddr;
        _readBufs = new LinkedBlockingQueue();
        _writeBufs = new LinkedBlockingQueue();
        _bwRequests = new ConcurrentHashSet(2);
        _outbound = new LinkedBlockingQueue();
        _isInbound = false;
        _decryptBlockBuf = new byte[16];
        _curReadState = new ReadState();
        initialize();
    }

    private void initialize() {
        _lastSendTime = _created;
        _lastReceiveTime = _created;
        _curReadBlock = new byte[16];
        _prevReadBlock = new byte[16];
        _transport.establishing(this);
    }
    
    public SocketChannel getChannel() { return _chan; }
    public SelectionKey getKey() { return _conKey; }
    public void setChannel(SocketChannel chan) { _chan = chan; }
    public void setKey(SelectionKey key) { _conKey = key; }
    public boolean isInbound() { return _isInbound; }
    public boolean isEstablished() { return _established; }
    public EstablishState getEstablishState() { return _establishState; }
    public NTCPAddress getRemoteAddress() { return _remAddr; }
    public RouterIdentity getRemotePeer() { return _remotePeer; }
    public void setRemotePeer(RouterIdentity ident) { _remotePeer = ident; }
    /** 
     * @param clockSkew alice's clock minus bob's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     */
    public void finishInboundEstablishment(SessionKey key, long clockSkew, byte prevWriteEnd[], byte prevReadEnd[]) {
        _sessionKey = key;
        _clockSkew = clockSkew;
        _prevWriteEnd = prevWriteEnd;
        System.arraycopy(prevReadEnd, prevReadEnd.length-16, _prevReadBlock, 0, _prevReadBlock.length);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Inbound established, prevWriteEnd: " + Base64.encode(prevWriteEnd) + " prevReadEnd: " + Base64.encode(prevReadEnd));
        _established = true;
        _establishedOn = System.currentTimeMillis();
        _transport.inboundEstablished(this);
        _establishState = null;
        _nextMetaTime = System.currentTimeMillis() + _context.random().nextInt(META_FREQUENCY);
        _nextInfoTime = System.currentTimeMillis() + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
    }

    /** @return seconds */
    public long getClockSkew() { return _clockSkew; }

    /** @return milliseconds */
    public long getUptime() { 
        if (!_established)
            return getTimeSinceCreated();
        else
            return System.currentTimeMillis()-_establishedOn; 
    }

    public long getMessagesSent() { return _messagesWritten; }
    public long getMessagesReceived() { return _messagesRead; }
    public long getOutboundQueueSize() { 
            int queued = _outbound.size();
            if (_currentOutbound != null)
                queued++;
            return queued;
    }

    /** @return milliseconds */
    public long getTimeSinceSend() { return System.currentTimeMillis()-_lastSendTime; }

    /** @return milliseconds */
    public long getTimeSinceReceive() { return System.currentTimeMillis()-_lastReceiveTime; }

    /** @return milliseconds */
    public long getTimeSinceCreated() { return System.currentTimeMillis()-_created; }

    public int getConsecutiveBacklog() { return _consecutiveBacklog; }
    
    public boolean isClosed() { return _closed; }
    public void close() { close(false); }
    public void close(boolean allowRequeue) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Closing connection " + toString(), new Exception("cause"));
        _closed = true;
        if (_chan != null) try { _chan.close(); } catch (IOException ioe) { }
        if (_conKey != null) _conKey.cancel();
        _establishState = null;
        _transport.removeCon(this);
        _transport.getReader().connectionClosed(this);
        _transport.getWriter().connectionClosed(this);

        for (Iterator<FIFOBandwidthLimiter.Request> iter = _bwRequests.iterator(); iter.hasNext(); ) {
            iter.next().abort();
        }
        _bwRequests.clear();

        OutNetMessage msg;
        while ((msg = _outbound.poll()) != null) {
            Object buf = msg.releasePreparationBuffer();
            if (buf != null)
                releaseBuf((PrepBuffer)buf);
            _transport.afterSend(msg, false, allowRequeue, msg.getLifetime());
        }

        msg = _currentOutbound;
        if (msg != null) {
            Object buf = msg.releasePreparationBuffer();
            if (buf != null)
                releaseBuf((PrepBuffer)buf);
            _transport.afterSend(msg, false, allowRequeue, msg.getLifetime());
        }
    }
    
    /**
     * toss the message onto the connection's send queue
     */
    public void send(OutNetMessage msg) {
        if (tooBacklogged()) {
            boolean allowRequeue = false; // if we are too backlogged in tcp, don't try ssu
            boolean successful = false;
            _consecutiveBacklog++;
            _transport.afterSend(msg, successful, allowRequeue, msg.getLifetime());
            if (_consecutiveBacklog > 10) { // waaay too backlogged
                boolean wantsWrite = false;
                try { wantsWrite = ( (_conKey.interestOps() & SelectionKey.OP_WRITE) != 0); } catch (Exception e) {}
                if (_log.shouldLog(Log.WARN)) {
		    int blocks = _writeBufs.size();
                    _log.warn("Too backlogged for too long (" + _consecutiveBacklog + " messages for " + DataHelper.formatDuration(queueTime()) + ", sched? " + wantsWrite + ", blocks: " + blocks + ") sending to " + _remotePeer.calculateHash().toBase64());
                }
                _context.statManager().addRateData("ntcp.closeOnBacklog", getUptime(), getUptime());
                close();
            }
            _context.statManager().addRateData("ntcp.dontSendOnBacklog", _consecutiveBacklog, msg.getLifetime());
            return;
        }
        _consecutiveBacklog = 0;
        int enqueued = 0;
        if (FAST_LARGE)
            bufferedPrepare(msg);
        boolean noOutbound = false;
        _outbound.offer(msg);
        enqueued = _outbound.size();
        msg.setQueueSize(enqueued);
        noOutbound = (_currentOutbound == null);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("messages enqueued on " + toString() + ": " + enqueued + " new one: " + msg.getMessageId() + " of " + msg.getMessageType());
        if (_established && noOutbound)
            _transport.getWriter().wantsWrite(this, "enqueued");
    }

    private long queueTime() {    
        OutNetMessage msg = _currentOutbound;
        if (msg == null) {
            msg = _outbound.peek();
            if (msg == null)
                return 0;
        }
        return msg.getSendTime(); // does not include any of the pre-send(...) preparation
    }

    public boolean tooBacklogged() {
        long queueTime = queueTime();
        if (queueTime <= 0) return false;
        boolean currentOutboundSet = _currentOutbound != null;
        
        // perhaps we could take into account the size of the queued messages too, our
        // current transmission rate, and how much time is left before the new message's expiration?
        // ok, maybe later...
        if (getUptime() < 10*1000) // allow some slack just after establishment
            return false;
        if (queueTime > 5*1000) { // bloody arbitrary.  well, its half the average message lifetime...
            int size = _outbound.size();
            if (_log.shouldLog(Log.WARN)) {
	        int writeBufs = _writeBufs.size();
                try {
                    _log.warn("Too backlogged: queue time " + queueTime + " and the size is " + size 
                          + ", wantsWrite? " + (0 != (_conKey.interestOps()&SelectionKey.OP_WRITE))
                          + ", currentOut set? " + currentOutboundSet
			  + ", writeBufs: " + writeBufs + " on " + toString());
                } catch (Exception e) {}  // java.nio.channels.CancelledKeyException
            }
            _context.statManager().addRateData("ntcp.sendBacklogTime", queueTime, size);
            return true;
        //} else if (size > 32) { // another arbitrary limit.
        //    if (_log.shouldLog(Log.ERROR))
        //        _log.error("Too backlogged: queue size is " + size + " and the lifetime of the head is " + queueTime);
        //    return true;
        } else {
            return false;
        }
    }
    
    public void enqueueInfoMessage() {
        OutNetMessage infoMsg = new OutNetMessage(_context);
        infoMsg.setExpiration(_context.clock().now()+10*1000);
        DatabaseStoreMessage dsm = new DatabaseStoreMessage(_context);
        dsm.setEntry(_context.router().getRouterInfo());
        infoMsg.setMessage(dsm);
        infoMsg.setPriority(100);
        RouterInfo target = _context.netDb().lookupRouterInfoLocally(_remotePeer.calculateHash());
        if (target != null) {
            infoMsg.setTarget(target);
            infoMsg.beginSend();
            _context.statManager().addRateData("ntcp.infoMessageEnqueued", 1, 0);
            send(infoMsg);
            
            // See comment below
            //enqueueFloodfillMessage(target);
        } else {
            if (_isInbound) {
                // ok, we shouldn't have enqueued it yet, as we havent received their info
            } else {
                // how did we make an outbound connection to someone we don't know about?
            }
        }
    }

    //private static final int PEERS_TO_FLOOD = 3;

    /**
     * to prevent people from losing track of the floodfill peers completely, lets periodically
     * send those we are connected to references to the floodfill peers that we know
     *
     * Do we really need this anymore??? Peers shouldn't lose track anymore, and if they do,
     * FloodOnlyLookupJob should recover.
     * The bandwidth isn't so much, but it is a lot of extra data at connection startup, which
     * hurts latency of new connections.
     */
/**********
    private void enqueueFloodfillMessage(RouterInfo target) {
        FloodfillNetworkDatabaseFacade fac = (FloodfillNetworkDatabaseFacade)_context.netDb();
        List peers = fac.getFloodfillPeers();
        Collections.shuffle(peers);
        for (int i = 0; i < peers.size() && i < PEERS_TO_FLOOD; i++) {
            Hash peer = (Hash)peers.get(i);
            
            // we already sent our own info, and no need to tell them about themselves
            if (peer.equals(_context.routerHash()) || peer.equals(target.calculateHash()))
                continue;
            
            RouterInfo info = fac.lookupRouterInfoLocally(peer);
            if (info == null)
                continue;

            OutNetMessage infoMsg = new OutNetMessage(_context);
            infoMsg.setExpiration(_context.clock().now()+10*1000);
            DatabaseStoreMessage dsm = new DatabaseStoreMessage(_context);
            dsm.setKey(peer);
            dsm.setRouterInfo(info);
            infoMsg.setMessage(dsm);
            infoMsg.setPriority(100);
            infoMsg.setTarget(target);
            infoMsg.beginSend();
            _context.statManager().addRateData("ntcp.floodInfoMessageEnqueued", 1, 0);
            send(infoMsg);
        }
    }
***********/
    
    /** 
     * @param clockSkew alice's clock minus bob's clock in seconds (may be negative, obviously, but |val| should
     *                  be under 1 minute)
     */
    public void finishOutboundEstablishment(SessionKey key, long clockSkew, byte prevWriteEnd[], byte prevReadEnd[]) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("outbound established (key=" + key + " skew=" + clockSkew + " prevWriteEnd=" + Base64.encode(prevWriteEnd) + ")");
        _sessionKey = key;
        _clockSkew = clockSkew;
        _prevWriteEnd = prevWriteEnd;
        System.arraycopy(prevReadEnd, prevReadEnd.length-16, _prevReadBlock, 0, _prevReadBlock.length);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Outbound established, prevWriteEnd: " + Base64.encode(prevWriteEnd) + " prevReadEnd: " + Base64.encode(prevReadEnd));

        _established = true;
        _establishedOn = System.currentTimeMillis();
        _establishState = null;
        _transport.markReachable(getRemotePeer().calculateHash(), false);
        //_context.shitlist().unshitlistRouter(getRemotePeer().calculateHash(), NTCPTransport.STYLE);
        boolean msgs = !_outbound.isEmpty();
        _nextMetaTime = System.currentTimeMillis() + _context.random().nextInt(META_FREQUENCY);
        _nextInfoTime = System.currentTimeMillis() + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        if (msgs)
            _transport.getWriter().wantsWrite(this, "outbound established");
    }
    
    // Time vs space tradeoff:
    // on slow GCing jvms, the mallocs in the following preparation can cause the 
    // write to get congested, taking up a substantial portion of the Writer's
    // time (and hence, slowing down the transmission to the peer).  we could 
    // however do the preparation (up to but not including the aes.encrypt)
    // as part of the .send(OutNetMessage) above, which runs on less congested
    // threads (whatever calls OutNetMessagePool.add, which can be the jobqueue,
    // tunnel builders, simpletimers, etc).  that would increase the Writer's
    // efficiency (speeding up the transmission to the peer) but would require
    // more memory to hold the serialized preparations of all queued messages, not
    // just the currently transmitting one.
    //
    // hmm.
    private static final boolean FAST_LARGE = true; // otherwise, SLOW_SMALL
    
    /**
     * prepare the next i2np message for transmission.  this should be run from
     * the Writer thread pool.
     *
     * Todo: remove synchronization?
     *
     */
    synchronized void prepareNextWrite() {
        //if (FAST_LARGE)
            prepareNextWriteFast();
        //else
        //    prepareNextWriteSmall();
    }
/**********  nobody's tried this one in years
    private void prepareNextWriteSmall() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("prepare next write w/ isInbound? " + _isInbound + " established? " + _established);
        if (!_isInbound && !_established) {
            if (_establishState == null) {
                _establishState = new EstablishState(_context, _transport, this);
                _establishState.prepareOutbound();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("prepare next write, but we have already prepared the first outbound and we are not yet established..." + toString());
            }
            return;
        }
        
        if (_nextMetaTime <= System.currentTimeMillis()) {
            sendMeta();
            _nextMetaTime = System.currentTimeMillis() + _context.random().nextInt(META_FREQUENCY);
        }
      
        OutNetMessage msg = null;
        synchronized (_outbound) {
            if (_currentOutbound != null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("attempt for multiple outbound messages with " + System.identityHashCode(_currentOutbound) + " already waiting and " + _outbound.size() + " queued");
                return;
            }
                //throw new RuntimeException("We should not be preparing a write while we still have one pending");
            if (!_outbound.isEmpty()) {
                msg = (OutNetMessage)_outbound.remove(0);
                _currentOutbound = msg;
            } else {
                return;
            }
        }
        
        msg.beginTransmission();
        msg.beginPrepare();
        long begin = System.currentTimeMillis();
        // prepare the message as a binary array, then encrypt it w/ a checksum
        // and add it to the _writeBufs
        // E(sizeof(data)+data+pad+crc, sessionKey, prevEncrypted)
        I2NPMessage m = msg.getMessage();
        int sz = m.getMessageSize();
        int min = 2 + sz + 4;
        int rem = min % 16;
        int padding = 0;
        if (rem > 0)
            padding = 16 - rem;
        
        byte unencrypted[] = new byte[min+padding];
        byte base[] = m.toByteArray();
        DataHelper.toLong(unencrypted, 0, 2, sz);
        System.arraycopy(base, 0, unencrypted, 2, base.length);
        if (padding > 0) {
            byte pad[] = new byte[padding];
            _context.random().nextBytes(pad);
            System.arraycopy(pad, 0, unencrypted, 2+sz, padding);
        }

        long serialized = System.currentTimeMillis();
        Adler32 crc = new Adler32();
        crc.reset();
        crc.update(unencrypted, 0, unencrypted.length-4);
        long val = crc.getValue();
        DataHelper.toLong(unencrypted, unencrypted.length-4, 4, val);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Outbound message " + _messagesWritten + " has crc " + val);
        
        long crced = System.currentTimeMillis();
        byte encrypted[] = new byte[unencrypted.length];
        _context.aes().encrypt(unencrypted, 0, encrypted, 0, _sessionKey, _prevWriteEnd, 0, unencrypted.length);
        System.arraycopy(encrypted, encrypted.length-16, _prevWriteEnd, 0, _prevWriteEnd.length);
        long encryptedTime = System.currentTimeMillis();
        msg.prepared();
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("prepared outbound " + System.identityHashCode(msg) 
                       + " serialize=" + (serialized-begin)
                       + " crc=" + (crced-serialized)
                       + " encrypted=" + (encryptedTime-crced)
                       + " prepared=" + (encryptedTime-begin));
        }
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Encrypting " + msg + " [" + System.identityHashCode(msg) + "] crc=" + crc.getValue() + "\nas: " 
        //               + Base64.encode(encrypted, 0, 16) + "...\ndecrypted: " 
        //               + Base64.encode(unencrypted, 0, 16) + "..." + "\nIV=" + Base64.encode(_prevWriteEnd, 0, 16));
        _transport.getPumper().wantsWrite(this, encrypted);

        // for every 6-12 hours that we are connected to a peer, send them
	// our updated netDb info (they may not accept it and instead query
	// the floodfill netDb servers, but they may...)
        if (_nextInfoTime <= System.currentTimeMillis()) {
            enqueueInfoMessage();
            _nextInfoTime = System.currentTimeMillis() + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        }
    }
**********/
    
    /**
     * prepare the next i2np message for transmission.  this should be run from
     * the Writer thread pool.
     *
     * Todo: remove synchronization?
     *
     */
    synchronized void prepareNextWriteFast() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("prepare next write w/ isInbound? " + _isInbound + " established? " + _established);
        if (!_isInbound && !_established) {
            if (_establishState == null) {
                _establishState = new EstablishState(_context, _transport, this);
                _establishState.prepareOutbound();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("prepare next write, but we have already prepared the first outbound and we are not yet established..." + toString());
            }
            return;
        }
        
        if (_nextMetaTime <= System.currentTimeMillis()) {
            sendMeta();
            _nextMetaTime = System.currentTimeMillis() + META_FREQUENCY + _context.random().nextInt(META_FREQUENCY);
        }
      
        OutNetMessage msg = null;
        // this is synchronized only for _currentOutbound
        // Todo: figure out how to remove the synchronization
        synchronized (_outbound) {
            if (_currentOutbound != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("attempt for multiple outbound messages with " + System.identityHashCode(_currentOutbound) + " already waiting and " + _outbound.size() + " queued");
                return;
            }
                //throw new RuntimeException("We should not be preparing a write while we still have one pending");
            if (queueTime() > 3*1000) {  // don't stall low-priority messages
                msg = _outbound.poll();
                if (msg == null)
                    return;
            } else {
                int slot = 0;  // only for logging
                Iterator<OutNetMessage> it = _outbound.iterator();
                for (int i = 0; it.hasNext() && i < 75; i++) {  //arbitrary bound
                    OutNetMessage mmsg = it.next();
                    if (msg == null || mmsg.getPriority() > msg.getPriority()) {
                        msg = mmsg;
                        slot = i;
                    }
                }
                if (msg == null)
                    return;
                // if (_outbound.indexOf(msg) > 0)
                //     _log.debug("Priority message sent, pri = " + msg.getPriority() + " pos = " + _outbound.indexOf(msg) + "/" +_outbound.size());
                if (_log.shouldLog(Log.INFO))
                    _log.info("Type " + msg.getMessage().getType() + " pri " + msg.getPriority() + " slot " + slot);
                boolean removed = _outbound.remove(msg);
                if ((!removed) && _log.shouldLog(Log.WARN))
                    _log.warn("Already removed??? " + msg.getMessage().getType());
            }
            _currentOutbound = msg;
        }
        
        msg.beginTransmission();
        //long begin = System.currentTimeMillis();
        PrepBuffer buf = (PrepBuffer)msg.releasePreparationBuffer();
        if (buf == null)
            throw new RuntimeException("buf is null for " + msg);
        _context.aes().encrypt(buf.unencrypted, 0, buf.encrypted, 0, _sessionKey, _prevWriteEnd, 0, buf.unencryptedLength);
        System.arraycopy(buf.encrypted, buf.encrypted.length-16, _prevWriteEnd, 0, _prevWriteEnd.length);
        //long encryptedTime = System.currentTimeMillis();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Encrypting " + msg + " [" + System.identityHashCode(msg) + "] crc=" + crc.getValue() + "\nas: " 
        //               + Base64.encode(encrypted, 0, 16) + "...\ndecrypted: " 
        //               + Base64.encode(unencrypted, 0, 16) + "..." + "\nIV=" + Base64.encode(_prevWriteEnd, 0, 16));
        _transport.getPumper().wantsWrite(this, buf.encrypted);
        //long wantsTime = System.currentTimeMillis();
        releaseBuf(buf);
        //long releaseTime = System.currentTimeMillis();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("prepared outbound " + System.identityHashCode(msg) 
        //               + " encrypted=" + (encryptedTime-begin)
        //               + " wantsWrite=" + (wantsTime-encryptedTime)
        //               + " releaseBuf=" + (releaseTime-wantsTime));

        // for every 6-12 hours that we are connected to a peer, send them
	// our updated netDb info (they may not accept it and instead query
	// the floodfill netDb servers, but they may...)
        if (_nextInfoTime <= System.currentTimeMillis()) {
            // perhaps this should check to see if we are bw throttled, etc?
            enqueueInfoMessage();
            _nextInfoTime = System.currentTimeMillis() + (INFO_FREQUENCY / 2) + _context.random().nextInt(INFO_FREQUENCY);
        }
    }
    
    /**
     * Serialize the message/checksum/padding/etc for transmission, but leave off
     * the encryption for the actual write process (when we will always have the
     * end of the previous encrypted transmission to serve as our IV).  with care,
     * the encryption could be handled here too, as long as messages aren't expired
     * in the queue and the establishment process takes that into account.
     */
    private void bufferedPrepare(OutNetMessage msg) {
        //if (!_isInbound && !_established)
        //    return;
        msg.beginPrepare();
        //long begin = System.currentTimeMillis();
        PrepBuffer buf = acquireBuf();
        //long alloc = System.currentTimeMillis();
        
        I2NPMessage m = msg.getMessage();
        buf.baseLength = m.toByteArray(buf.base);
        int sz = buf.baseLength;
        //int sz = m.getMessageSize();
        int min = 2 + sz + 4;
        int rem = min % 16;
        int padding = 0;
        if (rem > 0)
            padding = 16 - rem;
        
        buf.padLength = padding;
        buf.unencryptedLength = min+padding;
        DataHelper.toLong(buf.unencrypted, 0, 2, sz);
        System.arraycopy(buf.base, 0, buf.unencrypted, 2, buf.baseLength);
        if (padding > 0)
            _context.random().nextBytes(buf.pad); // maybe more than necessary, but its only the prng
        System.arraycopy(buf.pad, 0, buf.unencrypted, 2+sz, buf.padLength);
        
        //long serialized = System.currentTimeMillis();
        buf.crc.update(buf.unencrypted, 0, buf.unencryptedLength-4);
        
        long val = buf.crc.getValue();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Outbound message " + _messagesWritten + " has crc " + val
                       + " sz=" +sz + " rem=" + rem + " padding=" + padding);
        
        DataHelper.toLong(buf.unencrypted, buf.unencryptedLength-4, 4, val);
        buf.encrypted = new byte[buf.unencryptedLength];
        
        //long crced = System.currentTimeMillis();
        msg.prepared(buf);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Buffered prepare took " + (crced-begin) + ", alloc=" + (alloc-begin)
        //               + " serialize=" + (serialized-alloc) + " crc=" + (crced-serialized));
    }
    
    private static final int MIN_PREP_BUFS = 5;
    private static int NUM_PREP_BUFS = 5;
    private static int __liveBufs = 0;
    private static int __consecutiveExtra;
    private final static List _bufs = new ArrayList(NUM_PREP_BUFS);
    private PrepBuffer acquireBuf() {
        synchronized (_bufs) {
            if (!_bufs.isEmpty()) {
                PrepBuffer b = (PrepBuffer)_bufs.remove(0);
                b.acquired();
                return b;
            }
        }
        PrepBuffer b = new PrepBuffer();
        b.init();
        NUM_PREP_BUFS = ++__liveBufs;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("creating a new prep buffer with " + __liveBufs + " live");
        _context.statManager().addRateData("ntcp.prepBufCache", NUM_PREP_BUFS, 0);
        b.acquired();
        return b;
    }
    private void releaseBuf(PrepBuffer buf) {
        buf.init();
        long lifetime = buf.lifetime();
        int extra = 0;
        boolean cached = false;
        synchronized (_bufs) {
            if (_bufs.size() < NUM_PREP_BUFS) {
                extra = _bufs.size();
                _bufs.add(buf);
                cached = true;
                if (extra > 5) {
                    __consecutiveExtra++;
                    if (__consecutiveExtra >= 20) {
                        NUM_PREP_BUFS = Math.max(NUM_PREP_BUFS - 1, MIN_PREP_BUFS);
                        __consecutiveExtra = 0;
                    }
                }
            } else {
                buf.unencrypted = null;
                buf.base = null;
                buf.pad = null;
                buf.crc = null;
                --__liveBufs;
            }
        }
        if (cached && _log.shouldLog(Log.DEBUG))
            _log.debug("releasing cached buffer with " + __liveBufs + " live after " + lifetime);
    }
    private static class PrepBuffer {
        byte unencrypted[];
        int unencryptedLength;
        byte base[];
        int baseLength;
        byte pad[];
        int padLength;
        Adler32 crc;
        byte encrypted[];
        private long acquiredOn;
        
        PrepBuffer() {
            unencrypted = new byte[BUFFER_SIZE];
            base = new byte[BUFFER_SIZE];
            pad = new byte[16];
            crc = new Adler32();
        }
        private void init() {
            unencryptedLength = 0;
            baseLength = 0;
            padLength = 0;
            encrypted = null;
            crc.reset();
        }
        public void acquired() { acquiredOn = System.currentTimeMillis(); }
        public long lifetime() { return System.currentTimeMillis()-acquiredOn; }
    }
    
    /** 
     * async callback after the outbound connection was completed (this should NOT block, 
     * as it occurs in the selector thread)
     */
    public void outboundConnected() {
        _conKey.interestOps(SelectionKey.OP_READ);
        // schedule up the beginning of our handshaking by calling prepareNextWrite on the
        // writer thread pool
        _transport.getWriter().wantsWrite(this, "outbound connected");
    }

    public void complete(FIFOBandwidthLimiter.Request req) {
        removeRequest(req);
        ByteBuffer buf = (ByteBuffer)req.attachment();
        if (req.getTotalInboundRequested() > 0) {
            _context.statManager().addRateData("ntcp.throttledReadComplete", (System.currentTimeMillis()-req.getRequestTime()), 0);
            recv(buf);
            // our reads used to be bw throttled (during which time we were no
            // longer interested in reading from the network), but we aren't
            // throttled anymore, so we should resume being interested in reading
            _transport.getPumper().wantsRead(this);
            //_transport.getReader().wantsRead(this);
        } else if (req.getTotalOutboundRequested() > 0) {
            _context.statManager().addRateData("ntcp.throttledWriteComplete", (System.currentTimeMillis()-req.getRequestTime()), 0);
            write(buf);
        }
    }

    private void removeRequest(FIFOBandwidthLimiter.Request req) {
        _bwRequests.remove(req);
    }

    private void addRequest(FIFOBandwidthLimiter.Request req) {
        _bwRequests.add(req);
    }
    
    /**
     * We have read the data in the buffer, but we can't process it locally yet,
     * because we're choked by the bandwidth limiter.  Cache the contents of
     * the buffer (not copy) and register ourselves to be notified when the 
     * contents have been fully allocated
     */
    public void queuedRecv(ByteBuffer buf, FIFOBandwidthLimiter.Request req) {
        req.attach(buf);
        req.setCompleteListener(this);
        addRequest(req);
    }

    /** ditto for writes */
    public void queuedWrite(ByteBuffer buf, FIFOBandwidthLimiter.Request req) {
        req.attach(buf);
        req.setCompleteListener(this);
        addRequest(req);
    }
    
    /**
     * The contents of the buffer have been read and can be processed asap.
     * This should not block, and the NTCP connection now owns the buffer
     * to do with as it pleases.
     */
    public void recv(ByteBuffer buf) {
        _bytesReceived += buf.remaining();
            //buf.flip();
        _readBufs.offer(buf);
        _transport.getReader().wantsRead(this);
        updateStats();
    }
    /**
     * The contents of the buffer have been encrypted / padded / etc and have
     * been fully allocated for the bandwidth limiter.
     */
    public void write(ByteBuffer buf) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Before write(buf)");
        _writeBufs.offer(buf);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("After write(buf)");
        _transport.getPumper().wantsWrite(this);
    }
    
    /** @return null if none available */
    public ByteBuffer getNextReadBuf() {
        return _readBufs.poll();
    }

    /** since getNextReadBuf() removes, this should not be necessary */
    public void removeReadBuf(ByteBuffer buf) {
        _readBufs.remove(buf); 
        //_transport.getPumper().releaseBuf(buf);
    }
    
    public int getWriteBufCount() { return _writeBufs.size(); }

    /** @return null if none available */
    public ByteBuffer getNextWriteBuf() {
        return _writeBufs.peek(); // not remove!  we removeWriteBuf afterwards
    }
    
    public void removeWriteBuf(ByteBuffer buf) {
        _bytesSent += buf.capacity();
        OutNetMessage msg = null;
        boolean bufsRemain = false;
        boolean clearMessage = false;
        if (_sendingMeta && (buf.capacity() == _meta.length)) {
            _sendingMeta = false;
        } else {
            clearMessage = true;
        }
        _writeBufs.remove(buf);
        bufsRemain = !_writeBufs.isEmpty();
        if (clearMessage) {
            // see synchronization comments in prepareNextWriteFast()
            synchronized (_outbound) {
                if (_currentOutbound != null)
                    msg = _currentOutbound;
                _currentOutbound = null;
            }
            if (msg != null) {
                _lastSendTime = System.currentTimeMillis();
                _context.statManager().addRateData("ntcp.sendTime", msg.getSendTime(), msg.getSendTime());
                _context.statManager().addRateData("ntcp.transmitTime", msg.getTransmissionTime(), msg.getTransmissionTime());
                _context.statManager().addRateData("ntcp.sendQueueSize", msg.getQueueSize(), msg.getLifetime());
                if (_log.shouldLog(Log.INFO)) {
                    _log.info("I2NP message " + _messagesWritten + "/" + msg.getMessageId() + " sent after " 
                              + msg.getSendTime() + "/" + msg.getTransmissionTime() + "/" 
                              + msg.getPreparationTime() + "/" + msg.getLifetime()
                              + " queued after " + msg.getQueueSize()
                              + " with " + buf.capacity() + " bytes (uid=" + System.identityHashCode(msg)+" on " + toString() + ")");
                }
                _messagesWritten++;
                _transport.sendComplete(msg);
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("I2NP meta message sent completely");
        }
        
        boolean msgs = ((!_outbound.isEmpty()) || (_currentOutbound != null));
        if (msgs) // push through the bw limiter to reach _writeBufs
            _transport.getWriter().wantsWrite(this, "write completed");
        if (bufsRemain) // send asap
            _transport.getPumper().wantsWrite(this);
        updateStats();
    }
        
    private long _bytesReceived;
    private long _bytesSent;
    /** _bytesReceived when we last updated the rate */
    private long _lastBytesReceived;
    /** _bytesSent when we last updated the rate */
    private long _lastBytesSent;
    private long _lastRateUpdated;
    private float _sendBps;
    private float _recvBps;
    private float _sendBps15s;
    private float _recvBps15s;
    
    public float getSendRate() { return _sendBps15s; }
    public float getRecvRate() { return _recvBps15s; }
    
    private void updateStats() {
        long now = System.currentTimeMillis();
        long time = now - _lastRateUpdated;
        // If at least one second has passed
        if (time >= 1000) {
            long totS = _bytesSent;
            long totR = _bytesReceived;
            long sent = totS - _lastBytesSent; // How much we sent meanwhile
            long recv = totR - _lastBytesReceived; // How much we received meanwhile
            _lastBytesSent = totS;
            _lastBytesReceived = totR;
            _lastRateUpdated = now;

            _sendBps = (0.9f)*_sendBps + (0.1f)*((float)sent*1000f)/(float)time;
            _recvBps = (0.9f)*_recvBps + (0.1f)*((float)recv*1000)/(float)time;

            // Maintain an approximate average with a 15-second halflife
            // Weights (0.955 and 0.045) are tuned so that transition between two values (e.g. 0..10)
            // would reach their midpoint (e.g. 5) in 15s
            _sendBps15s = (0.955f)*_sendBps15s + (0.045f)*((float)sent*1000f)/(float)time;
            _recvBps15s = (0.955f)*_recvBps15s + (0.045f)*((float)recv*1000)/(float)time;

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Rates updated to "
                           + _sendBps + "/" + _recvBps + "Bps in/out (" 
                           + _sendBps15s + "/" + _recvBps15s + "Bps in/out 15s) after "
                           + sent + "/" + recv + " in " + time);
        }
    }
        
    /**
     * the contents of the buffer include some fraction of one or more
     * encrypted and encoded I2NP messages.  individual i2np messages are
     * encoded as "sizeof(data)+data+pad+crc", and those are encrypted
     * with the session key and the last 16 bytes of the previous encrypted
     * i2np message.  the contents of the buffer is owned by the EventPumper,
     * so data should be copied out
     */
    synchronized void recvEncryptedI2NP(ByteBuffer buf) {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("receive encrypted i2np: " + buf.remaining());
        while (buf.hasRemaining() && !_closed) {
            int want = Math.min(buf.remaining(), _curReadBlock.length-_curReadBlockIndex);
            if (want > 0) {
                buf.get(_curReadBlock, _curReadBlockIndex, want);
                _curReadBlockIndex += want;
            }
            //_curReadBlock[_curReadBlockIndex++] = buf.get();
            if (_curReadBlockIndex >= _curReadBlock.length) {
                // cbc
                _context.aes().decryptBlock(_curReadBlock, 0, _sessionKey, _decryptBlockBuf, 0);
                DataHelper.xor(_decryptBlockBuf, 0, _prevReadBlock, 0, _decryptBlockBuf, 0, _decryptBlockBuf.length);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("parse decrypted i2np block (remaining: " + buf.remaining() + ")");
                boolean ok = recvUnencryptedI2NP();
                if (!ok) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Read buffer " + System.identityHashCode(buf) + " contained corrupt data");
                    _context.statManager().addRateData("ntcp.corruptDecryptedI2NP", 1, getUptime());
                    return;
                }
                byte swap[] = _prevReadBlock;
                _prevReadBlock = _curReadBlock;
                _curReadBlock = swap;
                _curReadBlockIndex = 0;
            }
        }
    }
    
    /** _decryptBlockBuf contains another cleartext block of I2NP to parse */
    private boolean recvUnencryptedI2NP() {
        _curReadState.receiveBlock(_decryptBlockBuf);
        if (_curReadState.getSize() > BUFFER_SIZE) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("I2NP message too big - size: " + _curReadState.getSize() + " Dropping " + toString());
            _context.statManager().addRateData("ntcp.corruptTooLargeI2NP", _curReadState.getSize(), getUptime());
            close();
            return false;
        } else {
            return true;
        }
    }
    
   /* 
    * One special case is a metadata message where the sizeof(data) is 0.  In
    * that case, the unencrypted message is encoded as:
    *  +-------+-------+-------+-------+-------+-------+-------+-------+
    *  |       0       |      timestamp in seconds     | uninterpreted             
    *  +-------+-------+-------+-------+-------+-------+-------+-------+
    *          uninterpreted           | adler checksum of sz+data+pad |
    *  +-------+-------+-------+-------+-------+-------+-------+-------+
    * 
    */
    private void readMeta(byte unencrypted[]) {
        long ourTs = (_context.clock().now() + 500) / 1000;
        long ts = DataHelper.fromLong(unencrypted, 2, 4);
        Adler32 crc = new Adler32();
        crc.update(unencrypted, 0, unencrypted.length-4);
        long expected = crc.getValue();
        long read = DataHelper.fromLong(unencrypted, unencrypted.length-4, 4);
        if (read != expected) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("I2NP metadata message had a bad CRC value");
            _context.statManager().addRateData("ntcp.corruptMetaCRC", 1, getUptime());
            close();
            return;
        } else {
            long newSkew = (ourTs - ts);
            if (Math.abs(newSkew*1000) > Router.CLOCK_FUDGE_FACTOR) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer's skew jumped too far (from " + _clockSkew + " s to " + newSkew + " s): " + toString());
                _context.statManager().addRateData("ntcp.corruptSkew", newSkew, getUptime());
                close();
                return;
            }
            _context.statManager().addRateData("ntcp.receiveMeta", newSkew, getUptime());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received NTCP metadata, old skew of " + _clockSkew + " s, new skew of " + newSkew + "s.");
            _clockSkew = newSkew;
        }
    }

    private void sendMeta() {
        byte encrypted[] = new byte[_meta.length];
        synchronized (_meta) {
            _context.random().nextBytes(_meta); // randomize the uninterpreted, then overwrite w/ data
            DataHelper.toLong(_meta, 0, 2, 0);
            DataHelper.toLong(_meta, 2, 4, (_context.clock().now() + 500) / 1000);
            Adler32 crc = new Adler32();
            crc.update(_meta, 0, _meta.length-4);
            DataHelper.toLong(_meta, _meta.length-4, 4, crc.getValue());
            _context.aes().encrypt(_meta, 0, encrypted, 0, _sessionKey, _prevWriteEnd, 0, _meta.length);
        }
        System.arraycopy(encrypted, encrypted.length-16, _prevWriteEnd, 0, _prevWriteEnd.length);
        // perhaps this should skip the bw limiter to reduce clock skew issues?
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending NTCP metadata");
        _sendingMeta = true;
        _transport.getPumper().wantsWrite(this, encrypted);
        // enqueueInfoMessage(); // this often?
    }
    
    private static final int MAX_HANDLERS = 4;
    private final static LinkedBlockingQueue<I2NPMessageHandler> _i2npHandlers = new LinkedBlockingQueue(MAX_HANDLERS);

    private final static I2NPMessageHandler acquireHandler(RouterContext ctx) {
        I2NPMessageHandler rv = _i2npHandlers.poll();
        if (rv == null)
            rv = new I2NPMessageHandler(ctx);
        return rv;
    }

    private static void releaseHandler(I2NPMessageHandler handler) {
        _i2npHandlers.offer(handler);
    }
    
    
    public long getReadTime() { return _curReadState.getReadTime(); }
    
    private static class DataBuf {
        byte data[];
        ByteArrayInputStream bais;
        public DataBuf() {
            data = new byte[BUFFER_SIZE];
            bais = new ByteArrayInputStream(data);
        }
    }
    
    private static final int MAX_DATA_READ_BUFS = 16;
    private final static LinkedBlockingQueue<DataBuf> _dataReadBufs = new LinkedBlockingQueue(MAX_DATA_READ_BUFS);

    private static DataBuf acquireReadBuf() {
        DataBuf rv = _dataReadBufs.poll();
        if (rv != null)
            return rv;
        return new DataBuf();
    }

    private static void releaseReadBuf(DataBuf buf) {
        buf.bais.reset();
        _dataReadBufs.offer(buf);
    }

    /**
     * sizeof(data)+data+pad+crc.
     *
     * perhaps to reduce the per-con memory footprint, we can acquire/release
     * the ReadState._data and ._bais when _size is > 0, so there are only
     * J 16KB buffers for the cons actually transmitting, instead of one per
     * con (including idle ones)
     */
    private class ReadState {
        private int _size;
        private DataBuf _dataBuf;
        private int _nextWrite;
        private long _expectedCrc;
        private Adler32 _crc;
        private long _stateBegin;
        private int _blocks;
        public ReadState() {
            _crc = new Adler32();
            init();
        }
        private void init() {
            _size = -1;
            _nextWrite = 0;
            _expectedCrc = -1;
            _stateBegin = -1;
            _blocks = -1;
            _crc.reset();
            if (_dataBuf != null)
                releaseReadBuf(_dataBuf);
            _dataBuf = null;
        }
        public int getSize() { return _size; }
        public void receiveBlock(byte buf[]) {
            if (_size == -1) {
                receiveInitial(buf);
            } else {
                receiveSubsequent(buf);
            }
        }
        public long getReadTime() {
            long now = System.currentTimeMillis();
            long readTime = now - _stateBegin;
            if (readTime >= now)
                return -1;
            else
                return readTime;
        }
        private void receiveInitial(byte buf[]) {
            _stateBegin = System.currentTimeMillis();
            _size = (int)DataHelper.fromLong(buf, 0, 2);
            if (_size == 0) {
                readMeta(buf);
                init();
                return;
            } else {
                _dataBuf = acquireReadBuf();
                System.arraycopy(buf, 2, _dataBuf.data, 0, buf.length-2);
                _nextWrite += buf.length-2;
                _crc.update(buf);
                _blocks++;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("new I2NP message with size: " + _size + " for message " + _messagesRead);
            }
        }
        private void receiveSubsequent(byte buf[]) {
            _blocks++;
            int remaining = _size - _nextWrite;
            int blockUsed = Math.min(buf.length, remaining);
            if (remaining > 0) {
                System.arraycopy(buf, 0, _dataBuf.data, _nextWrite, blockUsed);
                _nextWrite += blockUsed;
                remaining -= blockUsed;
            }
            if ( (remaining <= 0) && (buf.length-blockUsed < 4) ) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("crc wraparound required on block " + _blocks + " in message " + _messagesRead);
                _crc.update(buf);
                return;
            } else if (remaining <= 0) {
                receiveLastBlock(buf);
            } else {
                _crc.update(buf);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("update read state with another block (remaining: " + remaining + ")");
            }
        }
        private void receiveLastBlock(byte buf[]) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("block remaining in the last block: " + (buf.length-blockUsed));

            // on the last block
            _expectedCrc = DataHelper.fromLong(buf, buf.length-4, 4);
            _crc.update(buf, 0, buf.length-4);
            long val = _crc.getValue();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("CRC value computed: " + val + " expected: " + _expectedCrc + " size: " + _size);
            if (val == _expectedCrc) {
                try {
                    I2NPMessageHandler h = acquireHandler(_context);
                    //I2NPMessage read = h.readMessage(new ByteArrayInputStream(_data, 0, _size));
                    // the _bais is mark()ed at 0 on construction, and on init() we
                    // reset() it back to that position, so this read always starts
                    // at the beginning of the _data buffer.  the I2NPMessageHandler
                    // also only reads the first I2NP message found, and does not
                    // depend upon EOF to stop reading, so its ok that the _bais could
                    // in theory return more data than _size bytes, since h.readMessage
                    // stops when it should.
                    I2NPMessage read = h.readMessage(_dataBuf.bais);
                    long timeToRecv = System.currentTimeMillis() - _stateBegin;
                    releaseHandler(h);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("I2NP message " + _messagesRead + "/" + (read != null ? read.getUniqueId() : 0) 
                                   + " received after " + timeToRecv + " with " + _size +"/"+ (_blocks*16) + " bytes on " + NTCPConnection.this.toString());
                    _context.statManager().addRateData("ntcp.receiveTime", timeToRecv, timeToRecv);
                    _context.statManager().addRateData("ntcp.receiveSize", _size, timeToRecv);

                    // FIXME move end of try block here.
                    // On the input side, move releaseHandler() and init() to a finally block.

                    if (read != null) {
                        _transport.messageReceived(read, _remotePeer, null, timeToRecv, _size);
                        if (_messagesRead <= 0)
                            enqueueInfoMessage();
                        _lastReceiveTime = System.currentTimeMillis();
                        _messagesRead++;
                    }
                    // get it ready for the next I2NP message
                    init();
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error parsing I2NP message", ioe);
                    _context.statManager().addRateData("ntcp.corruptI2NPIOE", 1, getUptime());
                    close();
                    // handler and databuf are lost
                    return;
                } catch (I2NPMessageException ime) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error parsing I2NP message", ime);
                    _context.statManager().addRateData("ntcp.corruptI2NPIME", 1, getUptime());
                    close();
                    // handler and databuf are lost
                    return;
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("CRC incorrect for message " + _messagesRead + " (calc=" + val + " expected=" + _expectedCrc + ") size=" + _size + " blocks " + _blocks);
                    _context.statManager().addRateData("ntcp.corruptI2NPCRC", 1, getUptime());
                // FIXME should we try to read in the message and keep going?
                close();
                // databuf is lost
                return;
            }
        }
    }

    @Override
    public String toString() {
        return "NTCP Connection to " +
               (_remotePeer == null ? "unknown " : _remotePeer.calculateHash().toBase64().substring(0,6)) +
               " inbound? " + _isInbound + " established? " + _established +
               " created " + DataHelper.formatDuration(getTimeSinceCreated()) + " ago";
    }
}
