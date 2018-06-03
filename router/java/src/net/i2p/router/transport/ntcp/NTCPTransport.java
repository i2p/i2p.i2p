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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade.Status;
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
    private long _lastInboundIPv4;
    private long _lastInboundIPv6;

    // note: SSU version is i2np.udp.host, not hostname
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    public static final int DEFAULT_COST = 10;
    
    /** this is rarely if ever used, default is to bind to wildcard address */
    public static final String PROP_BIND_INTERFACE = "i2np.ntcp.bindInterface";

    private final NTCPSendFinisher _finisher;
    private final DHSessionKeyBuilder.Factory _dhFactory;
    private long _lastBadSkew;
    private static final long[] RATES = { 10*60*1000 };

    /**
     *  RI sigtypes supported in 0.9.16
     */
    public static final String MIN_SIGTYPE_VERSION = "0.9.16";

    // NTCP2 stuff
    public static final String STYLE = "NTCP";
    private static final String STYLE2 = "NTCP2";
    private static final String PROP_NTCP2_ENABLE = "i2np.ntcp2.enable";
    private static final boolean DEFAULT_NTCP2_ENABLE = false;
    private boolean _enableNTCP2;
    private static final String NTCP2_PROTO_SHORT = "NXK2CS";
    private static final String OPT_NTCP2_SK = 'N' + NTCP2_PROTO_SHORT + "2s";
    static final int NTCP2_INT_VERSION = 2;
    private static final String NTCP2_VERSION = Integer.toString(NTCP2_INT_VERSION);
    /** b64 static private key */
    private static final String PROP_NTCP2_SP = "i2np.ntcp2.sp";
    /** b64 static IV */
    private static final String PROP_NTCP2_IV = "i2np.ntcp2.iv";
    private static final int NTCP2_IV_LEN = 16;
    private static final int NTCP2_KEY_LEN = 32;
    private final byte[] _ntcp2StaticPrivkey;
    private final byte[] _ntcp2StaticIV;
    private final String _b64Ntcp2StaticPubkey;
    private final String _b64Ntcp2StaticIV;

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
        _context.statManager().createRateStat("ntcp.failsafeThrottle", "Delay event pumper", "ntcp", RATES);
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
        _context.statManager().createRateStat("ntcp.inboundIPv4Conn", "Inbound IPv4 NTCP Connection", "ntcp", RATES);
        _context.statManager().createRateStat("ntcp.inboundIPv6Conn", "Inbound IPv6 NTCP Connection", "ntcp", RATES);
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

        _enableNTCP2 = ctx.getProperty(PROP_NTCP2_ENABLE, DEFAULT_NTCP2_ENABLE);
        if (_enableNTCP2) {
            boolean shouldSave = false;
            byte[] priv = null;
            byte[] iv = null;
            String b64Pub = null;
            String b64IV = null;
            String s = ctx.getProperty(PROP_NTCP2_SP);
            if (s != null) {
                priv = Base64.decode(s);
            }
            if (priv == null || priv.length != NTCP2_KEY_LEN) {
                priv = new byte[NTCP2_KEY_LEN];
                ctx.random().nextBytes(priv);
                shouldSave = true;
            }
            s = ctx.getProperty(PROP_NTCP2_IV);
            if (s != null) {
                iv = Base64.decode(s);
                b64IV = s;
            }
            if (iv == null || iv.length != NTCP2_IV_LEN) {
                iv = new byte[NTCP2_IV_LEN];
                ctx.random().nextBytes(iv);
                shouldSave = true;
            }
            if (shouldSave) {
                Map<String, String> changes = new HashMap<String, String>(2);
                String b64Priv = Base64.encode(priv);
                b64IV = Base64.encode(iv);
                changes.put(PROP_NTCP2_SP, b64Priv);
                changes.put(PROP_NTCP2_IV, b64IV);
                ctx.router().saveConfig(changes, null);
            }
            _ntcp2StaticPrivkey = priv;
            _ntcp2StaticIV = iv;
            _b64Ntcp2StaticPubkey = "TODO"; // priv->pub
            _b64Ntcp2StaticIV = b64IV;
        } else {
            _ntcp2StaticPrivkey = null;
            _ntcp2StaticIV = null;
            _b64Ntcp2StaticPubkey = null;
            _b64Ntcp2StaticIV = null;
        }
    }

    /**
     * @param con that is established
     * @return the previous connection to the same peer, must be closed by caller, null if no such.
     */
    NTCPConnection inboundEstablished(NTCPConnection con) {
        _context.statManager().addRateData("ntcp.inboundEstablished", 1);
        Hash peer = con.getRemotePeer().calculateHash();
        markReachable(peer, true);
        //_context.banlist().unbanlistRouter(con.getRemotePeer().calculateHash());
        NTCPConnection old;
        synchronized (_conLock) {
            old = _conByIdent.put(peer, con);
        }
        if (con.isIPv6()) {
            _lastInboundIPv6 = con.getCreated();
            _context.statManager().addRateData("ntcp.inboundIPv6Conn", 1);
        } else {
            _lastInboundIPv4 = con.getCreated();
            _context.statManager().addRateData("ntcp.inboundIPv4Conn", 1);
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
                        int ver = getNTCPVersion(addr);
                        if (ver != 0) {
                            con = new NTCPConnection(_context, this, ident, addr, ver);
                            //if (_log.shouldLog(Log.DEBUG))
                            //    _log.debug("Send on a new con: " + con + " at " + addr + " for " + ih);
                            // Note that outbound conns go in the map BEFORE establishment
                            _conByIdent.put(ih, con);
                        } else {
                            fail = true;
                        }
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
                // doesn't do anything yet, just enqueues it
                con.send(msg);
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
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("fast bid when trying to send to " + peer + " as its already established");
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
                String v = toAddress.getVersion();
                if (VersionComparator.comp(v, MIN_SIGTYPE_VERSION) < 0) {
                    markUnreachable(peer);
                    return null;
                }
            }
        }

        if (!allowConnection()) {
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("no bid when trying to send to " + peer + ", max connection limit reached");
            return _transientFail;
        }

        //if ( (_myAddress != null) && (_myAddress.equals(addr)) )
        //    return null; // dont talk to yourself

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("slow bid when trying to send to " + peer);
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
                if (! allowLocal()) {
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

    /**
     * Tell the transport that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    @Override
    public void mayDisconnect(final Hash peer) {
        final NTCPConnection con = _conByIdent.get(peer);
        if (con != null && con.isEstablished() &&
            con.getMessagesReceived() <= 2 && con.getMessagesSent() <= 1) {
            con.setMayDisconnect();
        }
    }

    /**
     * @return usually the con passed in, but possibly a second connection with the same peer...
     */
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

    public int countPeers() {
            return _conByIdent.size();
    }
    
    /** 
     * For /peers UI only. Not a public API, not for external use.
     *
     * @return not a copy, do not modify
     * @since 0.9.31
     */
    public Collection<NTCPConnection> getPeers() {
        return _conByIdent.values();
    }
    
    /** 
     * Connected peers.
     *
     * @return a copy, modifiable
     * @since 0.9.34
     */
    public Set<Hash> getEstablished() {
        Set<Hash> rv = new HashSet<Hash>(_conByIdent.keySet());
        for (Map.Entry<Hash, NTCPConnection> e : _conByIdent.entrySet()) {
            NTCPConnection con = e.getValue();
            if (!con.isEstablished() || con.isClosed())
                rv.remove(e.getKey());
        }
        return rv;
    }

    /**
     * How many peers have we talked to in the last 5 minutes?
     * As of 0.9.20, actually returns active peer count, not total.
     */
    public int countActivePeers() {
        int active = 0;
        for (NTCPConnection con : _conByIdent.values()) {
            // con initializes times at construction,
            // so check message count also
            if ((con.getMessagesSent() > 0 && con.getTimeSinceSend() <= 5*60*1000) ||
                (con.getMessagesReceived() > 0 && con.getTimeSinceReceive() <= 5*60*1000)) {
                active++;
            }
        }
        return active;
    }

    /**
     * How many peers are we actively sending messages to (this minute)
     */
    public int countActiveSendPeers() {
        int active = 0;
        for (NTCPConnection con : _conByIdent.values()) {
            // con initializes times at construction,
            // so check message count also
            if (con.getMessagesSent() > 0 && con.getTimeSinceSend() <= 60*1000) {
                active++;
            }
        }
        return active;
    }

    /**
     *  A positive number means our clock is ahead of theirs.
     *
     *  @param skew in seconds
     */
    void setLastBadSkew(long skew) {
        _lastBadSkew = skew;
    }

    /**
     * Return our peer clock skews on this transport.
     * Vector composed of Long, each element representing a peer skew in seconds.
     * A positive number means our clock is ahead of theirs.
     */
    @Override
    public Vector<Long> getClockSkews() {
        Vector<Long> skews = new Vector<Long>();
        // Omit ones established too long ago,
        // since the skew is only set at startup (or after a meta message)
        // and won't include effects of later offset adjustments
        long tooOld = _context.clock().now() - 10*60*1000;

        for (NTCPConnection con : _conByIdent.values()) {
            if (con.isEstablished() && con.getCreated() > tooOld)
                skews.addElement(Long.valueOf(con.getClockSkew()));
        }

        // If we don't have many peers, maybe it is because of a bad clock, so
        // return the last bad skew we got
        if (skews.size() < 5 && _lastBadSkew != 0)
            skews.addElement(Long.valueOf(_lastBadSkew));

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("NTCP transport returning " + skews.size() + " peer clock skews.");
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
        if (_log.shouldLog(Log.WARN)) _log.warn("Starting NTCP transport listening");

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
                addNTCP2Options(props);
                int cost = getDefaultCost(ia instanceof Inet6Address);
                myAddress = new RouterAddress(STYLE, props, cost);
                replaceAddress(myAddress);
            }
        }
        // TransportManager.startListening() calls router.rebuildRouterInfo()
    }

    /**
     *  Only called by externalAddressReceived().
     *  Calls replaceAddress() or removeAddress().
     *  To remove all addresses, call replaceAddress(null) directly.
     *
     *  Doesn't actually restart unless addr is non-null and
     *  the port is different from the current listen port.
     *  If addr is null, removes the addresses specified (v4 or v6)
     *
     *  If we had interface addresses before, we lost them.
     *
     *  @param addr may be null to indicate remove the address
     *  @param ipv6 ignored if addr is non-null
     */
    private synchronized void restartListening(RouterAddress addr, boolean ipv6) {
        if (addr != null) {
            RouterAddress myAddress = bindAddress(addr.getPort());
            if (myAddress != null)
                replaceAddress(myAddress);
            else
                replaceAddress(addr);
            // UDPTransport.rebuildExternalAddress() calls router.rebuildRouterInfo()
        } else {
            removeAddress(ipv6);
            if (ipv6)
                _lastInboundIPv6 = 0;
            else
                _lastInboundIPv4 = 0;
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
                    addNTCP2Options(props);
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
     *  @return configured host (as an IP String) or null. Must be one of our local interfaces.
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
     * An alternate supported style, or null.
     * @return "NTCP2" or null
     * @since 0.9.35
     */
    @Override
    public String getAltStyle() {
        return _enableNTCP2 ? STYLE2 : null;
    }

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
     *  Note this is only called from startListening()
     *
     *  TODO return a list of one or more
     *  TODO only returns non-null if port is configured
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
     * Note this is only called from startListening() via configureLocalAddress()
     *
     * TODO return a list of one or more
     * TODO unlike in UDP rebuildExternalAddress(), this only runs once, at startup,
     * so we won't pick up IP changes.
     * TODO only returns non-null if port is configured
     *
     * @since IPv6 moved from CSFI
     */
    private RouterAddress createNTCPAddress() {
        int p = _context.getProperty(PROP_I2NP_NTCP_PORT, -1);
        if (p <= 0 || p >= 64*1024)
            return null;

        String name = getConfiguredIP();
        if (name == null)
            return null;

        OrderedProperties props = new OrderedProperties();
        props.setProperty(RouterAddress.PROP_HOST, name);
        props.setProperty(RouterAddress.PROP_PORT, Integer.toString(p));
        addNTCP2Options(props);
        int cost = getDefaultCost(false);
        RouterAddress addr = new RouterAddress(STYLE, props, cost);
        return addr;
    }

    /**
     * Add the required options to the properties for a NTCP2 address
     *
     * @since 0.9.35
     */
    private void addNTCP2Options(Properties props) {
        if (!_enableNTCP2)
            return;
        props.setProperty("i", _b64Ntcp2StaticIV);
        props.setProperty("n", NTCP2_PROTO_SHORT);
        props.setProperty("s", _b64Ntcp2StaticPubkey);
        props.setProperty("v", NTCP2_VERSION);
    }

    /**
     * Is NTCP2 enabled?
     *
     * @since 0.9.35
     */
    boolean isNTCP2Enabled() { return _enableNTCP2; }

    /**
     * The static priv key
     *
     * @since 0.9.35
     */
    byte[] getNTCP2StaticPrivkey() {
        return _ntcp2StaticPrivkey;
    }

    /**
     * Get the valid NTCP version of this NTCP address.
     *
     * @return the valid version 1 or 2, or 0 if unusable
     * @since 0.9.35
     */
    private int getNTCPVersion(RouterAddress addr) {
        int rv;
        String style = addr.getTransportStyle();
        if (style.equals(STYLE)) {
            if (!_enableNTCP2)
                return 1;
            rv = 1;
        } else if (style.equals(STYLE2)) {
            if (!_enableNTCP2)
                return 0;
            rv = 2;
        } else {
            return 0;
        }
        if (addr.getOption("s") == null ||
            addr.getOption("i") == null ||
            !NTCP2_VERSION.equals(addr.getOption("v")) ||
            !NTCP2_PROTO_SHORT.equals(addr.getOption("n"))) {
            return (rv == 1) ? 1 : 0;
        }
        // todo validate s/i b64, or just catch it later?
        return rv;
    }

    /**
     * Return a single configured IP (as a String) or null if not configured or invalid.
     * Resolves a hostname to an IP.
     * Called at startup via createNTCPAddress() and later via externalAddressReceived()
     *
     * TODO return a list of one or more
     *
     * @since 0.9.32
     */
    private String getConfiguredIP() {
        // Fixme doesn't check PROP_BIND_INTERFACE
        String name = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        if ( (name == null) || (name.trim().length() <= 0) || ("null".equals(name)) )
            return null;
        String[] hosts = DataHelper.split(name, "[,; \r\n\t]");
        List<String> ipstrings = new ArrayList<String>(2);
        // we only take one each of v4 and v6
        boolean v4 = false;
        boolean v6 = false;
        // prevent adding a type if disabled
        TransportUtil.IPv6Config cfg = getIPv6Config();
        if (cfg == IPV6_DISABLED)
            v6 = true;
        else if (cfg == IPV6_ONLY)
            v4 = true;
        for (int i = 0; i < hosts.length; i++) {
            String h = hosts[i];
            if (h.length() <= 0)
                continue;
            if (Addresses.isIPv4Address(h)) {
                if (v4)
                    continue;
                v4 = true;
                ipstrings.add(h);
            } else if (Addresses.isIPv6Address(h)) {
                if (v6)
                    continue;
                v6 = true;
                ipstrings.add(h);
            } else {
                int valid = 0;
                List<byte[]> ips = Addresses.getIPs(h);
                if (ips != null) {
                    for (byte[] ip : ips) {
                        if (!isValid(ip)) {
                            if (_log.shouldWarn())
                                _log.warn("skipping invalid " + Addresses.toString(ip) + " for " + h);
                            continue;
                        }
                        if ((v4 && ip.length == 4) || (v6 && ip.length == 16)) {
                            if (_log.shouldWarn())
                                _log.warn("skipping additional " + Addresses.toString(ip) + " for " + h);
                            continue;
                        }
                        if (ip.length == 4)
                            v4 = true;
                        else if (ip.length == 16)
                            v6 = true;
                        valid++;
                        if (_log.shouldDebug())
                            _log.debug("adding " + Addresses.toString(ip) + " for " + h);
                        ipstrings.add(Addresses.toString(ip));
                    }
                }
                if (valid == 0)
                    _log.error("No valid IPs for configured hostname " + h);
                continue;
            }
        }

        if (ipstrings.isEmpty()) {
            _log.error("No valid IPs for configuration: " + name);
            return null;
        }

        // get first IPv4, if none then first IPv6
        // TODO return both
        String ip = null;
        for (String ips : ipstrings) {
            if (ips.contains(".")) {
                ip = ips;
                break;
            }
        }
        if (ip == null)
            ip = ipstrings.get(0);
        return ip;
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
     *  @param ip typ. IPv4 or IPv6 non-local; may be null to indicate IPv4 failure or port info only
     *  @since IPv6 moved from CSFI.notifyReplaceAddress()
     */
    @Override
    public void externalAddressReceived(AddressSource source, byte[] ip, int port) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Received address: " + Addresses.toString(ip, port) + " from: " + source, new Exception());
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
        boolean isIPv6 = ip != null && ip.length == 16;
        externalAddressReceived(ip, isIPv6, port);
    }

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called after the transport is running.
     *
     *  TODO externalAddressRemoved(source, ip, port)
     *
     *  @param source defined in Transport.java
     *  @since 0.9.20
     */
    @Override
    public void externalAddressRemoved(AddressSource source, boolean ipv6) {
        if (_log.shouldWarn())
            _log.warn("Removing address, ipv6? " + ipv6 + " from: " + source, new Exception());
        // ignore UPnP for now, get everything from SSU
        if (source != SOURCE_SSU)
            return;
        externalAddressReceived(null, ipv6, 0);
    }    
    
    /**
     *  UDP changed addresses, tell NTCP and restart.
     *  Port may be set to indicate requested port even if ip is null.
     *
     *  @param ip previously validated; may be null to indicate IPv4 failure or port info only
     *  @since IPv6 moved from CSFI.notifyReplaceAddress()
     */
    private synchronized void externalAddressReceived(byte[] ip, boolean isIPv6, int port) {
        // FIXME just take first address for now
        // FIXME if SSU set to hostname, NTCP will be set to IP
        RouterAddress oldAddr = getCurrentAddress(isIPv6);
        if (_log.shouldLog(Log.INFO))
            _log.info("Changing NTCP Address? was " + oldAddr);

        OrderedProperties newProps = new OrderedProperties();
        int cost;
        if (oldAddr == null) {
            cost = getDefaultCost(isIPv6);
            addNTCP2Options(newProps);
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
        String name = getConfiguredIP();
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
        restartListening(newAddr, isIPv6);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Updating NTCP Address (ipv6? " + isIPv6 + ") with " + newAddr);
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
     *
     * This only returns OK, DISABLED, or UNKNOWN for IPv4 and IPv6.
     * We leave the FIREWALLED status for UDP.
     *
     * Previously returned short, now enum as of 0.9.20
     */
    public Status getReachabilityStatus() { 
        if (!isAlive())
            return Status.UNKNOWN;
        TransportUtil.IPv6Config config = getIPv6Config();
        boolean v4Disabled, v6Disabled;
        if (config == IPV6_DISABLED) {
            v4Disabled = false;
            v6Disabled = true;
        } else if (config == IPV6_ONLY) {
            v4Disabled = true;
            v6Disabled = false;
        } else {
            v4Disabled = false;
            v6Disabled = false;
        }
        boolean hasV4 = getCurrentAddress(false) != null;
        // or use _haveIPv6Addrnss ??
        boolean hasV6 = getCurrentAddress(true) != null;
        if (!hasV4 && !hasV6)
            return Status.UNKNOWN;
        long now = _context.clock().now();
        boolean v4OK = hasV4 && !v4Disabled && now - _lastInboundIPv4 < 10*60*1000;
        boolean v6OK = hasV6 && !v6Disabled && now - _lastInboundIPv6 < 30*60*1000;
        if (v4OK) {
            if (v6OK)
                return Status.OK;
            if (v6Disabled)
                return Status.OK;
            if (!hasV6)
                return Status.IPV4_OK_IPV6_UNKNOWN;
        }
        if (v6OK) {
            if (v4Disabled)
                return Status.IPV4_DISABLED_IPV6_OK;
            if (!hasV4)
                return Status.IPV4_UNKNOWN_IPV6_OK;
        }
        for (NTCPConnection con : _conByIdent.values()) {
            if (con.isInbound()) {
                if (con.isIPv6()) {
                    if (hasV6)
                        v6OK = true;
                } else {
                    if (hasV4)
                        v4OK = true;
                }
                if (v4OK) {
                    if (v6OK)
                        return Status.OK;
                    if (v6Disabled)
                        return Status.OK;
                    if (!hasV6)
                        return Status.IPV4_OK_IPV6_UNKNOWN;
                }
                if (v6OK) {
                    if (v4Disabled)
                        return Status.IPV4_DISABLED_IPV6_OK;
                    if (!hasV4)
                        return Status.IPV4_UNKNOWN_IPV6_OK;
                }
            }
        }
        if (v4OK)
            return Status.IPV4_OK_IPV6_UNKNOWN;
        if (v6OK)
            return Status.IPV4_UNKNOWN_IPV6_OK;
        if (v4Disabled)
            return Status.IPV4_DISABLED_IPV6_UNKNOWN;
        if (v6Disabled)
            return Status.UNKNOWN;
        return Status.UNKNOWN;
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
        _lastInboundIPv4 = 0;
        _lastInboundIPv6 = 0;
    }

    public void renderStatusHTML(java.io.Writer out, int sortFlags) throws IOException {}

    /**
     * Does nothing
     * @deprecated as of 0.9.31
     */
    @Override
    @Deprecated
    public void renderStatusHTML(java.io.Writer out, String urlBase, int sortFlags) throws IOException {
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
