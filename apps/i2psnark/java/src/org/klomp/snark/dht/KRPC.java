package org.klomp.snark.dht;

/*
 *  GPLv2
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;


/**
 * Standard BEP 5
 * Mods for I2P:
 * <pre>
 * - The UDP port need not be pinged after receiving a PORT message.
 *
 * - The UDP (datagram) port listed in the compact node info is used
 *   to receive repliable (signed) datagrams.
 *   This is used for queries, except for announces.
 *   We call this the "query port".
 *   In addition to that UDP port, we use a second datagram
 *   port equal to the signed port + 1. This is used to receive
 *   unsigned (raw) datagrams for replies, errors, and announce queries..
 *   We call this the "response port".
 *
 * - Compact peer info is 32 bytes (32 byte SHA256 Hash)
 *   instead of 4 byte IP + 2 byte port. There is no peer port.
 *
 * - Compact node info is 54 bytes (20 byte SHA1 Hash + 32 byte SHA256 Hash + 2 byte port)
 *   instead of 20 byte SHA1 Hash + 4 byte IP + 2 byte port.
 *   Port is the query port, the response port is always the query port + 1.
 *
 * - The trackerless torrent dictionary "nodes" key is a list of
 *   32 byte binary strings (SHA256 Hashes) instead of a list of lists
 *   containing a host string and a port integer.
 * </pre>
 *
 * Questions:
 *   - nodes (in the find_node and get_peers response) is one concatenated string, not a list of strings, right?
 *   - Node ID enforcement, keyspace rotation?
 *
 * @since 0.8.4
 * @author zzz
 */
public class KRPC implements I2PSessionMuxedListener, DHT {

    private final I2PAppContext _context;
    private final Log _log;

    /** our tracker */
    private final DHTTracker _tracker;
    /** who we know */
    private final DHTNodes _knownNodes;
    /** index to sent queries awaiting reply */
    private final ConcurrentHashMap<MsgID, ReplyWaiter> _sentQueries;
    /** index to outgoing tokens we generated, sent in reply to a get_peers query */
    private final ConcurrentHashMap<Token, NodeInfo> _outgoingTokens;
    /** index to incoming opaque tokens, received in a peers or nodes reply */
    private final ConcurrentHashMap<NID, Token> _incomingTokens;

    /** hook to inject and receive datagrams */
    private final I2PSession _session;
    /** 20 byte random id */
    private final byte[] _myID;
    /** 20 byte random id */
    private final NID _myNID;
    /** 20 byte random id + 32 byte Hash + 2 byte port */
    private final NodeInfo _myNodeInfo;
    /** unsigned dgrams */
    private final int _rPort;
    /** signed dgrams */
    private final int _qPort;
    private final File _dhtFile;
    private volatile boolean _isRunning;
    private volatile boolean _hasBootstrapped;

    /** all-zero NID used for pings */
    public static final NID FAKE_NID = new NID(new byte[NID.HASH_LENGTH]);

    /** Max number of nodes to return. BEP 5 says 8 */
    private static final int K = 8;
    /** Max number of peers to return. BEP 5 doesn't say. We'll use the same as I2PSnarkUtil.MAX_CONNECTIONS */
    private static final int MAX_WANT = 16;

    /** overloads error codes which start with 201 */
    private static final int REPLY_NONE = 0;
    private static final int REPLY_PONG = 1;
    private static final int REPLY_PEERS = 2;
    private static final int REPLY_NODES = 3;

    public static final boolean SECURE_NID = true;

    /** how long since last heard from do we delete  - BEP 5 says 15 minutes */
    private static final long MAX_NODEINFO_AGE = 60*60*1000;
    /** how long since generated do we delete - BEP 5 says 10 minutes */
    private static final long MAX_TOKEN_AGE = 60*60*1000;
    private static final long MAX_INBOUND_TOKEN_AGE = MAX_TOKEN_AGE - 5*60*1000;
    /** how long since sent do we wait for a reply */
    private static final long MAX_MSGID_AGE = 2*60*1000;
    /** how long since sent do we wait for a reply */
    private static final long DEFAULT_QUERY_TIMEOUT = 75*1000;
    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 63*1000;
    private static final long EXPLORE_TIME = 877*1000;
    private static final String DHT_FILE = "i2psnark.dht.dat";

