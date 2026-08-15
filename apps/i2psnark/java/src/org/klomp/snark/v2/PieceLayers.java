package org.klomp.snark.v2;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;

import org.klomp.snark.Storage;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * A map for the 'piece layers' dictionary,
 * since a standard map with a byte[] for keys is awkward in Java,
 * as is converting the byte arrays to strings.
 *
 * @since 0.9.71
 */
public class PieceLayers extends TreeMap<MerkleHash, List<MerkleHash>> {

    private static final Comparator<MerkleHash> COMP = new MerkleHashComparator();
    private static final byte[] THIRTYTWO = DataHelper.getASCII("32:");

    /**
     *  From BDecoder
     */
    public PieceLayers() {
        super(COMP);
    }

    /**
     *  From torrent creator
     *  Hashes are one big list
     *  Less efficient
     *
     *  @param hashes one per piece
     *  @param lengths one per file
     */
    public PieceLayers(List<MerkleHash> hashes, List<Long> lengths, int piece_length) throws InvalidBEncodingException {
        super(COMP);
        int idx = 0;
        for (Long l : lengths) {
            long len = l.longValue();
            if (len > piece_length) {
                int pcs = (int) ((len - 1) / piece_length) + 1;
                List<MerkleHash> file_hashes = hashes.subList(idx, idx + pcs);
                MerkleHash file_root_hash = V2Util.calculateMerkleRoot(file_hashes, piece_length);
                put(file_root_hash, file_hashes);
                idx += pcs;
            } else {
                idx++;
            }
        }
        if (idx != hashes.size())
            throw new InvalidBEncodingException("hash count mismatch");
        // comment out for production
        validate(piece_length);
    }

    /**
     *  From torrent creator
     *  Hashes are one list per file
     *  More efficient
     *
     *  @param hashes one per file
     *  @param lengths one per file
     */
    public PieceLayers(int piece_length, List<List<MerkleHash>> hashes, List<Long> lengths) throws InvalidBEncodingException {
        super(COMP);
        if (lengths.size() != hashes.size())
            throw new InvalidBEncodingException("hash count mismatch");
        int idx = 0;
        for (Long l : lengths) {
            long len = l.longValue();
            List<MerkleHash> file_hashes = hashes.get(idx);
            if (len > piece_length) {
                int pcs = (int) ((len - 1) / piece_length) + 1;
                if (pcs != file_hashes.size())
                    throw new InvalidBEncodingException("hash count mismatch for file " + idx +
                                                        " expected " + pcs + " got " + file_hashes.size() +
                                                        " for file length " + len + " piece length " + piece_length);
                MerkleHash file_root_hash = V2Util.calculateMerkleRoot(file_hashes, piece_length);
                put(file_root_hash, file_hashes);
            } else {
                if (file_hashes.size() != 1)
                    throw new InvalidBEncodingException("hash count mismatch for file " + idx +
                                                        " expected 1 got " + file_hashes.size() +
                                                        " for file length " + len + " piece length " + piece_length);
            }
            idx++;
        }
        // comment out for production
        validate(piece_length);
    }

    public BEValue bdecode(InputStream in) throws IOException {
        int d = in.read();
        if (d < 0)
            throw new EOFException();
        if (d != 'd')
            throw new InvalidBEncodingException("not a dictionary");
        while (true) {
            int len = readInt(in);
            if (len < 0)
                break;
            if (len != 32)
                throw new InvalidBEncodingException("bad piece layer key length " + len);
            byte[] h = new byte[32];
            DataHelper.read(in, h);
            MerkleHash k = new MerkleHash(h);
            len = readInt(in);
            // two hashes minimum
            if (len < 64 || (len & 0x1f) != 0 || len > 32 * Storage.MAX_PIECES)
                throw new InvalidBEncodingException("bad piece layer key length " + len);
            int cnt = len / 32;
            List<MerkleHash> v = new ArrayList<MerkleHash>(cnt);
            for (int i = 0; i < cnt; i++) {
                 h = new byte[32];
                 DataHelper.read(in, h);
                 v.add(new MerkleHash(h));
            }
            List<MerkleHash> old = put(k, v);
            if (old != null)
                throw new InvalidBEncodingException("dup key");
        }
        // uncomment after adding BEValue constructor
        // return new BEValue(this);
        return null;
    }

    public void bencode(OutputStream out) throws IOException {
        out.write('d');
        for (Map.Entry<MerkleHash, List<MerkleHash>> e : entrySet()) {
            MerkleHash k = e.getKey();
            out.write(THIRTYTWO);
            out.write(k.getData());
            List<MerkleHash> v = e.getValue();
            int sz = v.size();
            out.write(DataHelper.getASCII(Integer.toString(sz * 32)));
            out.write(':');
            for (int i = 0; i < sz; i++) {
                out.write(v.get(i).getData());
            }
        }
        out.write('e');
    }

    /**
     *  check that the merkles in the layers are correct
     */
    public void validate(int piece_length) throws InvalidBEncodingException {
        for (Map.Entry<MerkleHash, List<MerkleHash>> e : entrySet()) {
            MerkleHash root = e.getKey();
            List<MerkleHash> lh = e.getValue();
            if (!V2Util.checkMerkle(root, lh, piece_length))
                throw new InvalidBEncodingException("Bad layers");
        }
    }

    /**
     *  reads xxx:
     *  returns -1 on 'e'
     */
    private static int readInt(InputStream in) throws IOException {
        int rv = 0;
        int c = in.read();
        if (c == 'e')
            return -1;
        int i = c - '0';
        while (i >= 0 && i <= 9) {
            rv *= 10;
            rv += i;
            c = in.read();
            i = c - '0';
        }
        if (c != ':')
            throw new InvalidBEncodingException("Colon expected, not '" + (char)c + "'");
        return rv;
    }

    /**
     *  Sorts in true binary order, not Base64 string order, per BEP 52,
     *  and as required by libtorrent.
     *  Copied from RouterConsole
     */
    private static class MerkleHashComparator implements Comparator<MerkleHash>, Serializable {
    
        public int compare(MerkleHash l, MerkleHash r) {
            return DataHelper.compareTo(l.getData(), r.getData());
        }
    }
}
