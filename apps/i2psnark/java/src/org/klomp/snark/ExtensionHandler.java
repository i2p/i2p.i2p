package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;

/**
 * REF: BEP 10 Extension Protocol
 * @since 0.8.2
 * @author zzz
 */
abstract class ExtensionHandler {

    public static final int ID_HANDSHAKE = 0;
    public static final int ID_METADATA = 1;
    public static final String TYPE_METADATA = "ut_metadata";
    public static final int ID_PEX = 2;
    /** not ut_pex since the compact format is different */
    public static final String TYPE_PEX = "i2p_pex";
    public static final int ID_DHT = 3;
    /** not using the option bit since the compact format is different */
    public static final String TYPE_DHT = "i2p_dht";
    /** @since 0.9.31 */
    public static final int ID_COMMENT = 4;
    /** @since 0.9.31 */
    public static final String TYPE_COMMENT = "ut_comment";
    /** Pieces * SHA1 Hash length, + 25% extra for file names, bencoding overhead, etc */
    private static final int MAX_METADATA_SIZE = Storage.MAX_PIECES * 20 * 5 / 4;
    private static final int PARALLEL_REQUESTS = 3;


  /**
   *  @param metasize -1 if unknown
   *  @param pexAndMetadata advertise these capabilities
   *  @param dht advertise DHT capability
   *  @param comment advertise ut_comment capability
   *  @return bencoded outgoing handshake message
   */
    public static byte[] getHandshake(int metasize, boolean pexAndMetadata, boolean dht, boolean uploadOnly, boolean comment) {
        Map<String, Object> handshake = new HashMap<String, Object>();
        Map<String, Integer> m = new HashMap<String, Integer>();
        if (pexAndMetadata) {
            m.put(TYPE_METADATA, Integer.valueOf(ID_METADATA));
            m.put(TYPE_PEX, Integer.valueOf(ID_PEX));
            if (metasize >= 0)
                handshake.put("metadata_size", Integer.valueOf(metasize));
        }
        if (dht) {
            m.put(TYPE_DHT, Integer.valueOf(ID_DHT));
        }
        if (comment) {
            m.put(TYPE_COMMENT, Integer.valueOf(ID_COMMENT));
        }
        // include the map even if empty so the far-end doesn't NPE
        handshake.put("m", m);
        handshake.put("p", Integer.valueOf(TrackerClient.PORT));
        handshake.put("v", "I2PSnark");
        handshake.put("reqq", Integer.valueOf(5));
        // BEP 21
        if (uploadOnly)
            handshake.put("upload_only", Integer.valueOf(1));
        return BEncoder.bencode(handshake);
    }

    public static void handleMessage(Peer peer, PeerListener listener, int id, byte[] bs) {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(ExtensionHandler.class);
        if (log.shouldLog(Log.INFO))
            log.info("Got extension msg " + id + " length " + bs.length + " from " + peer);
        if (id == ID_HANDSHAKE)
            handleHandshake(peer, listener, bs, log);
        else if (id == ID_METADATA)
            handleMetadata(peer, listener, bs, log);
        else if (id == ID_PEX)
            handlePEX(peer, listener, bs, log);
        else if (id == ID_DHT)
            handleDHT(peer, listener, bs, log);
        else if (id == ID_COMMENT)
            handleComment(peer, listener, bs, log);
        else if (log.shouldLog(Log.INFO))
            log.info("Unknown extension msg " + id + " from " + peer);
    }

