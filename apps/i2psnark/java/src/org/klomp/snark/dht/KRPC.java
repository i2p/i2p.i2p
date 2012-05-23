package org.klomp.snark.dht;

/*
 *  GPLv2
 */

import java.io.ByteArrayInputStream;
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
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
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
    /** index to outgoing tokens, sent in reply to a get_peers query */
    private final ConcurrentHashMap<InfoHash, Token> _outgoingTokens;
    /** index to incoming tokens, received in a peers or nodes reply */
    private final ConcurrentHashMap<TokenKey, Token> _incomingTokens;

    /** hook to inject and receive datagrams */
    private final I2PSession _session;
    /** 20 byte random id + 32 byte Hash + 2 byte port */
    private final NodeInfo _myNodeInfo;
    /** unsigned dgrams */
    private final int _rPort;
    /** signed dgrams */
    private final int _qPort;

    /** all-zero NID used for pings */
    private static final NID _fakeNID = new NID(new byte[NID.HASH_LENGTH]);

    /** Max number of nodes to return. BEP 5 says 8 */
    private static final int K = 8;
    /** Max number of peers to return. BEP 5 doesn't say. We'll use the same as I2PSnarkUtil.MAX_CONNECTIONS */
    private static final int MAX_WANT = 16;

    /** overloads error codes which start with 201 */
    private static final int REPLY_NONE = 0;
    private static final int REPLY_PONG = 1;
    private static final int REPLY_PEERS = 2;
    private static final int REPLY_NODES = 3;

    /** how long since last heard from do we delete  - BEP 5 says 15 minutes */
    private static final long MAX_NODEINFO_AGE = 60*60*1000;
    /** how long since generated do we delete - BEP 5 says 10 minutes */
    private static final long MAX_TOKEN_AGE = 60*60*1000;
    /** how long since sent do we wait for a reply */
    private static final long MAX_MSGID_AGE = 2*60*1000;
    /** how long since sent do we wait for a reply */
    private static final long DEFAULT_QUERY_TIMEOUT = 75*1000;
    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 63*1000;

    public KRPC (I2PAppContext ctx, I2PSession session) {
        _context = ctx;
        _session = session;
        _log = ctx.logManager().getLog(KRPC.class);
        _tracker = new DHTTracker(ctx);

        // in place of a DHT, store everybody we hear from for now
        _knownNodes = new DHTNodes(ctx);
        _sentQueries = new ConcurrentHashMap();
        _outgoingTokens = new ConcurrentHashMap();
        _incomingTokens = new ConcurrentHashMap();

        // Construct my NodeInfo
        // ports can really be fixed, just do this for testing
        _qPort = 30000 + ctx.random().nextInt(99);
        _rPort = _qPort + 1;
        byte[] myID = new byte[NID.HASH_LENGTH];
        ctx.random().nextBytes(myID);
        NID myNID = new NID(myID);
        _myNodeInfo = new NodeInfo(myNID, session.getMyDestination(), _qPort);

        session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM, _rPort);
        session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM, _qPort);
        // can't be stopped
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    ///////////////// Public methods

    /**
     *  For bootstrapping if loaded from config file.
     *  @param when when did we hear from them
     */
    public void addNode(NodeInfo nInfo, long when) {
        heardFrom(nInfo, when);
    }

    /**
     *  NodeInfo heard from
     */
    public void addNode(NodeInfo nInfo) {
        heardFrom(nInfo);
    }

    /**
     *  For saving in a config file.
     *  @return the values, not a copy, could change, use an iterator
     */
    public Collection<NodeInfo> getNodes() {
        return _knownNodes.values();
    }

    /**
     *  @return The UDP port that should be included in a PORT message.
     */
    public int getPort() {
        return _qPort;
    }

    /**
     *  Ping. We don't have a NID yet so the node is presumed
     *  to be absent from our DHT.
     *  Non-blocking, does not wait for pong.
     *  If and when the pong is received the node will be inserted in our DHT.
     */
    public void ping(Destination dest, int port) {
        NodeInfo nInfo = new NodeInfo(_fakeNID, dest, port);
        sendPing(nInfo);
    }

    /**
     *  Bootstrapping or background thread.
     *  Blocking!
     *  This is almost the same as getPeers()
     *
     *  @param maxNodes how many to contact
     *  @param maxWait how long to wait for each to reply (not total) must be > 0
     *  @param parallel how many outstanding at once (unimplemented, always 1)
     */
    public void explore(int maxNodes, long maxWait, int parallel) {
        // Initial set to try, will get added to as we go
        NID myNID = _myNodeInfo.getNID();
        List<NodeInfo> nodes = _knownNodes.findClosest(myNID, maxNodes);
        if (nodes.isEmpty()) {
            if (_log.shouldLog(Log.WARN))
                _log.info("DHT is empty, cannot explore");
            return;
        }
        SortedSet<NodeInfo> toTry = new TreeSet(new NodeInfoComparator(myNID));
        toTry.addAll(nodes);
        Set<NodeInfo> tried = new HashSet();

        if (_log.shouldLog(Log.INFO))
            _log.info("Starting explore");
        for (int i = 0; i < maxNodes; i++) {
            NodeInfo nInfo;
            try {
                nInfo = toTry.first();
            } catch (NoSuchElementException nsee) {
                break;
            }
            toTry.remove(nInfo);
            tried.add(nInfo);

            // this isn't going to work, he will just return our own?
            ReplyWaiter waiter = sendFindNode(nInfo, _myNodeInfo);
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
            _log.info("Finished explore");
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
            _log.info("Starting getPeers");
        for (int i = 0; i < max; i++) {
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
     *  @param peer the peer's Hash
     */
    public void announce(byte[] ih, byte[] peerHash) {
        InfoHash iHash = new InfoHash(ih);
        _tracker.announce(iHash, new Hash(peerHash));
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
     *  Announce to the closest DHT peers.
     *  Blocking unless maxWait <= 0
     *  Caller should run in a thread.
     *  This also automatically announces ourself to our local tracker.
     *  For best results do a getPeers() first so we have tokens.
     *
     *  @param ih the Info Hash (torrent)
     *  @param maxWait the maximum total time to wait (ms) or 0 to do all in parallel and return immediately.
     *  @return the number of successful announces, not counting ourselves.
     */
    public int announce(byte[] ih, int max, long maxWait) {
        announce(ih);
        int rv = 0;
        long start = _context.clock().now();
        List<NodeInfo> nodes = _knownNodes.findClosest(new InfoHash(ih), max);
        for (NodeInfo nInfo : nodes) {
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
    public boolean announce(byte[] ih, NodeInfo nInfo, long maxWait) {
        InfoHash iHash = new InfoHash(ih);
        TokenKey tokenKey = new TokenKey(nInfo.getNID(), iHash);
        Token token = _incomingTokens.get(tokenKey);
        if (token == null) {
            // we have no token, have to do a getPeers first to get a token
            if (maxWait <= 0)
                return false;
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
            if (!(replyType == REPLY_PEERS || replyType == REPLY_NODES))
                return false;
            // we should have a token now
            token = _incomingTokens.get(tokenKey);
            if (token == null)
                return false;
            maxWait -= _context.clock().now() - start;
            if (maxWait < 1000)
                return false;
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
     *  Does nothing yet, everything is prestarted.
     *  Can't be restarted after stopping?
     */
    public void start() {
        // start the explore thread
    }

    /**
     *  Does nothing yet.
     */
    public void stop() {
        // stop the explore thread
        // unregister port listeners
        // does not clear the DHT or tracker yet.
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
        Map<String, Object> map = new HashMap();
        map.put("q", "ping");
        Map<String, Object> args = new HashMap();
        map.put("a", args);
        return sendQuery(nInfo, map, true);
    }

    /**
     *  @param nInfo who to send it to
     *  @return null on error
     */
    private ReplyWaiter sendFindNode(NodeInfo nInfo, NodeInfo tID) {
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
        Map<String, Object> map = new HashMap();
        map.put("q", "get_peers");
        Map<String, Object> args = new HashMap();
        args.put("info_hash", ih.getData());
        map.put("a", args);
        return sendQuery(nInfo, map, true);
    }

    /**
     *  @param nInfo who to send it to
     *  @return null on error
     */
    private ReplyWaiter sendAnnouncePeer(NodeInfo nInfo, InfoHash ih, Token token) {
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
        // save the InfoHash so we can get it later
        if (rv != null)
            rv.setSentObject(ih);
        return rv;
    }

    // Responses.....
    // All responses use the response port.

    /**
     *  @param nInfo who to send it to
     *  @return success
     */
    private boolean sendPong(NodeInfo nInfo, MsgID msgID) {
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
        Map<String, Object> map = new HashMap();
        Map<String, Object> resps = new HashMap();
        map.put("r", resps);
        return sendResponse(nInfo, msgID, map);
    }

    // Low-level send methods

    // TODO sendQuery with onReply / onTimeout args

    /**
     *  @param repliable true for all but announce
     *  @return null on error
     */
    private ReplyWaiter sendQuery(NodeInfo nInfo, Map<String, Object> map, boolean repliable) {
        if (nInfo.equals(_myNodeInfo))
            throw new IllegalArgumentException("wtf don't send to ourselves");
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending query to: " + nInfo);
        if (nInfo.getDestination() == null) {
            NodeInfo newInfo = _knownNodes.get(nInfo.getNID());
            if (newInfo != null && newInfo.getDestination() != null) {
                nInfo = newInfo;
            } else {
                // lookup b32?
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No destination for: " + nInfo);
                return null;
            }
        }
        map.put("y", "q");
        MsgID mID = new MsgID(_context);
        map.put("t", mID.getData());
        Map<String, Object> args = (Map<String, Object>) map.get("a");
        if (args == null)
            throw new IllegalArgumentException("no args");
        args.put("id", _myNodeInfo.getData());
        int port = nInfo.getPort();
        if (!repliable)
            port++;
        boolean success = sendMessage(nInfo.getDestination(), port, map, true);
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
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending response to: " + nInfo);
        if (nInfo.getDestination() == null) {
            NodeInfo newInfo = _knownNodes.get(nInfo.getNID());
            if (newInfo != null && newInfo.getDestination() != null) {
                nInfo = newInfo;
            } else {
                // lookup b32?
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No destination for: " + nInfo);
                return false;
            }
        }
        map.put("y", "r");
        map.put("t", msgID.getData());
        Map<String, Object> resps = (Map<String, Object>) map.get("r");
        if (resps == null)
            throw new IllegalArgumentException("no resps");
        resps.put("id", _myNodeInfo.getData());
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
                    _log.warn("No destination for: " + nInfo);
                return false;
            }
        }
        map.put("y", "e");
        map.put("t", msgID.getData());
        return sendMessage(nInfo.getDestination(), nInfo.getPort() + 1, map, false);
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
            boolean success = _session.sendMessage(dest, payload, 0, payload.length, null, null, 60*1000,
                                                   I2PSession.PROTO_DATAGRAM, fromPort, toPort);
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
            if (type.equals("q") && from != null) {
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
     *  @param dest non-null
     *  @throws NPE too
     */
    private void receiveQuery(MsgID msgID, Destination dest, int fromPort, String method, Map<String, BEValue> args) throws InvalidBEncodingException {
        byte[] nid = args.get("id").getBytes();
        NodeInfo nInfo = new NodeInfo(nid);
        nInfo = heardFrom(nInfo);
        nInfo.setDestination(dest);
// ninfo.checkport ?

        if (method.equals("ping")) {
            receivePing(msgID, nInfo);
        } else if (method.equals("find_node")) {
            byte[] tid = args.get("target").getBytes();
            NodeInfo tID = new NodeInfo(tid);
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
            receiveAnnouncePeer(msgID, nInfo, ih, token);
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
        return heardFrom(nInfo, _context.clock().now());
   }

    /**
     *  Used for initialization
     *  @return old NodeInfo or nInfo if none, use this to reduce object churn
     */
    private NodeInfo heardFrom(NodeInfo nInfo, long when) {
        // try to keep ourselves out of the DHT
        if (nInfo.equals(_myNodeInfo))
            return _myNodeInfo;
        NID nID = nInfo.getNID();
        NodeInfo oldInfo = _knownNodes.get(nID);
        if (oldInfo == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Adding node: " + nInfo);
            oldInfo = nInfo;
            NodeInfo nInfo2 = _knownNodes.putIfAbsent(nID, nInfo);
            if (nInfo2 != null)
                oldInfo = nInfo2;
        }
        if (when > oldInfo.lastSeen())
            oldInfo.setLastSeen(when);
        return oldInfo;
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
     */
    private void receiveFindNode(MsgID msgID, NodeInfo nInfo, NodeInfo tID) throws InvalidBEncodingException {
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd find_node from: " + nInfo + " for: " + tID);
        NodeInfo peer = _knownNodes.get(tID);
        if (peer != null) {
            // success, one answer
            sendNodes(nInfo, msgID, peer.getData());
        } else {
            // get closest from DHT
            List<NodeInfo> nodes = _knownNodes.findClosest(tID.getNID(), K);
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
        _outgoingTokens.put(ih, token);

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
     *  Handle and respond to the query
     */
    private void receiveAnnouncePeer(MsgID msgID, NodeInfo nInfo, InfoHash ih, byte[] token) throws InvalidBEncodingException {
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd announce from: " + nInfo + " for: " + ih);
        // check token
        // get desthash from token->dest map
        Token oldToken = _outgoingTokens.get(ih);
        if (oldToken == null || !DataHelper.eq(oldToken.getData(), token)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad token");
            return;
        }

        //msg ID -> NodeInfo -> Dest -> Hash
        //verify with token -> nid or dest or hash ????

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
        NodeInfo nInfo = waiter;

        BEValue nodes = response.get("nodes");
        BEValue values = response.get("values");

        // token handling - save it for later announces
        if (nodes != null || values != null) {
            BEValue btok = response.get("token");
            InfoHash ih = (InfoHash) waiter.getSentObject();
            if (btok != null && ih != null) {
                byte[] tok = btok.getBytes();
                _incomingTokens.put(new TokenKey(nInfo.getNID(), ih), new Token(_context, tok));
                if (_log.shouldLog(Log.INFO))
                    _log.info("Got token, must be a response to get_peers");
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
            receivePong(nInfo);
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
        long fakeTime = _context.clock().now() - (MAX_NODEINFO_AGE * 3 / 4);
        for (int off = 0; off < ids.length; off += NodeInfo.LENGTH) {
            NodeInfo nInf = new NodeInfo(ids, off);
            // anti-churn
            // TODO do we need heardAbout too?
            nInf = heardFrom(nInf, fakeTime);
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
            Hash h = new Hash(b);
            rv.add(h);
        }
        if (_log.shouldLog(Log.INFO))
             _log.info("Rcvd peers from: " + nInfo + ": " + DataHelper.toString(rv));
        return rv;
    }

    /** does nothing, but node was already added to our DHT */
    private void receivePong(NodeInfo nInfo) {
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
    private class ReplyWaiter extends NodeInfo {
        private final MsgID mid;
        private final Runnable onReply;
        private final Runnable onTimeout;
        private final SimpleTimer2.TimedEvent event;
        private int replyCode;
        private Object sentObject;
        private Object replyObject;

        /**
         *  Either wait on this object with a timeout, or use non-null Runnables.
         *  Any sent data to be rememberd may be stored by setSentObject().
         *  Reply object may be in getReplyObject().
         *  @param onReply must be fast, otherwise set to null and wait on this
         *  @param onTimeout must be fast, otherwise set to null and wait on this
         */
        public ReplyWaiter(MsgID mID, NodeInfo nInfo, Runnable onReply, Runnable onTimeout) {
            super(nInfo.getData());
            this.mid = mID;
            this.onReply = onReply;
            this.onTimeout = onTimeout;
            if (onTimeout != null)
                this.event = new Event();
            else
                this.event = null;
        }

        /** only used for announce, to save the Info Hash */
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
            replyCode = code;
            replyObject = o;
            if (event != null)
                event.cancel();
            _sentQueries.remove(mid);
            heardFrom(this);
            if (onReply != null)
                onReply.run();
            synchronized(this) {
                this.notifyAll();
            }
        }

        private class Event extends SimpleTimer2.TimedEvent {
            public Event() {
                super(SimpleTimer2.getInstance(), DEFAULT_QUERY_TIMEOUT);
            }

            public void timeReached() {
                _sentQueries.remove(mid);
                if (onTimeout != null)
                    onTimeout.run();
                if (_log.shouldLog(Log.INFO))
                    _log.warn("timeout waiting for reply from " + this.toString());
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
    private class Cleaner implements SimpleTimer.TimedEvent {

        public void timeReached() {
            long now = _context.clock().now();
            for (Iterator<Token> iter = _outgoingTokens.values().iterator(); iter.hasNext(); ) {
                Token tok = iter.next();
                if (tok.lastSeen() < now - MAX_TOKEN_AGE)
                    iter.remove();
            }
            for (Iterator<Token> iter = _incomingTokens.values().iterator(); iter.hasNext(); ) {
                Token tok = iter.next();
                if (tok.lastSeen() < now - MAX_TOKEN_AGE)
                    iter.remove();
            }
            // TODO sent queries?
            for (Iterator<NodeInfo> iter = _knownNodes.values().iterator(); iter.hasNext(); ) {
                NodeInfo ni = iter.next();
                if (ni.lastSeen() < now - MAX_NODEINFO_AGE)
                    iter.remove();
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("KRPC cleaner done, now with " +
                          _outgoingTokens.size() + " sent Tokens, " +
                          _incomingTokens.size() + " rcvd Tokens, " +
                          _knownNodes.size() + " known peers, " +
                          _sentQueries.size() + " queries awaiting response");
        }
    }
}
