package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.Transport;
import static net.i2p.router.transport.Transport.AddressSource.*;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportUtil;
import static net.i2p.router.transport.TransportUtil.IPv6Config.*;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 *  The NIO TCP transport
 */
public class NTCPTransport extends TransportImpl {
    private final Log _log;
    private final SharedBid _fastBid;
    private final SharedBid _slowBid;
    private final SharedBid _slowCostBid;
    /** save some conns for inbound */
    private final SharedBid _nearCapacityBid;
    private final SharedBid _nearCapacityCostBid;
    private final SharedBid _transientFail;
    private final Object _conLock;
    private final Map<Hash, NTCPConnection> _conByIdent;
    private final EventPumper _pumper;
    private final Reader _reader;
    private net.i2p.router.transport.ntcp.Writer _writer;
    private int _ssuPort;
    /** synch on this */
    private final Set<InetSocketAddress> _endpoints;

    /**
     * list of NTCPConnection of connections not yet established that we
     * want to remove on establishment or close on timeout
     */
    private final Set<NTCPConnection> _establishing;
    /** "bloom filter" */
    private final DecayingBloomFilter _replayFilter;

    /**
     *  Do we have a public IPv6 address?
     *  TODO periodically update via CSFI.NetMonitor?
     */
    private boolean _haveIPv6Address;

    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
    public static final int DEFAULT_COST = 10;
    
    /** this is rarely if ever used, default is to bind to wildcard address */
    public static final String PROP_BIND_INTERFACE = "i2np.ntcp.bindInterface";

    private final NTCPSendFinisher _finisher;
    private final DHSessionKeyBuilder.Factory _dhFactory;
    private long _lastBadSkew;
    private static final long[] RATES = { 10*60*1000 };

    // Opera doesn't have the char, TODO check UA
    //private static final String THINSP = "&thinsp;/&thinsp;";
    private static final String THINSP = " / ";

    /**
     *  RI sigtypes supported in 0.9.16
     */
    private static final String MIN_SIGTYPE_VERSION = "0.9.16";