    public KRPC (I2PAppContext ctx, I2PSession session) {
        _context = ctx;
        _session = session;
        _log = ctx.logManager().getLog(KRPC.class);
        _tracker = new DHTTracker(ctx);

        _sentQueries = new ConcurrentHashMap();
        _outgoingTokens = new ConcurrentHashMap();
        _incomingTokens = new ConcurrentHashMap();

        // Construct my NodeInfo
        // Pick ports over a big range to marginally increase security
        // If we add a search DHT, adjust to stay out of each other's way
        _qPort = 2555 + ctx.random().nextInt(61111);
        _rPort = _qPort + 1;
        if (SECURE_NID) {
            _myNID = NodeInfo.generateNID(session.getMyDestination().calculateHash(), _qPort, _context.random());
            _myID = _myNID.getData();
        } else {
            _myID = new byte[NID.HASH_LENGTH];
            ctx.random().nextBytes(_myID);
            _myNID = new NID(_myID);
        }
        _myNodeInfo = new NodeInfo(_myNID, session.getMyDestination(), _qPort);
        _dhtFile = new File(ctx.getConfigDir(), DHT_FILE);
        _knownNodes = new DHTNodes(ctx, _myNID);

        session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM, _qPort);
        start();
    }

    ///////////////// Public methods

    /**
     * Known nodes, not estimated total network size.
     */
    public int size() {
        return _knownNodes.size();
    }

    /**
     *  @return The UDP query port
     */
    public int getPort() {
        return _qPort;
    }

    /**
     *  @return The UDP response port
     */
    public int getRPort() {
        return _rPort;
    }

    /**
     *  Ping. We don't have a NID yet so the node is presumed
     *  to be absent from our DHT.
     *  Non-blocking, does not wait for pong.
     *  If and when the pong is received the node will be inserted in our DHT.
     */
    public void ping(Destination dest, int port) {
        NodeInfo nInfo = new NodeInfo(dest, port);
        sendPing(nInfo);
    }

    /**
     *  Bootstrapping or background thread.
     *  Blocking!
     *  This is almost the same as getPeers()
     *
     *  @param target the key we are searching for
     *  @param maxNodes how many to contact
     *  @param maxWait how long to wait for each to reply (not total) must be > 0
     *  @param parallel how many outstanding at once (unimplemented, always 1)
     */
    private void explore(NID target, int maxNodes, long maxWait, int parallel) {
        List<NodeInfo> nodes = _knownNodes.findClosest(target, maxNodes);
        if (nodes.isEmpty()) {
            if (_log.shouldLog(Log.WARN))
                _log.info("DHT is empty, cannot explore");
            return;
        }
        SortedSet<NodeInfo> toTry = new TreeSet(new NodeInfoComparator(target));
        toTry.addAll(nodes);
        Set<NodeInfo> tried = new HashSet();

        if (_log.shouldLog(Log.INFO))
            _log.info("Starting explore of " + target);
        for (int i = 0; i < maxNodes; i++) {
            if (!_isRunning)
                break;
            NodeInfo nInfo;
            try {
                nInfo = toTry.first();
            } catch (NoSuchElementException nsee) {
                break;
            }
            toTry.remove(nInfo);
            tried.add(nInfo);

            ReplyWaiter waiter = sendFindNode(nInfo, target);
            if (waiter == null)
                continue;
            synchronized(waiter) {
                try {
                    waiter.wait(maxWait);
                } catch (InterruptedException ie) {}
            }

            int replyType = waiter.getReplyCode();
            if (replyType == REPLY_NONE) {
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got no reply");
            } else if (replyType == REPLY_NODES) {
                 List<NodeInfo> reply = (List<NodeInfo>) waiter.getReplyObject();
                 // It seems like we are just going to get back ourselves all the time
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got " + reply.size() + " nodes");
                 for (NodeInfo ni : reply) {
                     if (! (ni.equals(_myNodeInfo) || (toTry.contains(ni) && tried.contains(ni))))
                         toTry.add(ni);
                 }
            } else {
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got unexpected reply " + replyType + ": " + waiter.getReplyObject());
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Finished explore of " + target);
    }

    /**
     *  Local lookup only
     *  @param ih a 20-byte info hash
     *  @param max max to return
     *  @return list or empty list (never null)
     */
    public List<NodeInfo> findClosest(byte[] ih, int max) {
        List<NodeInfo> nodes = _knownNodes.findClosest(new InfoHash(ih), max);
        return nodes;
    }

    /**
     *  Get peers for a torrent.
     *  This is an iterative lookup in the DHT.
     *  Blocking!
     *  Caller should run in a thread.
     *
     *  @param ih the Info Hash (torrent)
     *  @param max maximum number of peers to return
     *  @param maxWait the maximum time to wait (ms) must be > 0
     *  @return list or empty list (never null)
     */
    public List<Hash> getPeers(byte[] ih, int max, long maxWait) {
        // check local tracker first
        InfoHash iHash = new InfoHash(ih);
        List<Hash> rv = _tracker.getPeers(iHash, max);
        rv.remove(_myNodeInfo.getHash());
        if (!rv.isEmpty())
            return rv;  // TODO get DHT too?

        // Initial set to try, will get added to as we go
        List<NodeInfo> nodes = _knownNodes.findClosest(iHash, max);
        SortedSet<NodeInfo> toTry = new TreeSet(new NodeInfoComparator(iHash));
        toTry.addAll(nodes);
        Set<NodeInfo> tried = new HashSet();

        if (_log.shouldLog(Log.INFO))
            _log.info("Starting getPeers with " + nodes.size() + " to try");
        for (int i = 0; i < max; i++) {
            if (!_isRunning)
                break;
            NodeInfo nInfo;
            try {
                nInfo = toTry.first();
            } catch (NoSuchElementException nsee) {
                break;
            }
            toTry.remove(nInfo);
            tried.add(nInfo);

            ReplyWaiter waiter = sendGetPeers(nInfo, iHash);
            if (waiter == null)
                continue;
            synchronized(waiter) {
                try {
                    waiter.wait(maxWait);
                } catch (InterruptedException ie) {}
            }

            int replyType = waiter.getReplyCode();
            if (replyType == REPLY_NONE) {
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got no reply");
            } else if (replyType == REPLY_PONG) {
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got pong");
            } else if (replyType == REPLY_PEERS) {
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got peers");
                 List<Hash> reply = (List<Hash>) waiter.getReplyObject();
                 if (!reply.isEmpty()) {
                     if (_log.shouldLog(Log.INFO))
                         _log.info("Finished get Peers, returning " + reply.size());
                     return reply;
                 }
            } else if (replyType == REPLY_NODES) {
                 List<NodeInfo> reply = (List<NodeInfo>) waiter.getReplyObject();
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got " + reply.size() + " nodes");
                 for (NodeInfo ni : reply) {
                     if (! (ni.equals(_myNodeInfo) || tried.contains(ni) || toTry.contains(ni)))
                         toTry.add(ni);
                 }
            } else {
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Got unexpected reply " + replyType + ": " + waiter.getReplyObject());
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Finished get Peers, fail");
        return Collections.EMPTY_LIST;
    }

    /**
     *  Announce to ourselves.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     */
    public void announce(byte[] ih) {
        InfoHash iHash = new InfoHash(ih);
        _tracker.announce(iHash, _myNodeInfo.getHash());
    }

    /**
     *  Announce somebody else we know about.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     *  @param peerHash the peer's Hash
     */
    public void announce(byte[] ih, byte[] peerHash) {
        InfoHash iHash = new InfoHash(ih);
        _tracker.announce(iHash, new Hash(peerHash));
        // Do NOT do this, corrupts the Hash cache and the Peer ID
        //_tracker.announce(iHash, Hash.create(peerHash));
    }

    /**
     *  Remove reference to ourselves in the local tracker.
     *  Use when shutting down the torrent locally.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     */
    public void unannounce(byte[] ih) {
        InfoHash iHash = new InfoHash(ih);
        _tracker.unannounce(iHash, _myNodeInfo.getHash());
    }

    /**
     *  Announce to the closest peers in the local DHT.
     *  This is NOT iterative - call getPeers() first to get the closest
     *  peers into the local DHT.
     *  Blocking unless maxWait <= 0
     *  Caller should run in a thread.
     *  This also automatically announces ourself to our local tracker.
     *  For best results do a getPeers() first so we have tokens.
     *
     *  @param ih the Info Hash (torrent)
     *  @param max maximum number of peers to announce to
     *  @param maxWait the maximum total time to wait (ms) or 0 to do all in parallel and return immediately.
     *  @return the number of successful announces, not counting ourselves.
     */
    public int announce(byte[] ih, int max, long maxWait) {
        announce(ih);
        int rv = 0;
        long start = _context.clock().now();
        InfoHash iHash = new InfoHash(ih);
        List<NodeInfo> nodes = _knownNodes.findClosest(iHash, max);
        if (_log.shouldLog(Log.INFO))
            _log.info("Found " + nodes.size() + " to announce to for " + iHash);
        for (NodeInfo nInfo : nodes) {
            if (!_isRunning)
                break;
            if (announce(ih, nInfo, Math.min(maxWait, 60*1000)))
                rv++;
            maxWait -= _context.clock().now() - start;
            if (maxWait < 1000)
                break;
        }
        return rv;
    }

    /**
     *  Announce to a single DHT peer.
     *  Blocking unless maxWait <= 0
     *  Caller should run in a thread.
     *  For best results do a getPeers() first so we have a token.
     *
     *  @param ih the Info Hash (torrent)
     *  @param nInfo the peer to announce to
     *  @param maxWait the maximum time to wait (ms) or 0 to return immediately.
     *  @return success
     */
    private boolean announce(byte[] ih, NodeInfo nInfo, long maxWait) {
        InfoHash iHash = new InfoHash(ih);
        // it isn't clear from BEP 5 if a token is bound to a single infohash?
        // for now, just bind to the NID
        //TokenKey tokenKey = new TokenKey(nInfo.getNID(), iHash);
        Token token = _incomingTokens.get(nInfo.getNID());
        if (token == null) {
            // we have no token, have to do a getPeers first to get a token
            if (maxWait <= 0)
                return false;
            if (_log.shouldLog(Log.INFO))
                _log.info("No token for announce to " + nInfo + " sending get_peers first");
            ReplyWaiter waiter = sendGetPeers(nInfo, iHash);
            if (waiter == null)
                return false;
            long start = _context.clock().now();
            synchronized(waiter) {
                try {
                    waiter.wait(maxWait);
                } catch (InterruptedException ie) {}
            }
            int replyType = waiter.getReplyCode();
            if (!(replyType == REPLY_PEERS || replyType == REPLY_NODES)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Get_peers failed to " + nInfo);
                return false;
            }
            // we should have a token now
            token = _incomingTokens.get(nInfo.getNID());
            if (token == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Huh? no token after get_peers succeeded to " + nInfo);
                return false;
            }
            maxWait -= _context.clock().now() - start;
            if (maxWait < 1000) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Ran out of time after get_peers succeeded to " + nInfo);
                return false;
            }
        }

        // send and wait on rcv msg lock unless maxWait <= 0
        ReplyWaiter waiter = sendAnnouncePeer(nInfo, iHash, token);
        if (waiter == null)
            return false;
        if (maxWait <= 0)
            return true;
        synchronized(waiter) {
            try {
                waiter.wait(maxWait);
            } catch (InterruptedException ie) {}
        }
        int replyType = waiter.getReplyCode();
        return replyType == REPLY_PONG;
    }

    /**
     *  Loads the DHT from file.
     *  Can't be restarted after stopping?
     */
    public void start() {
        _knownNodes.start();
        _tracker.start();
        PersistDHT.loadDHT(this, _dhtFile);
        // start the explore thread
        _isRunning = true;
        // no need to keep ref, it will eventually stop
        new Cleaner();
        new Explorer(5*1000);
    }

    /**
     *  Stop everything.
     */
    public void stop() {
        _isRunning = false;
        // FIXME stop the explore thread
        // unregister port listeners
        _session.removeListener(I2PSession.PROTO_DATAGRAM, _qPort);
        _session.removeListener(I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        // clear the DHT and tracker
        _tracker.stop();
        PersistDHT.saveDHT(_knownNodes, _dhtFile);
        _knownNodes.stop();
        _sentQueries.clear();
        _outgoingTokens.clear();
        _incomingTokens.clear();
    }

    /**
     * Clears the tracker and DHT data.
     * Call after saving DHT data to disk.
     */
    public void clear() {
        _tracker.stop();
        _knownNodes.clear();
    }

    ////////// All private below here /////////////////////////////////////

    ///// Sending.....

    // Queries.....
    // The first 3 queries use the query port.
    // Announces use the response port.

    /**
     *  @param nInfo who to send it to
     *  @return null on error
     */
    private ReplyWaiter sendPing(NodeInfo nInfo) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending ping to: " + nInfo);
        Map<String, Object> map = new HashMap();
        map.put("q", "ping");
        Map<String, Object> args = new HashMap();
        map.put("a", args);
        return sendQuery(nInfo, map, true);
    }

    /**
     *  @param nInfo who to send it to
     *  @param tID target ID we are looking for
     *  @return null on error
     */
    private ReplyWaiter sendFindNode(NodeInfo nInfo, NID tID) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending find node of " + tID + " to: " + nInfo);
        Map<String, Object> map = new HashMap();
        map.put("q", "find_node");
        Map<String, Object> args = new HashMap();
        args.put("target", tID.getData());
        map.put("a", args);
        return sendQuery(nInfo, map, true);
    }

    /**
     *  @param nInfo who to send it to
     *  @return null on error
     */
    private ReplyWaiter sendGetPeers(NodeInfo nInfo, InfoHash ih) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending get peers of " + ih + " to: " + nInfo);
        Map<String, Object> map = new HashMap();
        map.put("q", "get_peers");
        Map<String, Object> args = new HashMap();
        args.put("info_hash", ih.getData());
        map.put("a", args);
        ReplyWaiter rv = sendQuery(nInfo, map, true);
        // save the InfoHash so we can get it later
        if (rv != null)
            rv.setSentObject(ih);
        return rv;
    }

    /**
     *  @param nInfo who to send it to
     *  @return null on error
     */
    private ReplyWaiter sendAnnouncePeer(NodeInfo nInfo, InfoHash ih, Token token) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending announce of " + ih + " to: " + nInfo);
        Map<String, Object> map = new HashMap();
        map.put("q", "announce_peer");
        Map<String, Object> args = new HashMap();
        args.put("info_hash", ih.getData());
        // port ignored
        args.put("port", Integer.valueOf(6881));
        args.put("token", token.getData());
        map.put("a", args);
        // an announce need not be signed, we have a token
        ReplyWaiter rv = sendQuery(nInfo, map, false);
        return rv;
    }

    // Responses.....
    // All responses use the response port.

    /**
     *  @param nInfo who to send it to
     *  @return success
     */
    private boolean sendPong(NodeInfo nInfo, MsgID msgID) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending pong to: " + nInfo);
        Map<String, Object> map = new HashMap();
        Map<String, Object> resps = new HashMap();
        map.put("r", resps);
        return sendResponse(nInfo, msgID, map);
    }

    /** response to find_node (no token) */
    private boolean sendNodes(NodeInfo nInfo, MsgID msgID, byte[] ids) {
        return sendNodes(nInfo, msgID, null, ids);
    }

    /**
     *  response to find_node (token is null) or get_peers (has a token)
     *  @param nInfo who to send it to
     *  @return success
     */
    private boolean sendNodes(NodeInfo nInfo, MsgID msgID, Token token, byte[] ids) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending nodes to: " + nInfo);
        Map<String, Object> map = new HashMap();
        Map<String, Object> resps = new HashMap();
        map.put("r", resps);
        if (token != null)
            resps.put("token", token.getData());
        resps.put("nodes", ids);
        return sendResponse(nInfo, msgID, map);
    }

    /** @param token non-null */
    private boolean sendPeers(NodeInfo nInfo, MsgID msgID, Token token, List<byte[]> peers) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending peers to: " + nInfo);
        Map<String, Object> map = new HashMap();
        Map<String, Object> resps = new HashMap();
        map.put("r", resps);
        resps.put("token", token.getData());
        resps.put("values", peers);
        return sendResponse(nInfo, msgID, map);
    }

    // All errors use the response port.

    /**
     *  @param nInfo who to send it to
     *  @return success
     */
    private boolean sendError(NodeInfo nInfo, MsgID msgID, int err, String msg) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending error " + msg + " to: " + nInfo);
        Map<String, Object> map = new HashMap();
        Map<String, Object> resps = new HashMap();
        map.put("r", resps);
        return sendResponse(nInfo, msgID, map);
    }

    // Low-level send methods

    // TODO sendQuery with onReply / onTimeout args

    /**
     *  Blocking if repliable and we must lookup b32
     *  @param repliable true for all but announce
     *  @return null on error
     */
    private ReplyWaiter sendQuery(NodeInfo nInfo, Map<String, Object> map, boolean repliable) {
        if (nInfo.equals(_myNodeInfo))
            throw new IllegalArgumentException("wtf don't send to ourselves");
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending query to: " + nInfo);
        if (nInfo.getDestination() == null) {
            NodeInfo newInfo = _knownNodes.get(nInfo.getNID());
            if (newInfo != null && newInfo.getDestination() != null) {
                nInfo = newInfo;
            } else if (!repliable) {
                // Don't lookup for announce query, we should already have it
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping non-repliable query, no dest for " + nInfo);
                return null;
            } else {
                // Lookup the dest for the hash
                // TODO spin off into thread or queue? We really don't want to block here
                if (!lookupDest(nInfo)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping repliable query, no dest for " + nInfo);
                    timeout(nInfo);
                    return null;
                }
            }
        }
        map.put("y", "q");
        MsgID mID = new MsgID(_context);
        map.put("t", mID.getData());
        Map<String, Object> args = (Map<String, Object>) map.get("a");
        if (args == null)
            throw new IllegalArgumentException("no args");
        args.put("id", _myID);
        int port = nInfo.getPort();
        if (!repliable)
            port++;
        boolean success = sendMessage(nInfo.getDestination(), port, map, repliable);
        if (success) {
            // save for the caller to get
            ReplyWaiter rv = new ReplyWaiter(mID, nInfo, null, null);
            _sentQueries.put(mID, rv);
            return rv;
        }
        return null;
    }

    /**
     * @param toPort the query port, we will increment here
     *  @return success
     */
    private boolean sendResponse(NodeInfo nInfo, MsgID msgID, Map<String, Object> map) {
        if (nInfo.equals(_myNodeInfo))
            throw new IllegalArgumentException("wtf don't send to ourselves");
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending response to: " + nInfo);
        if (nInfo.getDestination() == null) {
            NodeInfo newInfo = _knownNodes.get(nInfo.getNID());
            if (newInfo != null && newInfo.getDestination() != null) {
                nInfo = newInfo;
            } else {
                // lookup b32?
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping response, no dest for " + nInfo);
                return false;
            }
        }
        map.put("y", "r");
        map.put("t", msgID.getData());
        Map<String, Object> resps = (Map<String, Object>) map.get("r");
        if (resps == null)
            throw new IllegalArgumentException("no resps");
        resps.put("id", _myID);
        return sendMessage(nInfo.getDestination(), nInfo.getPort() + 1, map, false);
    }

    /**
     *  @param toPort the query port, we will increment here
     *  @return success
     */
    private boolean sendError(NodeInfo nInfo, MsgID msgID, Map<String, Object> map) {
        if (nInfo.equals(_myNodeInfo))
            throw new IllegalArgumentException("wtf don't send to ourselves");
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending error to: " + nInfo);
        if (nInfo.getDestination() == null) {
            NodeInfo newInfo = _knownNodes.get(nInfo.getNID());
            if (newInfo != null && newInfo.getDestination() != null) {
                nInfo = newInfo;
            } else {
                // lookup b32?
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping sendError, no dest for " + nInfo);
                return false;
            }
        }
        map.put("y", "e");
        map.put("t", msgID.getData());
        return sendMessage(nInfo.getDestination(), nInfo.getPort() + 1, map, false);
    }

    /**
     *  Get the dest for a NodeInfo lacking it, and store it there.
     *  Blocking.
     *  @return success
     */
    private boolean lookupDest(NodeInfo nInfo) {
        if (_log.shouldLog(Log.INFO))
            _log.info("looking up dest for " + nInfo);
        try {
            // use a short timeout for now
            Destination dest = _session.lookupDest(nInfo.getHash(), 5*1000);
            if (dest != null) {
                nInfo.setDestination(dest);
                if (_log.shouldLog(Log.INFO))
                    _log.info("lookup success for " + nInfo);
                return true;
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("lookup fail", ise);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("lookup fail for " + nInfo);
        return false;
    }

    /**
     *  Lowest-level send message call.
     *  @param repliable true for all but announce
     *  @return success
     */
    private boolean sendMessage(Destination dest, int toPort, Map<String, Object> map, boolean repliable) {
        if (_session.isClosed()) {
            // Don't allow DHT to open a closed session
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not sending message, session is closed");
            return false;
        }
        if (dest.calculateHash().equals(_myNodeInfo.getHash()))
            throw new IllegalArgumentException("wtf don't send to ourselves");
        byte[] payload = BEncoder.bencode(map);
        if (_log.shouldLog(Log.DEBUG)) {
            ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            try {
                _log.debug("Sending to: " + dest.calculateHash() + ' ' + BDecoder.bdecode(bais).toString());
            } catch (IOException ioe) {}
        }

        // Always send query port, peer will increment for unsigned replies
        int fromPort = _qPort;
        if (repliable) {
            I2PDatagramMaker dgMaker = new I2PDatagramMaker(_session);
            payload = dgMaker.makeI2PDatagram(payload);
            if (payload == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("WTF DGM fail");
            }
        }

        try {
            // TODO I2CP per-packet options
            boolean success = _session.sendMessage(dest, payload, 0, payload.length, null, null, 60*1000,
                                                   repliable ? I2PSession.PROTO_DATAGRAM : I2PSession.PROTO_DATAGRAM_RAW,
                                                   fromPort, toPort);
            if (!success) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("WTF sendMessage fail");
            }
            return success;
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("sendMessage fail", ise);
            return false;
        }
    }

    ///// Reception.....

    /**
     *  @param from dest or null if it didn't come in on signed port
     */
    private void receiveMessage(Destination from, int fromPort, byte[] payload) {

        try {
            InputStream is = new ByteArrayInputStream(payload);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Got KRPC message " + bev.toString());

            // Lazy here, just let missing Map entries throw NPEs, caught below

            byte[] msgIDBytes = map.get("t").getBytes();
            MsgID mID = new MsgID(msgIDBytes);
            String type = map.get("y").getString();
            if (type.equals("q")) {
                // queries must be repliable
                String method = map.get("q").getString();
                Map<String, BEValue> args = map.get("a").getMap();
                receiveQuery(mID, from, fromPort, method, args);
            } else if (type.equals("r") || type.equals("e")) {
               // get dest from id->dest map
                ReplyWaiter waiter = _sentQueries.remove(mID);
                if (waiter != null) {
                    // TODO verify waiter NID and port?
                    if (type.equals("r")) {
                        Map<String, BEValue> response = map.get("r").getMap();
                        receiveResponse(waiter, response);
                    } else {
                        List<BEValue> error = map.get("e").getList();
                        receiveError(waiter, error);
                    }
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Rcvd msg with no one waiting: " + bev.toString());
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unknown msg type rcvd: " + bev.toString());
                throw new InvalidBEncodingException("Unknown type: " + type);
            }
            // success
      /***
        } catch (InvalidBEncodingException e) {
        } catch (IOException e) {
        } catch (ArrayIndexOutOfBoundsException e) {
        } catch (IllegalArgumentException e) {
        } catch (ClassCastException e) {
        } catch (NullPointerException e) {
       ***/
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Receive error for message", e);
        }
    }


    // Queries.....

    /**
     *  Adds sender to our DHT.
     *  @param dest may be null for announce_peer method only
     *  @throws NPE too
     */
    private void receiveQuery(MsgID msgID, Destination dest, int fromPort, String method, Map<String, BEValue> args) throws InvalidBEncodingException {
        if (dest == null && !method.equals("announce_peer")) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received non-announce_peer query method on reply port: " + method);
            return;
        }
        byte[] nid = args.get("id").getBytes();
        NodeInfo nInfo;
        if (dest != null) {
            nInfo = new NodeInfo(new NID(nid), dest, fromPort);
            nInfo = heardFrom(nInfo);
            nInfo.setDestination(dest);
            // ninfo.checkport ?
        } else {
            nInfo = null;
        }

        if (method.equals("ping")) {
            receivePing(msgID, nInfo);
        } else if (method.equals("find_node")) {
            byte[] tid = args.get("target").getBytes();
            NID tID = new NID(tid);
            receiveFindNode(msgID, nInfo, tID);
        } else if (method.equals("get_peers")) {
            byte[] hash = args.get("info_hash").getBytes();
            InfoHash ih = new InfoHash(hash);
            receiveGetPeers(msgID, nInfo, ih);
        } else if (method.equals("announce_peer")) {
            byte[] hash = args.get("info_hash").getBytes();
            InfoHash ih = new InfoHash(hash);
            // this is the "TCP" port, we don't care
            //int port = args.get("port").getInt();
            byte[] token = args.get("token").getBytes();
            receiveAnnouncePeer(msgID, ih, token);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown query method rcvd: " + method);
        }
    }

    /**
     *  Called for a request or response
     *  @return old NodeInfo or nInfo if none, use this to reduce object churn
     */
    private NodeInfo heardFrom(NodeInfo nInfo) {
        // try to keep ourselves out of the DHT
        if (nInfo.equals(_myNodeInfo))
            return _myNodeInfo;
        NID nID = nInfo.getNID();
        NodeInfo oldInfo = _knownNodes.get(nID);
        if (oldInfo == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Adding node: " + nInfo);
            oldInfo = nInfo;
            NodeInfo nInfo2 = _knownNodes.putIfAbsent(nInfo);
            if (nInfo2 != null)
                oldInfo = nInfo2;
        } else {
            if (oldInfo.getDestination() == null && nInfo.getDestination() != null)
                oldInfo.setDestination(nInfo.getDestination());
        }
        oldInfo.getNID().setLastSeen();
        return oldInfo;
    }

    /**
     *  Called for bootstrap or for all nodes in a receiveNodes reply.
     *  Package private for PersistDHT.
     *  @return non-null nodeInfo from DB if present, otherwise the nInfo parameter is returned
     */
    NodeInfo heardAbout(NodeInfo nInfo) {
        // try to keep ourselves out of the DHT
        if (nInfo.equals(_myNodeInfo))
            return _myNodeInfo;
        NodeInfo rv = _knownNodes.putIfAbsent(nInfo);
        if (rv == null)
            rv = nInfo;
        return rv;
    }

    /**
     *  Called when a reply times out
     */
    private void timeout(NodeInfo nInfo) {
        NID nid = nInfo.getNID();
        boolean remove = nid.timeout();
        if (remove) {
            if (_knownNodes.remove(nid) != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Removed after consecutive timeouts: " + nInfo);
            }
        }
    }

    /**
     *  Handle and respond to the query
     */
    private void receivePing(MsgID msgID, NodeInfo nInfo) throws InvalidBEncodingException {
        if (_log.shouldLog(Log.INFO))
            _log.info("Rcvd ping from: " + nInfo);
        sendPong(nInfo, msgID);
    }

    /**
     *  Handle and respond to the query
     *  @param tID target ID they are looking for
     */
    private void receiveFindNode(MsgID msgID, NodeInfo nInfo, NID tID) throws InvalidBEncodingException {
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd find_node from: " + nInfo + " for: " + tID);
        NodeInfo peer = _knownNodes.get(tID);
        if (peer != null) {
            // success, one answer
            sendNodes(nInfo, msgID, peer.getData());
        } else {
            // get closest from DHT
            List<NodeInfo> nodes = _knownNodes.findClosest(tID, K);
            nodes.remove(nInfo);        // him
            nodes.remove(_myNodeInfo);  // me
            byte[] nodeArray = new byte[nodes.size() * NodeInfo.LENGTH];
            for (int i = 0; i < nodes.size(); i ++) {
                System.arraycopy(nodes.get(i).getData(), 0, nodeArray, i * NodeInfo.LENGTH, NodeInfo.LENGTH);
            }
            sendNodes(nInfo, msgID, nodeArray);
        }
    }

    /**
     *  Handle and respond to the query
     */
    private void receiveGetPeers(MsgID msgID, NodeInfo nInfo, InfoHash ih) throws InvalidBEncodingException {
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd get_peers from: " + nInfo + " for: " + ih);
        // generate and save random token
        Token token = new Token(_context);
        _outgoingTokens.put(token, nInfo);
        if (_log.shouldLog(Log.INFO))
             _log.info("Stored new OB token: " + token + " for: " + nInfo);

        List<Hash> peers = _tracker.getPeers(ih, MAX_WANT);
        if (peers.isEmpty()) {
            // similar to find node, but with token
            // get closest from DHT
            List<NodeInfo> nodes = _knownNodes.findClosest(ih, K);
            nodes.remove(nInfo);        // him
            nodes.remove(_myNodeInfo);  // me
            byte[] nodeArray = new byte[nodes.size() * NodeInfo.LENGTH];
            for (int i = 0; i < nodes.size(); i ++) {
                System.arraycopy(nodes.get(i).getData(), 0, nodeArray, i * NodeInfo.LENGTH, NodeInfo.LENGTH);
            }
            sendNodes(nInfo, msgID, token, nodeArray);
        } else {
            List<byte[]> hashes = new ArrayList(peers.size());
            Hash him = nInfo.getHash();
            for (Hash peer : peers) {
                 if (!peer.equals(him))
                     hashes.add(peer.getData());
            }
            sendPeers(nInfo, msgID, token, hashes);
        }
    }

    /**
     *  Handle and respond to the query.
     *  We have no node info here, it came on response port, we have to get it from the token
     */
    private void receiveAnnouncePeer(MsgID msgID, InfoHash ih, byte[] tok) throws InvalidBEncodingException {
        Token token = new Token(tok);
        NodeInfo nInfo = _outgoingTokens.get(token);
        if (nInfo == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown token in announce_peer: " + token);
            if (_log.shouldLog(Log.INFO))
                _log.info("Current known tokens: " + _outgoingTokens.keySet());
            return;
        }
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd announce from: " + nInfo + " for: " + ih);

        _tracker.announce(ih, nInfo.getHash());
        // the reply for an announce is the same as the reply for a ping
        sendPong(nInfo, msgID);
    }

    // Responses.....

    /**
     *  Handle the response and alert whoever sent the query it is responding to.
     *  Adds sender nodeinfo to our DHT.
     *  @throws NPE, IllegalArgumentException, and others too
     */
    private void receiveResponse(ReplyWaiter waiter, Map<String, BEValue> response) throws InvalidBEncodingException {
        NodeInfo nInfo = waiter.getSentTo();

        BEValue nodes = response.get("nodes");
        BEValue values = response.get("values");

        // token handling - save it for later announces
        if (nodes != null || values != null) {
            BEValue btok = response.get("token");
            InfoHash ih = (InfoHash) waiter.getSentObject();
            if (btok != null && ih != null) {
                byte[] tok = btok.getBytes();
                Token token = new Token(_context, tok);
                _incomingTokens.put(nInfo.getNID(), token);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Got token: " + token + ", must be a response to get_peers");
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("No token and saved infohash, must be a response to find_node");
            }
        }

        // now do the right thing
        if (nodes != null) {
            // find node or get peers response - concatenated NodeInfos
            byte[] ids = nodes.getBytes();
            List<NodeInfo> rlist = receiveNodes(nInfo, ids);
            waiter.gotReply(REPLY_NODES, rlist);
        } else if (values != null) {
            // get peers response - list of Hashes
            List<BEValue> peers = values.getList();
            List<Hash> rlist = receivePeers(nInfo, peers);
            waiter.gotReply(REPLY_PEERS, rlist);
        } else {
            // a ping response or an announce peer response
            byte[] nid = response.get("id").getBytes();
            receivePong(nInfo, nid);
            waiter.gotReply(REPLY_PONG, null);
        }
    }

    /**
     *  rcv concatenated 54 byte NodeInfos, return as a List
     *  Adds all received nodeinfos to our DHT.
     *  @throws NPE, IllegalArgumentException, and others too
     */
    private List<NodeInfo> receiveNodes(NodeInfo nInfo, byte[] ids) throws InvalidBEncodingException {
        List<NodeInfo> rv = new ArrayList(ids.length / NodeInfo.LENGTH);
        for (int off = 0; off < ids.length; off += NodeInfo.LENGTH) {
            NodeInfo nInf = new NodeInfo(ids, off);
            nInf = heardAbout(nInf);
            rv.add(nInf);
        }
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd nodes from: " + nInfo + ": " + DataHelper.toString(rv));
        return rv;
    }

    /**
     *  rcv 32 byte Hashes, return as a List
     *  @throws NPE, IllegalArgumentException, and others too
     */
    private List<Hash> receivePeers(NodeInfo nInfo, List<BEValue> peers) throws InvalidBEncodingException {
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd peers from: " + nInfo);
        List<Hash> rv = new ArrayList(peers.size());
        for (BEValue bev : peers) {
            byte[] b = bev.getBytes();
            //Hash h = new Hash(b);
            Hash h = Hash.create(b);
            rv.add(h);
        }
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd peers from: " + nInfo + ": " + DataHelper.toString(rv));
        return rv;
    }

    /**
     *  If node info was previously created with the dummy NID,
     *  replace it with the received NID.
     */
    private void receivePong(NodeInfo nInfo, byte[] nid) {
        if (nInfo.getNID().equals(FAKE_NID)) {
            NodeInfo newInfo = new NodeInfo(new NID(nid), nInfo.getHash(), nInfo.getPort());
            Destination dest = nInfo.getDestination();
            if (dest != null)
                newInfo.setDestination(dest);
            heardFrom(newInfo);
        }
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd pong from: " + nInfo);
    }

    // Errors.....

    /**
     *  @throws NPE, and others too
     */
    private void receiveError(ReplyWaiter waiter, List<BEValue> error) throws InvalidBEncodingException {
        int errorCode = error.get(0).getInt();
        String errorString = error.get(1).getString();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Rcvd error from: " + waiter +
                      " num: " + errorCode +
                      " msg: " + errorString);
        // this calls heardFrom()
        waiter.gotReply(errorCode, errorString);
    }

    /**
     * Callback for replies
     */
    private class ReplyWaiter extends SimpleTimer2.TimedEvent {
        private final MsgID mid;
        private final NodeInfo sentTo;
        private final Runnable onReply;
        private final Runnable onTimeout;
        private int replyCode;
        private Object sentObject;
        private Object replyObject;

        /**
         *  Either wait on this object with a timeout, or use non-null Runnables.
         *  Any sent data to be remembered may be stored by setSentObject().
         *  Reply object may be in getReplyObject().
         *  @param onReply must be fast, otherwise set to null and wait on this UNUSED
         *  @param onTimeout must be fast, otherwise set to null and wait on this UNUSED
         */
        public ReplyWaiter(MsgID mID, NodeInfo nInfo, Runnable onReply, Runnable onTimeout) {
            super(SimpleTimer2.getInstance(), DEFAULT_QUERY_TIMEOUT);
            this.mid = mID;
            this.sentTo = nInfo;
            this.onReply = onReply;
            this.onTimeout = onTimeout;
        }

        public NodeInfo getSentTo() {
            return sentTo;
        }

        /** only used for get_peers, to save the Info Hash */
        public void setSentObject(Object o) {
            sentObject = o;
        }

        /** @return that stored with setSentObject() */
        public Object getSentObject() {
            return sentObject;
        }

        /**
         *  Should contain null if getReplyCode is REPLY_PONG.
         *  Should contain List<Hash> if getReplyCode is REPLY_PEERS.
         *  Should contain List<NodeInfo> if getReplyCode is REPLY_NODES.
         *  Should contain String if getReplyCode is > 200.
         *  @return may be null depending on what happened. Cast to expected type.
         */
        public Object getReplyObject() {
            return replyObject;
        }

        /**
         *  If nonzero, we got a reply, and getReplyObject() may contain something.
         *  @return code or 0 if no error
         */
        public int getReplyCode() {
            return replyCode;
        }

        /**
         *  Will notify this and run onReply.
         *  Also removes from _sentQueries and calls heardFrom().
         */
        public void gotReply(int code, Object o) {
            cancel();
            _sentQueries.remove(mid);
            replyObject = o;
            replyCode = code;
            // if it is fake, heardFrom is called by receivePong()
            if (!sentTo.getNID().equals(FAKE_NID))
                heardFrom(sentTo);
            if (onReply != null)
                onReply.run();
            synchronized(this) {
                this.notifyAll();
            }
        }

        /** timer callback on timeout */
        public void timeReached() {
            _sentQueries.remove(mid);
            if (onTimeout != null)
                onTimeout.run();
            timeout(sentTo);
            if (_log.shouldLog(Log.INFO))
                _log.warn("timeout waiting for reply from " + sentTo);
            synchronized(this) {
                this.notifyAll();
            }
        }
    }

    // I2PSessionMuxedListener interface ----------------

    /**
     * Instruct the client that the given session has received a message
     *
     * Will be called only if you register via addMuxedSessionListener().
     * Will be called only for the proto(s) and toPort(s) you register for.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     * @param proto 1-254 or 0 for unspecified
     * @param fromPort 1-65535 or 0 for unspecified
     * @param toPort 1-65535 or 0 for unspecified
     */
    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
        try {
            byte[] payload = session.receiveMessage(msgId);
            if (toPort == _qPort) {
                // repliable
                I2PDatagramDissector dgDiss = new I2PDatagramDissector();
                dgDiss.loadI2PDatagram(payload);
                payload = dgDiss.getPayload();
                Destination from = dgDiss.getSender();
                receiveMessage(from, fromPort, payload);
            } else if (toPort == _rPort) {
                // raw
                receiveMessage(null, fromPort, payload);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("msg on bad port");
            }
        } catch (DataFormatException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("bad msg");
        } catch (I2PInvalidDatagramException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("bad msg");
        } catch (I2PSessionException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("bad msg");
        }
    }

    /** for non-muxed */
    public void messageAvailable(I2PSession session, int msgId, long size) {}

    public void reportAbuse(I2PSession session, int severity) {}

    public void disconnected(I2PSession session) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("KRPC disconnected");
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("KRPC got error msg: ", error);
    }

    /**
     * Cleaner-upper
     */
    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(SimpleTimer2.getInstance(), CLEAN_TIME);
        }

        public void timeReached() {
            if (!_isRunning)
                return;
            long now = _context.clock().now();
            for (Iterator<Token> iter = _outgoingTokens.keySet().iterator(); iter.hasNext(); ) {
                Token tok = iter.next();
                if (tok.lastSeen() < now - MAX_TOKEN_AGE)
                    iter.remove();
            }
            for (Iterator<Token> iter = _incomingTokens.values().iterator(); iter.hasNext(); ) {
                Token tok = iter.next();
                if (tok.lastSeen() < now - MAX_INBOUND_TOKEN_AGE)
                    iter.remove();
            }
            // TODO sent queries?
            for (Iterator<NodeInfo> iter = _knownNodes.values().iterator(); iter.hasNext(); ) {
                NodeInfo ni = iter.next();
                if (ni.lastSeen() < now - MAX_NODEINFO_AGE)
                    iter.remove();
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("KRPC cleaner done, now with " +
                          _outgoingTokens.size() + " sent Tokens, " +
                          _incomingTokens.size() + " rcvd Tokens, " +
                          _knownNodes.size() + " known peers, " +
                          _sentQueries.size() + " queries awaiting response");
            schedule(CLEAN_TIME);
        }
    }

    /**
     * Fire off explorer thread
     */
    private class Explorer extends SimpleTimer2.TimedEvent {

        public Explorer(long delay) {
            super(SimpleTimer2.getInstance(), delay);
        }

        public void timeReached() {
            if (!_isRunning)
                return;
            if (_knownNodes.size() > 0)
                (new I2PAppThread(new ExplorerThread(), "DHT Explore", true)).start();
            else
                schedule(60*1000);
        }
    }

    /**
     * explorer thread
     */
    private class ExplorerThread implements Runnable {

        public void run() {
            if (!_isRunning)
                return;
            if (!_hasBootstrapped) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Bootstrap start size: " + _knownNodes.size());
                explore(_myNID, 8, 60*1000, 1);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Bootstrap done size: " + _knownNodes.size());
                _hasBootstrapped = true;
            }
            if (!_isRunning)
                return;
            if (_log.shouldLog(Log.INFO))
                _log.info("Explore start size: " + _knownNodes.size());
            List<NID> keys = _knownNodes.getExploreKeys();
            for (NID nid : keys) {
                explore(nid, 8, 60*1000, 1);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Explore done size: " + _knownNodes.size());
            new Explorer(EXPLORE_TIME);
        }
    }
}