    private static void handleHandshake(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldLog(Log.DEBUG))
            log.debug("Got handshake msg from " + peer);
        try {
            // this throws NPE on missing keys
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            peer.setHandshakeMap(map);
            Map<String, BEValue> msgmap = map.get("m").getMap();

            if (log.shouldLog(Log.DEBUG))
                log.debug("Peer " + peer + " supports extensions: " + msgmap.keySet());

            //if (msgmap.get(TYPE_PEX) != null) {
            //    if (log.shouldLog(Log.DEBUG))
            //        log.debug("Peer supports PEX extension: " + peer);
            //    // peer state calls peer listener calls sendPEX()
            //}

            //if (msgmap.get(TYPE_DHT) != null) {
            //    if (log.shouldLog(Log.DEBUG))
            //        log.debug("Peer supports DHT extension: " + peer);
            //    // peer state calls peer listener calls sendDHT()
            //}

            MagnetState state = peer.getMagnetState();

            if (msgmap.get(TYPE_METADATA) == null) {
                if (log.shouldLog(Log.DEBUG))
                    log.debug("Peer does not support metadata extension: " + peer);
                // drop if we need metainfo and we haven't found anybody yet
                synchronized(state) {
                    if (!state.isInitialized()) {
                        if (log.shouldLog(Log.DEBUG))
                            log.debug("Dropping peer, we need metadata! " + peer);
                        peer.disconnect();
                    }
                }
                return;
            }

            BEValue msize = map.get("metadata_size");
            if (msize == null) {
                if (log.shouldLog(Log.DEBUG))
                    log.debug("Peer does not have the metainfo size yet: " + peer);
                // drop if we need metainfo and we haven't found anybody yet
                synchronized(state) {
                    if (!state.isInitialized()) {
                        if (log.shouldLog(Log.DEBUG))
                            log.debug("Dropping peer, we need metadata! " + peer);
                        peer.disconnect();
                    }
                }
                return;
            }
            int metaSize = msize.getInt();
            if (log.shouldLog(Log.DEBUG))
                log.debug("Got the metainfo size: " + metaSize);

            int remaining;
            synchronized(state) {
                if (state.isComplete())
                    return;

                if (state.isInitialized()) {
                    if (state.getSize() != metaSize) {
                        if (log.shouldLog(Log.DEBUG))
                            log.debug("Wrong metainfo size " + metaSize + " from: " + peer);
                        peer.disconnect();
                        return;
                    }
                } else {
                    // initialize it
                    if (metaSize > MAX_METADATA_SIZE) {
                        if (log.shouldLog(Log.DEBUG))
                            log.debug("Huge metainfo size " + metaSize + " from: " + peer);
                        peer.disconnect(false);
                        return;
                    }
                    if (log.shouldLog(Log.INFO))
                        log.info("Initialized state, metadata size = " + metaSize + " from " + peer);
                    state.initialize(metaSize);
                }
                remaining = state.chunksRemaining();
            }

            // send requests for chunks
            int count = Math.min(remaining, PARALLEL_REQUESTS);
            for (int i = 0; i < count; i++) {
                int chk;
                synchronized(state) {
                    chk = state.getNextRequest();
                }
                if (log.shouldLog(Log.INFO))
                    log.info("Request chunk " + chk + " from " + peer);
                sendRequest(peer, chk);
            }
        } catch (Exception e) {
            if (log.shouldLog(Log.WARN))
                log.warn("Handshake exception from " + peer, e);
        }
    }

    private static final int TYPE_REQUEST = 0;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_REJECT = 2;

    /**
     * REF: BEP 9
     * @since 0.8.4
     */
    private static void handleMetadata(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldLog(Log.DEBUG))
            log.debug("Got metadata msg from " + peer);
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int type = map.get("msg_type").getInt();
            int piece = map.get("piece").getInt();

            MagnetState state = peer.getMagnetState();
            if (type == TYPE_REQUEST) {
                if (log.shouldLog(Log.DEBUG))
                    log.debug("Got request for " + piece + " from: " + peer);
                byte[] pc;
                int totalSize;
                synchronized(state) {
                    pc = state.getChunk(piece);
                    totalSize = state.getSize();
                }
                sendPiece(peer, piece, pc, totalSize);
                // Do this here because PeerConnectionOut only reports for PIECE messages
                peer.uploaded(pc.length);
                listener.uploaded(peer, pc.length);
            } else if (type == TYPE_DATA) {
                // On close reading of BEP 9, this is the total metadata size.
                // Prior to 0.9.21, we sent the piece size, so we can't count on it.
                // just ignore it. The actual length will be verified in saveChunk()
                //int size = map.get("total_size").getInt();
                //if (log.shouldLog(Log.DEBUG))
                //    log.debug("Got data for " + piece + " length " + size + " from: " + peer);
                boolean done;
                int chk = -1;
                synchronized(state) {
                    if (state.isComplete())
                        return;
                    int len = is.available();
                    peer.downloaded(len);
                    listener.downloaded(peer, len);
                    // this checks the size
                    done = state.saveChunk(piece, bs, bs.length - len, len);
                    if (log.shouldLog(Log.INFO))
                        log.info("Got chunk " + piece + " from " + peer);
                    if (!done)
                        chk = state.getNextRequest();
                }
                // out of the lock
                if (done) {
                    // Done!
                    // PeerState will call the listener (peer coord), who will
                    // check to see if the MagnetState has it
                    if (log.shouldLog(Log.WARN))
                        log.warn("Got last chunk from " + peer);
                } else {
                    // get the next chunk
                    if (log.shouldLog(Log.INFO))
                        log.info("Request chunk " + chk + " from " + peer);
                    sendRequest(peer, chk);
                }
            } else if (type == TYPE_REJECT) {
                if (log.shouldLog(Log.WARN))
                    log.warn("Got reject msg from " + peer);
                peer.disconnect(false);
            } else {
                if (log.shouldLog(Log.WARN))
                    log.warn("Got unknown metadata msg from " + peer);
                peer.disconnect(false);
            }
        } catch (Exception e) {
            if (log.shouldLog(Log.INFO))
                log.info("Metadata ext. msg. exception from " + peer, e);
            // fatal ?
            peer.disconnect(false);
        }
    }

    private static void sendRequest(Peer peer, int piece) {
        sendMessage(peer, TYPE_REQUEST, piece);
    }

  /****
    private static void sendReject(Peer peer, int piece) {
        sendMessage(peer, TYPE_REJECT, piece);
    }
  ****/

    /** REQUEST and REJECT are the same except for message type */
    private static void sendMessage(Peer peer, int type, int piece) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("msg_type", Integer.valueOf(type));
        map.put("piece", Integer.valueOf(piece));
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_METADATA).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no metadata capability
            //if (log.shouldLog(Log.INFO))
            //    log.info("Metadata send req msg exception to " + peer, e);
        }
    }

    private static void sendPiece(Peer peer, int piece, byte[] data, int totalSize) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("msg_type", Integer.valueOf(TYPE_DATA));
        map.put("piece", Integer.valueOf(piece));
        // BEP 9
        // "This key has the same semantics as the 'metadata_size' in the extension header"
        // which apparently means the same value. Fixed in 0.9.21.
        //map.put("total_size", Integer.valueOf(data.length));
        map.put("total_size", Integer.valueOf(totalSize));
        byte[] dict = BEncoder.bencode(map);
        byte[] payload = new byte[dict.length + data.length];
        System.arraycopy(dict, 0, payload, 0, dict.length);
        System.arraycopy(data, 0, payload, dict.length, data.length);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_METADATA).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no metadata caps
            //if (log.shouldLog(Log.INFO))
            //    log.info("Metadata send piece msg exception to " + peer, e);
        }
    }

    private static final int HASH_LENGTH = 32;

    /**
     * Can't find a published standard for this anywhere.
     * See the libtorrent code.
     * Here we use the "added" key as a single string of concatenated
     * 32-byte peer hashes.
     * added.f and dropped unsupported
     * @since 0.8.4
     */
    private static void handlePEX(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldLog(Log.DEBUG))
            log.debug("Got PEX msg from " + peer);
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            bev = map.get("added");
            if (bev == null)
                return;
            byte[] ids = bev.getBytes();
            if (ids.length < HASH_LENGTH)
                return;
            int len = Math.min(ids.length, (I2PSnarkUtil.MAX_CONNECTIONS - 1) * HASH_LENGTH);
            List<PeerID> peers = new ArrayList<PeerID>(len / HASH_LENGTH);
            for (int off = 0; off < len; off += HASH_LENGTH) {
                byte[] hash = new byte[HASH_LENGTH];
                System.arraycopy(ids, off, hash, 0, HASH_LENGTH);
                if (DataHelper.eq(hash, peer.getPeerID().getDestHash()))
                    continue;
                PeerID pID = new PeerID(hash, listener.getUtil());
                peers.add(pID);
            }
            // could include ourselves, listener must remove
            listener.gotPeers(peer, peers);
        } catch (Exception e) {
            if (log.shouldLog(Log.INFO))
                log.info("PEX msg exception from " + peer, e);
            //peer.disconnect(false);
        }
    }

    /**
     * Receive the DHT port numbers
     * @since DHT
     */
    private static void handleDHT(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldLog(Log.DEBUG))
            log.debug("Got DHT msg from " + peer);
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int qport = map.get("port").getInt();
            int rport = map.get("rport").getInt();
            listener.gotPort(peer, qport, rport);
        } catch (Exception e) {
            if (log.shouldLog(Log.INFO))
                log.info("DHT msg exception from " + peer, e);
            //peer.disconnect(false);
        }
    }

    /**
     * added.f and dropped unsupported
     * @param pList non-null
     * @since 0.8.4
     */
    public static void sendPEX(Peer peer, List<Peer> pList) {
        if (pList.isEmpty())
            return;
        Map<String, Object> map = new HashMap<String, Object>();
        byte[] peers = new byte[HASH_LENGTH * pList.size()];
        int off = 0;
        for (Peer p : pList) {
            System.arraycopy(p.getPeerID().getDestHash(), 0, peers, off, HASH_LENGTH);
            off += HASH_LENGTH;
        }
        map.put("added", peers);
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_PEX).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no PEX caps
            //if (log.shouldLog(Log.INFO))
            //    log.info("PEX msg exception to " + peer, e);
        }
    }

    /**
     *  Send the DHT port numbers
     *  @since DHT
     */
    public static void sendDHT(Peer peer, int qport, int rport) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("port", Integer.valueOf(qport));
        map.put("rport", Integer.valueOf(rport));
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_DHT).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no DHT caps
            //if (log.shouldLog(Log.INFO))
            //    log.info("DHT msg exception to " + peer, e);
        }
    }

    /**
     * Handle comment request and response
     *
     * Ref: https://blinkenlights.ch/ccms/software/bittorrent.html
     * Ref: https://github.com/adrian-bl/bitflu/blob/3cb7fe887dbdea8132e4fa36fbbf5f26cf992db3/plugins/Bitflu/20_DownloadBitTorrent.pm#L3403
     * @since 0.9.31
     */
    private static void handleComment(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldLog(Log.DEBUG))
            log.debug("Got comment msg from " + peer);
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int type = map.get("msg_type").getInt();
            if (type == 0) {
                // request
                int num = 20;
                BEValue b = map.get("num");
                if (b != null)
                    num = b.getInt();
                listener.gotCommentReq(peer, num);
            } else if (type == 1) {
                // response
                List<BEValue> list = map.get("comments").getList();
                if (list.isEmpty())
                    return;
                List<Comment> comments = new ArrayList<Comment>(list.size());
                long now = I2PAppContext.getGlobalContext().clock().now();
                for (BEValue li : list) {
                     Map<String, BEValue> m = li.getMap();
                     String owner = m.get("owner").getString();
                     String text = m.get("text").getString();
                     // 0-5 range for rating is enforced by Comment constructor
                     int rating = m.get("like").getInt();
                     long time = now - (Math.max(0, m.get("timestamp").getInt()) * 1000L);
                     Comment c = new Comment(text, owner, rating, time, false);
                     comments.add(c);
                }
                listener.gotComments(peer, comments);
            } else {
                if (log.shouldLog(Log.INFO))
                    log.info("Unknown comment msg type " + type + " from " + peer);
            }
        } catch (Exception e) {
            if (log.shouldLog(Log.INFO))
                log.info("Comment msg exception from " + peer, e);
            //peer.disconnect(false);
        }
    }

    private static final byte[] COMMENTS_FILTER = new byte[64];

    /**
     *  Send comment request
     *  @since 0.9.31
     */
    public static void sendCommentReq(Peer peer, int num) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("msg_type", Integer.valueOf(0));
        map.put("num", Integer.valueOf(num));
        map.put("filter", COMMENTS_FILTER);
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_COMMENT).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no caps
        }
    }

    /**
     *  Send comments
     *  Caller must sync on comments
     *  @param num max to send
     *  @param comments non-null
     *  @since 0.9.31
     */
    public static void locked_sendComments(Peer peer, int num, CommentSet comments) {
        int toSend = Math.min(num, comments.size());
        if (toSend <= 0)
            return;
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("msg_type", Integer.valueOf(1));
        List<Object> lc = new ArrayList<Object>(toSend);
        long now = I2PAppContext.getGlobalContext().clock().now();
        int i = 0;
        for (Comment c : comments) {
            if (i++ >= toSend)
                break;
            Map<String, Object> mc = new HashMap<String, Object>();
            String s = c.getName();
            mc.put("owner", s != null ? s : "");
            s = c.getText();
            mc.put("text", s != null ? s : "");
            mc.put("like", Integer.valueOf(c.getRating()));
            mc.put("timestamp", Long.valueOf((now - c.getTime()) / 1000L));
            lc.add(mc);
        }
        map.put("comments", lc);
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_COMMENT).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no caps
        }
    }
}
