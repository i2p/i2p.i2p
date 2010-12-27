package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * REF: BEP 10 Extension Protocol
 * @since 0.8.2
 * @author zzz
 */
abstract class ExtensionHandler {

    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(ExtensionHandler.class);

    public static final int ID_METADATA = 3;
    private static final String TYPE_METADATA = "ut_metadata";
    /** Pieces * SHA1 Hash length, + 25% extra for file names, benconding overhead, etc */
    private static final int MAX_METADATA_SIZE = Storage.MAX_PIECES * 20 * 5 / 4;
    private static final int PARALLEL_REQUESTS = 3;


  /**
   *  @param metasize -1 if unknown
   *  @return bencoded outgoing handshake message
   */
    public static byte[] getHandshake(int metasize) {
        Map<String, Object> handshake = new HashMap();
        Map<String, Integer> m = new HashMap();
        m.put(TYPE_METADATA, Integer.valueOf(ID_METADATA));
        if (metasize >= 0)
            handshake.put("metadata_size", Integer.valueOf(metasize));
        handshake.put("m", m);
        handshake.put("p", Integer.valueOf(6881));
        handshake.put("v", "I2PSnark");
        handshake.put("reqq", Integer.valueOf(5));
        return BEncoder.bencode(handshake);
    }

    public static void handleMessage(Peer peer, PeerListener listener, int id, byte[] bs) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Got extension msg " + id + " length " + bs.length + " from " + peer);
        if (id == 0)
            handleHandshake(peer, listener, bs);
        else if (id == ID_METADATA)
            handleMetadata(peer, listener, bs);
        else if (_log.shouldLog(Log.INFO))
            _log.info("Unknown extension msg " + id + " from " + peer);
    }

    private static void handleHandshake(Peer peer, PeerListener listener, byte[] bs) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Got handshake msg from " + peer);
        try {
            // this throws NPE on missing keys
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            peer.setHandshakeMap(map);
            Map<String, BEValue> msgmap = map.get("m").getMap();

            if (msgmap.get(TYPE_METADATA) == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.debug("Peer does not support metadata extension: " + peer);
                // drop if we need metainfo ?
                return;
            }

            BEValue msize = map.get("metadata_size");
            if (msize == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.debug("Peer does not have the metainfo size yet: " + peer);
                // drop if we need metainfo ?
                return;
            }
            int metaSize = msize.getInt();
            if (_log.shouldLog(Log.WARN))
                _log.debug("Got the metainfo size: " + metaSize);

            MagnetState state = peer.getMagnetState();
            int remaining;
            synchronized(state) {
                if (state.isComplete())
                    return;

                if (state.isInitialized()) {
                    if (state.getSize() != metaSize) {
                        if (_log.shouldLog(Log.WARN))
                            _log.debug("Wrong metainfo size " + metaSize + " from: " + peer);
                        peer.disconnect();
                        return;
                    }
                } else {
                    // initialize it
                    if (metaSize > MAX_METADATA_SIZE) {
                        if (_log.shouldLog(Log.WARN))
                            _log.debug("Huge metainfo size " + metaSize + " from: " + peer);
                        peer.disconnect(false);
                        return;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Initialized state, metadata size = " + metaSize + " from " + peer);
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
                if (_log.shouldLog(Log.INFO))
                    _log.info("Request chunk " + chk + " from " + peer);
                sendRequest(peer, chk);
            }
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Handshake exception from " + peer, e);
        }
    }

    private static final int TYPE_REQUEST = 0;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_REJECT = 2;

    private static final int CHUNK_SIZE = 16*1024;

    /**
     * REF: BEP 9
     * @since 0.8.4
     */
    private static void handleMetadata(Peer peer, PeerListener listener, byte[] bs) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Got metadata msg from " + peer);
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int type = map.get("msg_type").getInt();
            int piece = map.get("piece").getInt();

            MagnetState state = peer.getMagnetState();
            if (type == TYPE_REQUEST) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Got request for " + piece + " from: " + peer);
                byte[] pc;
                synchronized(state) {
                    pc = state.getChunk(piece);
                }
                sendPiece(peer, piece, pc);
                // Do this here because PeerConnectionOut only reports for PIECE messages
                peer.uploaded(pc.length);
                listener.uploaded(peer, pc.length);
            } else if (type == TYPE_DATA) {
                int size = map.get("total_size").getInt();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Got data for " + piece + " length " + size + " from: " + peer);
                boolean done;
                int chk = -1;
                synchronized(state) {
                    if (state.isComplete())
                        return;
                    int len = is.available();
                    if (len != size) {
                        // probably fatal
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("total_size " + size + " but avail data " + len);
                    }
                    peer.downloaded(len);
                    listener.downloaded(peer, len);
                    done = state.saveChunk(piece, bs, bs.length - len, len);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Got chunk " + piece + " from " + peer);
                    if (!done)
                        chk = state.getNextRequest();
                }
                // out of the lock
                if (done) {
                    // Done!
                    // PeerState will call the listener (peer coord), who will
                    // check to see if the MagnetState has it
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Got last chunk from " + peer);
                } else {
                    // get the next chunk
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Request chunk " + chk + " from " + peer);
                    sendRequest(peer, chk);
                }
            } else if (type == TYPE_REJECT) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Got reject msg from " + peer);
                peer.disconnect(false);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Got unknown metadata msg from " + peer);
                peer.disconnect(false);
            }
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.info("Metadata ext. msg. exception from " + peer, e);
            peer.disconnect(false);
        }
    }

    private static void sendRequest(Peer peer, int piece) {
        sendMessage(peer, TYPE_REQUEST, piece);
    }

    private static void sendReject(Peer peer, int piece) {
        sendMessage(peer, TYPE_REJECT, piece);
    }

    /** REQUEST and REJECT are the same except for message type */
    private static void sendMessage(Peer peer, int type, int piece) {
        Map<String, Object> map = new HashMap();
        map.put("msg_type", Integer.valueOf(type));
        map.put("piece", Integer.valueOf(piece));
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_METADATA).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no metadata capability
            if (_log.shouldLog(Log.WARN))
                _log.info("Metadata send req msg exception to " + peer, e);
        }
    }

    private static void sendPiece(Peer peer, int piece, byte[] data) {
        Map<String, Object> map = new HashMap();
        map.put("msg_type", Integer.valueOf(TYPE_DATA));
        map.put("piece", Integer.valueOf(piece));
        map.put("total_size", Integer.valueOf(data.length));
        byte[] dict = BEncoder.bencode(map);
        byte[] payload = new byte[dict.length + data.length];
        System.arraycopy(dict, 0, payload, 0, dict.length);
        System.arraycopy(data, 0, payload, dict.length, data.length);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_METADATA).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no metadata caps
            if (_log.shouldLog(Log.WARN))
                _log.info("Metadata send piece msg exception to " + peer, e);
        }
    }
}
