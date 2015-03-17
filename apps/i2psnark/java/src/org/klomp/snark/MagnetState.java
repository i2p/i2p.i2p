package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.RandomSource;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;

/**
 * Simple state for the download of the metainfo, shared between
 * Peer and ExtensionHandler.
 *
 * Nothing is synchronized here!
 * Caller must synchronize on this for everything!
 *
 * Reference: BEP 9
 *
 * @since 0.8.4
 * author zzz
 */
class MagnetState {
    public static final int CHUNK_SIZE = 16*1024;

    private final byte[] infohash;
    private boolean complete;
    /** if false, nothing below is valid */
    private boolean isInitialized;

    private int metaSize;
    private int totalChunks;
    /** bitfield for the metainfo chunks - will remain null if we start out complete */
    private BitField requested;
    private BitField have;
    /** bitfield for the metainfo */
    private byte[] metainfoBytes;
    /** only valid when finished */
    private MetaInfo metainfo;

    /**
     *  @param meta null for new magnet
     */
    public MagnetState(byte[] iHash, MetaInfo meta) {
        infohash = iHash;
        if (meta != null) {
            metainfo = meta;
            initialize(meta.getInfoBytes().length);
            complete = true;
        }
    }

    /**
     *  Call this for a new magnet when you have the size
     *  @throws IllegalArgumentException
     */
    public void initialize(int size) {
        if (isInitialized)
            throw new IllegalArgumentException("already set");
        isInitialized = true;
        metaSize = size;
        totalChunks = (size + (CHUNK_SIZE - 1)) / CHUNK_SIZE;
        if (metainfo != null) {
            metainfoBytes = metainfo.getInfoBytes();
        } else {
            // we don't need these if complete
            have = new BitField(totalChunks);
            requested = new BitField(totalChunks);
            metainfoBytes = new byte[metaSize];
        }
    }

    /**
     *  Call this for a new magnet when the download is complete.
     *  @throws IllegalArgumentException
     */
    public void setMetaInfo(MetaInfo meta) {
        metainfo = meta;
    }

    /**
     *  @throws IllegalArgumentException
     */
    public MetaInfo getMetaInfo() {
        if (!complete)
            throw new IllegalArgumentException("not complete");
        return metainfo;
    }

    /**
     *  @throws IllegalArgumentException
     */
    public int getSize() {
        if (!isInitialized)
            throw new IllegalArgumentException("not initialized");
        return metaSize;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public boolean isComplete() {
        return complete;
    }

    public int chunkSize(int chunk) {
        return Math.min(CHUNK_SIZE, metaSize - (chunk * CHUNK_SIZE));
    }

    /** @return chunk count */
    public int chunksRemaining() {
        if (!isInitialized)
            throw new IllegalArgumentException("not initialized");
        if (complete)
            return 0;
        return totalChunks - have.count();
    }

    /** @return chunk number */
    public int getNextRequest() {
        if (!isInitialized)
            throw new IllegalArgumentException("not initialized");
        if (complete)
            throw new IllegalArgumentException("complete");
        int rand = RandomSource.getInstance().nextInt(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            int chk = (i + rand) % totalChunks; 
            if (!(have.get(chk) || requested.get(chk))) {
                requested.set(chk);
                return chk; 
            }
        }
        // all requested - end game
        for (int i = 0; i < totalChunks; i++) {
            int chk = (i + rand) % totalChunks; 
            if (!have.get(chk))
                return chk; 
        }
        throw new IllegalArgumentException("complete");
    }

    /**
     *  @throws IllegalArgumentException
     */
    public byte[] getChunk(int chunk) {
        if (!complete)
            throw new IllegalArgumentException("not complete");
        if (chunk < 0 || chunk >= totalChunks)
            throw new IllegalArgumentException("bad chunk number");
        int size = chunkSize(chunk);
        byte[] rv = new byte[size];
        System.arraycopy(metainfoBytes, chunk * CHUNK_SIZE, rv, 0, size);
        // use meta.getInfoBytes() so we don't save it in memory
        return rv;
    }

    /**
     *  @return true if this was the last piece
     *  @throws NPE, IllegalArgumentException, IOException, ...
     */
    public boolean saveChunk(int chunk, byte[] data, int off, int length) throws Exception {
        if (!isInitialized)
            throw new IllegalArgumentException("not initialized");
        if (chunk < 0 || chunk >= totalChunks)
            throw new IllegalArgumentException("bad chunk number");
        if (have.get(chunk))
            return false;  // shouldn't happen if synced
        int size = chunkSize(chunk);
        if (size != length)
            throw new IllegalArgumentException("bad chunk length");
        System.arraycopy(data, off, metainfoBytes, chunk * CHUNK_SIZE, size);
        have.set(chunk);
        boolean done = have.complete();
        if (done) {
            metainfo = buildMetaInfo();
            complete = true;
        }
        return done;
    }

    /**
     *  @return true if this was the last piece
     *  @throws NPE, IllegalArgumentException, IOException, ...
     */
    public MetaInfo buildMetaInfo() throws Exception {
        // top map has nothing in it but the info map (no announce)
        Map<String, BEValue> map = new HashMap();
        InputStream is = new ByteArrayInputStream(metainfoBytes);
        BDecoder dec = new BDecoder(is);
        BEValue bev = dec.bdecodeMap();
        map.put("info", bev);
        MetaInfo newmeta = new MetaInfo(map);
        if (!DataHelper.eq(newmeta.getInfoHash(), infohash)) {
            // Disaster. Start over. ExtensionHandler will catch
            // the IOE and disconnect the peer, hopefully we will
            // find a new peer.
            // TODO: Count fails and give up eventually
            have = new BitField(totalChunks);
            requested = new BitField(totalChunks);
            throw new IOException("info hash mismatch");
        }
        return newmeta;
    }
}