    public NTCPTransport(RouterContext ctx, DHSessionKeyBuilder.Factory dh) {
        super(ctx);
        _dhFactory = dh;
        _log = ctx.logManager().getLog(getClass());

        _context.statManager().createRateStat("ntcp.sendTime", "Total message lifetime when sent completely", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.sendQueueSize", "How many messages were ahead of the current one on the connection's queue when it was first added", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.receiveTime", "How long it takes to receive an inbound message", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.receiveSize", "How large the received message was", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.sendBacklogTime", "How long the head of the send queue has been waiting when we fail to add a new one to the queue (period is the number of messages queued)", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.failsafeWrites", "How many times do we need to proactively add in an extra nio write to a peer at any given failsafe pass?", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.failsafeCloses", "How many times do we need to proactively close an idle connection to a peer at any given failsafe pass?", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.failsafeInvalid", "How many times do we close a connection to a peer to work around a JVM bug?", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.accept", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.attemptBanlistedPeer", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.attemptUnreachablePeer", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.closeOnBacklog", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedIOE", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.connectFailedInvalidPort", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.bidRejectedLocalAddress", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.bidRejectedNoNTCPAddress", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedTimeout", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedTimeoutIOE", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.connectFailedUnresolved", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.connectImmediate", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.connectSuccessful", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptDecryptedI2NP", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptI2NPCRC", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptI2NPIME", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptI2NPIOE", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptMetaCRC", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptSkew", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.corruptTooLargeI2NP", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.dontSendOnBacklog", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.inboundCheckConnection", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.inboundEstablished", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.inboundEstablishedDuplicate", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.infoMessageEnqueued", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.floodInfoMessageEnqueued", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidDH", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidHXY", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidHXxorBIH", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundDFE", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundIOE", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundSignature", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundSize", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidInboundSkew", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidSignature", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.liveReadBufs", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.multipleCloseOnRemove", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.outboundEstablishFailed", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.outboundFailedIOEImmediate", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.invalidOutboundSkew", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.noBidTooLargeI2NP", "send size", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.queuedRecv", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.read", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.readEOF", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.readError", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.receiveCorruptEstablishment", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.receiveMeta", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.registerConnect", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.replayHXxorBIH", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.throttledReadComplete", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.throttledWriteComplete", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.wantsQueuedWrite", "", "ntcp", RATES);
        //_context.statManager().createRateStat("ntcp.write", "", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.writeError", "", "ntcp", RATES);
        _endpoints = new HashSet<InetSocketAddress>(4);
        _establishing = new ConcurrentHashSet<NTCPConnection>(16);
        _conLock = new Object();
        _conByIdent = new ConcurrentHashMap<Hash, NTCPConnection>(64);
        _replayFilter = new DecayingHashSet(ctx, 10*60*1000, 8, "NTCP-Hx^HI");

        _finisher = new NTCPSendFinisher(ctx, this);

        _pumper = new EventPumper(ctx, this);
        _reader = new Reader(ctx);
        _writer = new net.i2p.router.transport.ntcp.Writer(ctx);

        _fastBid = new SharedBid(25); // best
        _slowBid = new SharedBid(70); // better than ssu unestablished, but not better than ssu established
        _slowCostBid = new SharedBid(85);
        _nearCapacityBid = new SharedBid(90); // not better than ssu - save our conns for inbound
        _nearCapacityCostBid = new SharedBid(105);
        _transientFail = new SharedBid(TransportBid.TRANSIENT_FAIL);
    }

    /**
     * @param con that is established
     * @return the previous connection to the same peer, null if no such.
     */
    NTCPConnection inboundEstablished(NTCPConnection con) {
        _context.statManager().addRateData("ntcp.inboundEstablished", 1);
        markReachable(con.getRemotePeer().calculateHash(), true);
        //_context.banlist().unbanlistRouter(con.getRemotePeer().calculateHash());
        NTCPConnection old;
        synchronized (_conLock) {
            old = _conByIdent.put(con.getRemotePeer().calculateHash(), con);
        }
        return old;
    }

    protected void outboundMessageReady() {
        OutNetMessage msg = getNextMessage();
        if (msg != null) {
            RouterInfo target = msg.getTarget();
            RouterIdentity ident = target.getIdentity();
            Hash ih = ident.calculateHash();
            NTCPConnection con = null;
            boolean isNew = false;
            boolean fail = false;
            synchronized (_conLock) {
                con = _conByIdent.get(ih);
                if (con == null) {
                    isNew = true;
                    RouterAddress addr = getTargetAddress(target);
                    if (addr != null) {
                        con = new NTCPConnection(_context, this, ident, addr);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Send on a new con: " + con + " at " + addr + " for " + ih);
                        _conByIdent.put(ih, con);
                    } else {
                        // race, RI changed out from under us
                        // call afterSend below outside of conLock
                        fail = true;
                    }
                }
            }
            if (fail) {
                // race, RI changed out from under us, maybe SSU can handle it
                if (_log.shouldLog(Log.WARN))
                    _log.warn("we bid on a peer who doesn't have an ntcp address? " + target);
                afterSend(msg, false);
                return;
            }
            if (isNew) {
                con.send(msg); // doesn't do anything yet, just enqueues it
                // As of 0.9.12, don't send our info if the first message is
                // doing the same (common when connecting to a floodfill).
                // Also, put the info message after whatever we are trying to send
                // (it's a priority queue anyway and the info is low priority)
                // Prior to 0.9.12, Bob would not send his RI unless he had ours,
                // but that's fixed in 0.9.12.
                boolean shouldSkipInfo = false;
                I2NPMessage m = msg.getMessage();
                if (m.getType() == DatabaseStoreMessage.MESSAGE_TYPE) {
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage) m;
                    if (dsm.getKey().equals(_context.routerHash())) {
                        shouldSkipInfo = true;
                    }
                }
                if (!shouldSkipInfo) {
                    con.enqueueInfoMessage();
                } else if (_log.shouldLog(Log.INFO)) {
                    _log.info("SKIPPING INFO message: " + con);
                }

                try {
                    SocketChannel channel = SocketChannel.open();
                    con.setChannel(channel);
                    channel.configureBlocking(false);
                    _pumper.registerConnect(con);
                    con.getEstablishState().prepareOutbound();
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error opening a channel", ioe);
                    _context.statManager().addRateData("ntcp.outboundFailedIOEImmediate", 1);
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
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Multiple connections on out ready, closing " + old + " and keeping " + con);
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

    @Override
    public void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue, long msToSend) {
        super.afterSend(msg, sendSuccessful, allowRequeue, msToSend);
    }

    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        if (!isAlive())
            return null;
        if (dataSize > NTCPConnection.MAX_MSG_SIZE) {
            // let SSU deal with it
            _context.statManager().addRateData("ntcp.noBidTooLargeI2NP", dataSize);
            return null;
        }
        Hash peer = toAddress.getIdentity().calculateHash();
        if (_context.banlist().isBanlisted(peer, STYLE)) {
            // we aren't banlisted in general (since we are trying to get a bid), but we have
            // recently banlisted the peer on the NTCP transport, so don't try it
            _context.statManager().addRateData("ntcp.attemptBanlistedPeer", 1);
            return null;
        } else if (isUnreachable(peer)) {
            _context.statManager().addRateData("ntcp.attemptUnreachablePeer", 1);
            return null;
        }

        boolean established = isEstablished(toAddress.getIdentity());
        if (established) { // should we check the queue size?  nah, if its valid, use it
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("fast bid when trying to send to " + peer + " as its already established");
            return _fastBid;
        }

        RouterAddress addr = getTargetAddress(toAddress);
        if (addr == null) {
            markUnreachable(peer);
            return null;
        }

        // Check for supported sig type
        SigType type = toAddress.getIdentity().getSigType();
        if (type == null || !type.isAvailable()) {
            markUnreachable(peer);
            return null;
        }

        // Can we connect to them if we are not DSA?
        RouterInfo us = _context.router().getRouterInfo();
        if (us != null) {
            RouterIdentity id = us.getIdentity();
            if (id.getSigType() != SigType.DSA_SHA1) {
                String v = toAddress.getOption("router.version");
                if (v != null && VersionComparator.comp(v, MIN_SIGTYPE_VERSION) < 0) {
                    markUnreachable(peer);
                    return null;
                }
            }
        }

        if (!allowConnection()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("no bid when trying to send to " + peer + ", max connection limit reached");
            return _transientFail;
        }

        //if ( (_myAddress != null) && (_myAddress.equals(addr)) )
        //    return null; // dont talk to yourself

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("slow bid when trying to send to " + peer);
        if (haveCapacity()) {
            if (addr.getCost() > DEFAULT_COST)
                return _slowCostBid;
            else
                return _slowBid;
        } else {
            if (addr.getCost() > DEFAULT_COST)
                return _nearCapacityCostBid;
            else
                return _nearCapacityBid;
        }
    }

    /**
     *  Get first available address we can use.
     *  @return address or null
     *  @since 0.9.6
     */
    private RouterAddress getTargetAddress(RouterInfo target) {
        List<RouterAddress> addrs = getTargetAddresses(target);
        for (int i = 0; i < addrs.size(); i++) {
            RouterAddress addr = addrs.get(i);
            byte[] ip = addr.getIP();
            if (!TransportUtil.isValidPort(addr.getPort()) || ip == null) {
                //_context.statManager().addRateData("ntcp.connectFailedInvalidPort", 1);
                //_context.banlist().banlistRouter(toAddress.getIdentity().calculateHash(), "Invalid NTCP address", STYLE);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("no bid when trying to send to " + peer + " as they don't have a valid ntcp address");
                continue;
            }
            if (!isValid(ip)) {
                if (! _context.getBooleanProperty("i2np.ntcp.allowLocal")) {
                    //_context.statManager().addRateData("ntcp.bidRejectedLocalAddress", 1);
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("no bid when trying to send to " + peer + " as they have a private ntcp address");
                    continue;
                }
            }
            return addr;
        }
        return null;
    }
    
    /**
     * An IPv6 address is only valid if we are configured to support IPv6
     * AND we have a public IPv6 address.
     *
     * @param addr may be null, returns false
     * @since 0.9.8
     */
    private boolean isValid(byte addr[]) {
        if (addr == null) return false;
        if (isPubliclyRoutable(addr) &&
            (addr.length != 16 || _haveIPv6Address))
            return true;
        return false;
    }

    public boolean allowConnection() {
        return countActivePeers() < getMaxConnections();
    }

    /** queue up afterSend call, which can take some time w/ jobs, etc */
    void sendComplete(OutNetMessage msg) { _finisher.add(msg); }

    private boolean isEstablished(RouterIdentity peer) {
        return isEstablished(peer.calculateHash());
    }

    @Override
    public boolean isEstablished(Hash dest) {
            NTCPConnection con = _conByIdent.get(dest);
            return (con != null) && con.isEstablished() && !con.isClosed();
    }

    @Override
    public boolean isBacklogged(Hash dest) {
            NTCPConnection con = _conByIdent.get(dest);
            return (con != null) && con.isEstablished() && con.tooBacklogged();
    }

    NTCPConnection removeCon(NTCPConnection con) {
        NTCPConnection removed = null;
        RouterIdentity ident = con.getRemotePeer();
        if (ident != null) {
            synchronized (_conLock) {
                removed = _conByIdent.remove(ident.calculateHash());
            }
        }
        return removed;
    }

    /**
     * How many peers can we talk to right now?
     *
     */
    @Override
    public int countActivePeers() { return _conByIdent.size(); }

    /**
     * How many peers are we actively sending messages to (this minute)
     */
    @Override
    public int countActiveSendPeers() {
        int active = 0;
        for (NTCPConnection con : _conByIdent.values()) {
                if ( (con.getTimeSinceSend() <= 60*1000) || (con.getTimeSinceReceive() <= 60*1000) )
                    active++;
        }
        return active;
    }

    /** @param skew in seconds */
    void setLastBadSkew(long skew) {
        _lastBadSkew = skew;
    }

    /**
     * Return our peer clock skews on this transport.
     * Vector composed of Long, each element representing a peer skew in seconds.
     */
    @Override
    public Vector<Long> getClockSkews() {
        Vector<Long> skews = new Vector<Long>();

        for (NTCPConnection con : _conByIdent.values()) {
            if (con.isEstablished())
                skews.addElement(Long.valueOf(con.getClockSkew()));
        }

        // If we don't have many peers, maybe it is because of a bad clock, so
        // return the last bad skew we got
        if (skews.size() < 5 && _lastBadSkew != 0)
            skews.addElement(Long.valueOf(_lastBadSkew));

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("NTCP transport returning " + skews.size() + " peer clock skews.");
        return skews;
    }

    /**
     *  Incoming connection replay detection.
     *  As there is no timestamp in the first message, we can't detect
     *  something long-delayed. To be fixed in next version of NTCP.
     *
     *  @param hxhi 32 bytes
     *  @return valid
     *  @since 0.9.12
     */
    boolean isHXHIValid(byte[] hxhi) {
        return !_replayFilter.add(hxhi, 0, 8);
    }

    private static final int MIN_CONCURRENT_READERS = 2;  // unless < 32MB
    private static final int MIN_CONCURRENT_WRITERS = 2;  // unless < 32MB
    private static final int MAX_CONCURRENT_READERS = 4;
    private static final int MAX_CONCURRENT_WRITERS = 4;

    /**
     *  Called by TransportManager.
     *  Caller should stop the transport first, then
     *  verify stopped with isAlive()
     *  Unfortunately TransportManager doesn't do that, so we
     *  check here to prevent two pumpers.
     */
    public synchronized void startListening() {
        // try once again to prevent two pumpers which is fatal
        if (_pumper.isAlive())
            return;
        if (_log.shouldLog(Log.WARN)) _log.warn("Starting ntcp transport listening");

        startIt();
        RouterAddress addr = configureLocalAddress();
        int port;
        if (addr != null)
            // probably not set
            port = addr.getPort();
        else
            // received by externalAddressReceived() from TransportManager
            port = _ssuPort;
        RouterAddress myAddress = bindAddress(port);
        if (myAddress != null) {
            // fixed interface, or bound to the specified host
            replaceAddress(myAddress);
        } else if (addr != null) {
            // specified host, bound to wildcard
            replaceAddress(addr);
        } else if (port > 0) {
            // all detected interfaces
            for (InetAddress ia : getSavedLocalAddresses()) {
                OrderedProperties props = new OrderedProperties();
                props.setProperty(RouterAddress.PROP_HOST, ia.getHostAddress());
                props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
                int cost = getDefaultCost(ia instanceof Inet6Address);
                myAddress = new RouterAddress(STYLE, props, cost);
                replaceAddress(myAddress);
            }
        }
        // TransportManager.startListening() calls router.rebuildRouterInfo()
    }

    /**
     *  Only called by externalAddressReceived().
     *
     *  Doesn't actually restart unless addr is non-null and
     *  the port is different from the current listen port.
     *  If addr is null, removes all addresses.
     *
     *  If we had interface addresses before, we lost them.
     *
     *  @param addr may be null
     */
    private synchronized void restartListening(RouterAddress addr) {
        if (addr != null) {
            RouterAddress myAddress = bindAddress(addr.getPort());
            if (myAddress != null)
                replaceAddress(myAddress);
            else
                replaceAddress(addr);
            // UDPTransport.rebuildExternalAddress() calls router.rebuildRouterInfo()
        } else {
            replaceAddress(null);
        }
    }

    /**
     *  Start up. Caller must synchronize.
     *  @since 0.8.3
     */
    private void startIt() {
        _finisher.start();
        _pumper.startPumping();

        long maxMemory = SystemVersion.getMaxMemory();
        int nr, nw;
        if (maxMemory < 32*1024*1024) {
            nr = nw = 1;
        } else if (maxMemory < 64*1024*1024) {
            nr = nw = 2;
        } else {
            nr = Math.max(MIN_CONCURRENT_READERS, Math.min(MAX_CONCURRENT_READERS, _context.bandwidthLimiter().getInboundKBytesPerSecond() / 20));
            nw = Math.max(MIN_CONCURRENT_WRITERS, Math.min(MAX_CONCURRENT_WRITERS, _context.bandwidthLimiter().getOutboundKBytesPerSecond() / 20));
        }
        _reader.startReading(nr);
        _writer.startWriting(nw);
    }

    public boolean isAlive() {
        return _pumper.isAlive();
    }

    /**
     *  Only does something if myPort > 0 and myPort != current bound port
     *  (or there's no current port, or the configured interface or hostname changed).
     *  If we are changing the bound port, this restarts everything, which takes a long time.
     *
     *  call from synchronized method
     *
     *  @param myPort does nothing if <= 0
     *  @return new address ONLY if bound to specific address, otherwise null
     */
    private RouterAddress bindAddress(int port) {
        RouterAddress myAddress = null;
        if (port > 0) {
            InetAddress bindToAddr = null;
            String bindTo = _context.getProperty(PROP_BIND_INTERFACE);

            if (bindTo == null) {
                // If we are configured with a fixed IP address,
                // AND it's one of our local interfaces,
                // bind only to that.
                bindTo = getFixedHost();
            }

            if (bindTo != null) {
                try {
                    bindToAddr = InetAddress.getByName(bindTo);
                } catch (UnknownHostException uhe) {
                    _log.error("Invalid NTCP bind interface specified [" + bindTo + "]", uhe);
                    // this can be implemented later, just updates some stats
                    // see udp/UDPTransport.java
                    //setReachabilityStatus(CommSystemFacade.STATUS_HOSED);
                    //return null;
                    // fall thru
                }
            }

            try {
                InetSocketAddress addr;
                if (bindToAddr == null) {
                    addr = new InetSocketAddress(port);
                } else {
                    addr = new InetSocketAddress(bindToAddr, port);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Binding only to " + bindToAddr);
                    OrderedProperties props = new OrderedProperties();
                    props.setProperty(RouterAddress.PROP_HOST, bindTo);
                    props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
                    int cost = getDefaultCost(false);
                    myAddress = new RouterAddress(STYLE, props, cost);
                }
                if (!_endpoints.isEmpty()) {
                    // If we are already bound to the new address, OR
                    // if the host is specified and we are bound to the wildcard on the same port,
                    // do nothing. Changing config from wildcard to a specified host will
                    // require a restart.
                    if (_endpoints.contains(addr) ||
                        (bindToAddr != null && _endpoints.contains(new InetSocketAddress(port)))) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Already listening on " + addr);
                        return null;
                    }
                    // FIXME support multiple binds
                    // FIXME just close and unregister
                    stopWaitAndRestart();
                }
                if (!TransportUtil.isValidPort(port))
                    _log.error("Specified NTCP port is " + port + ", ports lower than 1024 not recommended");
                ServerSocketChannel chan = ServerSocketChannel.open();
                chan.configureBlocking(false);
                chan.socket().bind(addr);
                _endpoints.add(addr);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Listening on " + addr);
                _pumper.register(chan);
            } catch (IOException ioe) {
                _log.error("Error listening", ioe);
                myAddress = null;
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Outbound NTCP connections only - no listener configured");
        }
        return myAddress;
    }

    /**
     *  @return configured host or null. Must be one of our local interfaces.
     *  @since IPv6 moved from bindAddress()
     */
    private String getFixedHost() {
        boolean isFixed = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true")
                          .toLowerCase(Locale.US).equals("false");
        String fixedHost = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        if (isFixed && fixedHost != null) {
            try {
                String testAddr = InetAddress.getByName(fixedHost).getHostAddress();
                // FIXME range of IPv6 addresses
                if (Addresses.getAddresses().contains(testAddr))
                    return testAddr;
            } catch (UnknownHostException uhe) {}
        }
        return null;
    }

    /**
     *  Caller must sync
     *  @since IPv6 moved from externalAddressReceived()
     */
    private void stopWaitAndRestart() {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Halting NTCP to change address");
        stopListening();
        // Wait for NTCP Pumper to stop so we don't end up with two...
        while (isAlive()) {
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("Restarting NTCP transport listening");
        startIt();
    }

    /**
     *  Hook for NTCPConnection
     */
    Reader getReader() { return _reader; }

    /**
     *  Hook for NTCPConnection
     */
    net.i2p.router.transport.ntcp.Writer getWriter() { return _writer; }

    public String getStyle() { return STYLE; }

    /**
     *  Hook for NTCPConnection
     */
    EventPumper getPumper() { return _pumper; }

    /**
     *  @since 0.9
     */
    DHSessionKeyBuilder getDHBuilder() {
        return _dhFactory.getBuilder();
    }

    /**
     * Return an unused DH key builder
     * to be put back onto the queue for reuse.
     *
     * @param builder must not have a peerPublicValue set
     * @since 0.9.16
     */
    void returnUnused(DHSessionKeyBuilder builder) {
        _dhFactory.returnUnused(builder);
    }

    /**
     * how long from initial connection attempt (accept() or connect()) until
     * the con must be established to avoid premature close()ing
     */
    public static final int ESTABLISH_TIMEOUT = 10*1000;

    /** add us to the establishment timeout process */
    void establishing(NTCPConnection con) {
            _establishing.add(con);
    }

    /**
     * called in the EventPumper no more than once a second or so, closing
     * any unconnected/unestablished connections
     */
    void expireTimedOut() {
        int expired = 0;

            for (Iterator<NTCPConnection> iter = _establishing.iterator(); iter.hasNext(); ) {
                NTCPConnection con = iter.next();
                if (con.isClosed() || con.isEstablished()) {
                    iter.remove();
                } else if (con.getTimeSinceCreated() > ESTABLISH_TIMEOUT) {
                    iter.remove();
                    con.close();
                    expired++;
                }
            }

        if (expired > 0)
            _context.statManager().addRateData("ntcp.outboundEstablishFailed", expired);
    }

    //private boolean bindAllInterfaces() { return true; }

    /**
     *  Generally returns null
     *  caller must synch on this
     */
    private RouterAddress configureLocalAddress() {
            // this generally returns null -- see javadoc
            RouterAddress addr = createNTCPAddress();
            if (addr != null) {
                if (addr.getPort() <= 0) {
                    addr = null;
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("NTCP address is outbound only, since the NTCP configuration is invalid");
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("NTCP address configured: " + addr);
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("NTCP address is outbound only");
            }
            return addr;
    }

    /**
     * This only creates an address if the hostname AND port are set in router.config,
     * which should be rare.
     * Otherwise, notifyReplaceAddress() below takes care of it.
     * Note this is called both from above and from NTCPTransport.startListening()
     *
     * @since IPv6 moved from CSFI
     */
    private RouterAddress createNTCPAddress() {
        // Fixme doesn't check PROP_BIND_INTERFACE
        String name = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        if ( (name == null) || (name.trim().length() <= 0) || ("null".equals(name)) )
            return null;
        int p = _context.getProperty(PROP_I2NP_NTCP_PORT, -1);
        if (p <= 0 || p >= 64*1024)
            return null;
        OrderedProperties props = new OrderedProperties();
        props.setProperty(RouterAddress.PROP_HOST, name);
        props.setProperty(RouterAddress.PROP_PORT, Integer.toString(p));
        int cost = getDefaultCost(false);
        RouterAddress addr = new RouterAddress(STYLE, props, cost);
        return addr;
    }
    
    private int getDefaultCost(boolean isIPv6) {
        int rv = DEFAULT_COST;
        if (isIPv6) {
            TransportUtil.IPv6Config config = getIPv6Config();
            if (config == IPV6_PREFERRED)
                rv--;
            else if (config == IPV6_NOT_PREFERRED)
                rv++;
        }
        return rv;
    }

    /**
     *  UDP changed addresses, tell NTCP and (possibly) restart
     *
     *  @since IPv6 moved from CSFI.notifyReplaceAddress()
     */
    @Override
    public void externalAddressReceived(AddressSource source, byte[] ip, int port) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Received address: " + Addresses.toString(ip, port) + " from: " + source);
        if ((source == SOURCE_INTERFACE || source == SOURCE_SSU)
             && ip != null && ip.length == 16) {
            // must be set before isValid() call
            _haveIPv6Address = true;
        }
        if (ip != null && !isValid(ip)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid address: " + Addresses.toString(ip, port) + " from: " + source);
            return;
        }
        if (!isAlive()) {
            if (source == SOURCE_INTERFACE || source == SOURCE_UPNP) {
                try {
                    InetAddress ia = InetAddress.getByAddress(ip);
                    saveLocalAddress(ia);
                } catch (UnknownHostException uhe) {}
            } else if (source == SOURCE_CONFIG) {
                // save for startListening()
                _ssuPort = port;
            }
            return;
        }
        // ignore UPnP for now, get everything from SSU
        if (source != SOURCE_SSU)
            return;
        externalAddressReceived(ip, port);
    }
    
    /**
     *  UDP changed addresses, tell NTCP and restart.
     *  Port may be set to indicate requested port even if ip is null.
     *
     *  @param ip previously validated
     *  @since IPv6 moved from CSFI.notifyReplaceAddress()
     */
    private synchronized void externalAddressReceived(byte[] ip, int port) {
        // FIXME just take first IPv4 address for now
        // FIXME if SSU set to hostname, NTCP will be set to IP
        RouterAddress oldAddr = getCurrentAddress(false);
        if (_log.shouldLog(Log.INFO))
            _log.info("Changing NTCP Address? was " + oldAddr);

        OrderedProperties newProps = new OrderedProperties();
        int cost;
        if (oldAddr == null) {
            cost = getDefaultCost(ip != null && ip.length == 16);
        } else {
            cost = oldAddr.getCost();
            newProps.putAll(oldAddr.getOptionsMap());
        }
        RouterAddress newAddr = new RouterAddress(STYLE, newProps, cost);

        boolean changed = false;

        // Auto Port Setting
        // old behavior (<= 0.7.3): auto-port defaults to false, and true trumps explicit setting
        // new behavior (>= 0.7.4): auto-port defaults to true, but explicit setting trumps auto
        // TODO rewrite this to operate on ints instead of strings
        String oport = newProps.getProperty(RouterAddress.PROP_PORT);
        String nport = null;
        String cport = _context.getProperty(PROP_I2NP_NTCP_PORT);
        if (cport != null && cport.length() > 0) {
            nport = cport;
        } else if (_context.getBooleanPropertyDefaultTrue(PROP_I2NP_NTCP_AUTO_PORT)) {
            // 0.9.6 change
            // This wasn't quite right, as udpAddr is the EXTERNAL port and we really
            // want NTCP to bind to the INTERNAL port the first time,
            // because if they are different, the NAT is changing them, and
            // it probably isn't mapping UDP and TCP the same.
            if (port > 0)
                // should always be true
                nport = Integer.toString(port);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("old port: " + oport + " config: " + cport + " new: " + nport);
        //if (nport == null || nport.length() <= 0)
        //    return;
        // 0.9.6 change
        // Don't have NTCP "chase" SSU's external port,
        // as it may change, possibly frequently.
        //if (oport == null || ! oport.equals(nport)) {
        if (oport == null && nport != null && nport.length() > 0) {
            newProps.setProperty(RouterAddress.PROP_PORT, nport);
            changed = true;
        }

        // Auto IP Setting
        // old behavior (<= 0.7.3): auto-ip defaults to false, and trumps configured hostname,
        //                          and ignores reachability status - leading to
        //                          "firewalled with inbound TCP enabled" warnings.
        // new behavior (>= 0.7.4): auto-ip defaults to true, and explicit setting trumps auto,
        //                          and only takes effect if reachability is OK.
        //                          And new "always" setting ignores reachability status, like
        //                          "true" was in 0.7.3
        String ohost = newProps.getProperty(RouterAddress.PROP_HOST);
        String enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true").toLowerCase(Locale.US);
        String name = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        // hostname config trumps auto config
        if (name != null && name.length() > 0)
            enabled = "false";

        // assume SSU is happy if the address is non-null
        // TODO is this sufficient?
        boolean ssuOK = ip != null;
        if (_log.shouldLog(Log.INFO))
            _log.info("old: " + ohost + " config: " + name + " auto: " + enabled + " ssuOK? " + ssuOK);
        if (enabled.equals("always") ||
            (Boolean.parseBoolean(enabled) && ssuOK)) {
            if (!ssuOK) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("null address with always config", new Exception());
                return;
            }
            // ip non-null
            String nhost = Addresses.toString(ip);
            if (_log.shouldLog(Log.INFO))
                _log.info("old: " + ohost + " config: " + name + " new: " + nhost);
            if (nhost == null || nhost.length() <= 0)
                return;
            if (ohost == null || ! ohost.equalsIgnoreCase(nhost)) {
                newProps.setProperty(RouterAddress.PROP_HOST, nhost);
                changed = true;
            }
        } else if (enabled.equals("false") &&
                   name != null && name.length() > 0 &&
                   !name.equals(ohost)) {
            // Host name is configured, and we have a port (either auto or configured)
            // but we probably only get here if the port is auto,
            // otherwise createNTCPAddress() would have done it already
            if (_log.shouldLog(Log.INFO))
                _log.info("old host: " + ohost + " config: " + name + " new: " + name);
            newProps.setProperty(RouterAddress.PROP_HOST, name);
            changed = true;
        } else if (ohost == null || ohost.length() <= 0) {
            return;
        } else if (Boolean.parseBoolean(enabled) && !ssuOK) {
            // UDP transitioned to not-OK, turn off NTCP address
            // This will commonly happen at startup if we were initially OK
            // because UPnP was successful, but a subsequent SSU Peer Test determines
            // we are still firewalled (SW firewall, bad UPnP indication, etc.)
            if (_log.shouldLog(Log.INFO))
                _log.info("old host: " + ohost + " config: " + name + " new: null");
            newAddr = null;
            changed = true;
        }

        if (!changed) {
            if (oldAddr != null) {
                // change cost only?
                int oldCost = oldAddr.getCost();
                int newCost = getDefaultCost(ohost != null && ohost.contains(":"));
                if (ADJUST_COST && !haveCapacity())
                    newCost += CONGESTION_COST_ADJUSTMENT;
                if (newCost != oldCost) {
                    newAddr.setCost(newCost);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Changing NTCP cost from " + oldCost + " to " + newCost);
                    // fall thru and republish
                } else {
                    _log.info("No change to NTCP Address");
                    return;
                }
            } else {
                _log.info("No change to NTCP Address");
                return;
            }
        }

        // stopListening stops the pumper, readers, and writers, so required even if
        // oldAddr == null since startListening starts them all again
        //
        // really need to fix this so that we can change or create an inbound address
        // without tearing down everything
        // Especially on disabling the address, we shouldn't tear everything down.
        //
        //if (_log.shouldLog(Log.WARN))
        //    _log.warn("Halting NTCP to change address");
        //stopListening();
        // Wait for NTCP Pumper to stop so we don't end up with two...
        //while (isAlive()) {
        //    try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        //}
        restartListening(newAddr);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Updating NTCP Address with " + newAddr);
        return;     	
    }
    


    /**
     *  If we didn't used to be forwarded, and we have an address,
     *  and we are configured to use UPnP, update our RouterAddress
     *
     *  Don't do anything now. If it fails, we don't know if it's
     *  because there is no firewall, or if the firewall rejected the request.
     *  So we just use the SSU reachability status
     *  to decide whether to enable inbound NTCP. SSU will have CSFI build a new
     *  NTCP address when it transitions to OK.
     */
    @Override
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason) {
        if (_log.shouldLog(Log.WARN)) {
            if (success)
                _log.warn("UPnP has opened the NTCP port: " + port + " via " + Addresses.toString(ip, externalPort));
            else
                _log.warn("UPnP has failed to open the NTCP port: " + port + " reason: " + reason);
        }
    }

    /**
     *  @return current IPv4 port, else NTCP configured port, else -1 (but not UDP port if auto)
     */
    @Override
    public int getRequestedPort() {
        RouterAddress addr = getCurrentAddress(false);
        if (addr != null) {
            int port = addr.getPort();
            if (port > 0)
                return port;
        }
        // would be nice to do this here but we can't easily get to the UDP transport.getRequested_Port()
        // from here, so we do it in TransportManager.
        // if (Boolean.valueOf(_context.getProperty(CommSystemFacadeImpl.PROP_I2NP_NTCP_AUTO_PORT)).booleanValue())
        //    return foo;
        return _context.getProperty(PROP_I2NP_NTCP_PORT, -1);
    }

    /**
     * Maybe we should trust UPnP here and report OK if it opened the port, but
     * for now we don't. Just go through and if we have one inbound connection,
     * we must be good. As we drop idle connections pretty quickly, this will
     * be fairly accurate.
     *
     * We have to be careful here because much of the router console code assumes
     * that the reachability status is really just the UDP status.
     */
    @Override
    public short getReachabilityStatus() { 
        // If we have an IPv4 address
        if (isAlive() && getCurrentAddress(false) != null) {
                for (NTCPConnection con : _conByIdent.values()) {
                    if (con.isInbound())
                        return CommSystemFacade.STATUS_OK;
                }
        }
        return CommSystemFacade.STATUS_UNKNOWN;
    }

    /**
     *  This doesn't (completely) block, caller should check isAlive()
     *  before calling startListening() or restartListening()
     */
    public synchronized void stopListening() {
        if (_log.shouldLog(Log.WARN)) _log.warn("Stopping ntcp transport");
        _pumper.stopPumping();
        _writer.stopWriting();
        _reader.stopReading();
        _finisher.stop();
        List<NTCPConnection> cons;
        synchronized (_conLock) {
            cons = new ArrayList<NTCPConnection>(_conByIdent.values());
            _conByIdent.clear();
        }
        for (NTCPConnection con : cons) {
            con.close();
        }
        NTCPConnection.releaseResources();
        replaceAddress(null);
        _endpoints.clear();
    }

    public static final String STYLE = "NTCP";

    public void renderStatusHTML(java.io.Writer out, int sortFlags) throws IOException {}

    @Override
    public void renderStatusHTML(java.io.Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet<NTCPConnection> peers = new TreeSet<NTCPConnection>(getComparator(sortFlags));
        peers.addAll(_conByIdent.values());

        long offsetTotal = 0;
        float bpsSend = 0;
        float bpsRecv = 0;
        long totalUptime = 0;
        long totalSend = 0;
        long totalRecv = 0;

        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3 id=\"ntcpcon\">").append(_("NTCP connections")).append(": ").append(peers.size());
        buf.append(". ").append(_("Limit")).append(": ").append(getMaxConnections());
        buf.append(". ").append(_("Timeout")).append(": ").append(DataHelper.formatDuration2(_pumper.getIdleTimeout()));
        buf.append(".</h3>\n" +
                   "<table>\n" +
                   "<tr><th><a href=\"#def.peer\">").append(_("Peer")).append("</a></th>" +
                   "<th>").append(_("Dir")).append("</th>" +
                   "<th>").append(_("IPv6")).append("</th>" +
                   "<th align=\"right\"><a href=\"#def.idle\">").append(_("Idle")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.rate\">").append(_("In/Out")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.up\">").append(_("Up")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.skew\">").append(_("Skew")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.send\">").append(_("TX")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.recv\">").append(_("RX")).append("</a></th>" +
                   "<th>").append(_("Out Queue")).append("</th>" +
                   "<th>").append(_("Backlogged?")).append("</th>" +
                   //"<th>").append(_("Reading?")).append("</th>" +
                   " </tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        for (NTCPConnection con : peers) {
            buf.append("<tr><td class=\"cells\" align=\"left\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(con.getRemotePeer().calculateHash()));
            //byte[] ip = getIP(con.getRemotePeer().calculateHash());
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td><td class=\"cells\" align=\"center\">");
            if (con.isInbound())
                buf.append("<img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"").append(_("Inbound")).append("\"/>");
            else
                buf.append("<img src=\"/themes/console/images/outbound.png\" alt=\"Outbound\" title=\"").append(_("Outbound")).append("\"/>");
            buf.append("</td><td class=\"cells\" align=\"center\">");
            if (con.isIPv6())
                buf.append("&#x2713;");
            else
                buf.append("&nbsp;");
            buf.append("</td><td class=\"cells\" align=\"right\">");
            buf.append(DataHelper.formatDuration2(con.getTimeSinceReceive()));
            buf.append(THINSP).append(DataHelper.formatDuration2(con.getTimeSinceSend()));
            buf.append("</td><td class=\"cells\" align=\"right\">");
            if (con.getTimeSinceReceive() < 2*60*1000) {
                float r = con.getRecvRate();
                buf.append(formatRate(r / 1024));
                bpsRecv += r;
            } else {
                buf.append(formatRate(0));
            }
            buf.append(THINSP);
            if (con.getTimeSinceSend() < 2*60*1000) {
                float r = con.getSendRate();
                buf.append(formatRate(r / 1024));
                bpsSend += r;
            } else {
                buf.append(formatRate(0));
            }
            //buf.append(" K/s");
            buf.append("</td><td class=\"cells\" align=\"right\">").append(DataHelper.formatDuration2(con.getUptime()));
            totalUptime += con.getUptime();
            offsetTotal = offsetTotal + con.getClockSkew();
            buf.append("</td><td class=\"cells\" align=\"right\">").append(DataHelper.formatDuration2(1000 * con.getClockSkew()));
            buf.append("</td><td class=\"cells\" align=\"right\">").append(con.getMessagesSent());
            totalSend += con.getMessagesSent();
            buf.append("</td><td class=\"cells\" align=\"right\">").append(con.getMessagesReceived());
            totalRecv += con.getMessagesReceived();
            long outQueue = con.getOutboundQueueSize();
            buf.append("</td><td class=\"cells\" align=\"center\">").append(outQueue);
            buf.append("</td><td class=\"cells\" align=\"center\">");
            if (con.isBacklogged())
                buf.append("&#x2713;");
            else
                buf.append("&nbsp;");
            //long readTime = con.getReadTime();
            //if (readTime <= 0) {
            //    buf.append("</td> <td class=\"cells\" align=\"center\">0");
            //} else {
            //    buf.append("</td> <td class=\"cells\" align=\"center\">").append(DataHelper.formatDuration(readTime));
            //}
            buf.append("</td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }

        if (!peers.isEmpty()) {
//            buf.append("<tr> <td colspan=\"11\"><hr></td></tr>\n");
            buf.append("<tr class=\"tablefooter\"><td colspan=\"4\" align=\"left\"><b>")
               .append(ngettext("{0} peer", "{0} peers", peers.size()));
            buf.append("</b></td><td align=\"center\"><b>").append(formatRate(bpsRecv/1024)).append(THINSP).append(formatRate(bpsSend/1024)).append("</b>");
            buf.append("</td><td align=\"center\"><b>").append(DataHelper.formatDuration2(totalUptime/peers.size()));
            buf.append("</b></td><td align=\"center\"><b>").append(DataHelper.formatDuration2(offsetTotal*1000/peers.size()));
            buf.append("</b></td><td align=\"center\"><b>").append(totalSend).append("</b></td><td align=\"center\"><b>").append(totalRecv);
            buf.append("</b></td><td>&nbsp;</td><td>&nbsp;</td></tr>\n");
        }

        buf.append("</table>\n");
        out.write(buf.toString());
        buf.setLength(0);
    }

    private static final NumberFormat _rateFmt = new DecimalFormat("#,##0.00");

    private static String formatRate(float rate) {
        synchronized (_rateFmt) { return _rateFmt.format(rate); }
    }

    private Comparator<NTCPConnection> getComparator(int sortFlags) {
        Comparator<NTCPConnection> rv = null;
        switch (Math.abs(sortFlags)) {
            default:
                rv = AlphaComparator.instance();
        }
        if (sortFlags < 0)
            rv = Collections.reverseOrder(rv);
        return rv;
    }

    private static class AlphaComparator extends PeerComparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() { return _instance; }
    }

    private static class PeerComparator implements Comparator<NTCPConnection>, Serializable {
        public int compare(NTCPConnection l, NTCPConnection r) {
            if (l == null || r == null)
                throw new IllegalArgumentException();
            // base64 retains binary ordering
            // UM, no it doesn't, but close enough
            return l.getRemotePeer().calculateHash().toBase64().compareTo(r.getRemotePeer().calculateHash().toBase64());
        }
    }

    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        @Override
        public Transport getTransport() { return NTCPTransport.this; }
        @Override
        public String toString() { return "NTCP bid @ " + getLatencyMs(); }
    }
}
