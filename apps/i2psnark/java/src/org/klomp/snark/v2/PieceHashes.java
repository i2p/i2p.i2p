package org.klomp.snark.v2;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Process the file tree, and present it as a list of hashes.
 *
 * @since 0.9.71
 */
public class PieceHashes extends AbstractList<MerkleHash> {

    private final int _size;
    private final List<MerkleHash> _filehashes;
    private final List<Long> _filelengths;
    private final PieceLayers _layers;
    private final int _plen;

    public PieceHashes(int hashcount, List<MerkleHash> hashes, List<Long> lengths,
                       PieceLayers layers, int piece_length) throws InvalidBEncodingException {
        super();
        _size = hashcount;
        _filehashes = hashes;
        _filelengths = lengths;
        _layers = layers;
        _plen = piece_length;
        //test();
    }

    public int size() { return _size; }

    public MerkleHash get(int index) {
        if (index < 0 || index >= _size)
            throw new IndexOutOfBoundsException();
        int off = 0;
        for (int i = 0; i < _filehashes.size(); i++) {
            MerkleHash h = _filehashes.get(i);
            if (h == null) {
                // padding file
                continue;
            }
            long len = _filelengths.get(i).longValue();
            int pcs = (int) ((len - 1) / _plen) + 1;
            if (index >= off + pcs) {
                off += pcs;
                continue;
            }
            if (len <= _plen)
                return h;
            List<MerkleHash> phashes = _layers.get(h);
            if (phashes == null)
                throw new IllegalStateException("Missing hashes for file " + i);
            return phashes.get(index - off);
        }
        throw new IllegalStateException("Ran off the end? index " + index);
    }

    // TODO
    //public Iterator<MerkleHash> iterator() {
    //}

/*
    private void test() throws InvalidBEncodingException {
        List<MerkleHash> tst = getPieceHashList();
        if (_size != tst.size())
            throw new InvalidBEncodingException("Fail size mismatch " + _size + ' ' + tst.size());
        for (int i = 0; i < _size; i++) {
            if (!tst.get(i).equals(get(i)))
                throw new InvalidBEncodingException("Fail at index " + i);
        }
    }
*/

    /**
     *  @param hashes one per file, from the file tree
     *  @param lengths one per file, from the file tree
     *  @throws InvalidBEncodingException on missing hashes in PieceLayers
     */
/*
    private List<MerkleHash> getPieceHashList() throws InvalidBEncodingException {
        List<MerkleHash> rv = new ArrayList<MerkleHash>(_size);
        for (int i = 0; i < _filehashes.size(); i++) {
            MerkleHash h = _filehashes.get(i);
            long len = _filelengths.get(i).longValue();
            if (len > _plen) {
                List<MerkleHash> phashes = _layers.get(h);
                if (phashes == null)
                    throw new InvalidBEncodingException("Missing hashes for file " + i);
                for (MerkleHash h2 : phashes) {
                    rv.add(h2);
                }
            } else if (h != null) {
                rv.add(h);
            } else {
                // padding file
            }
        }
        if (rv.size() != _size)
            throw new InvalidBEncodingException("piece count " + _size + " generated " + rv.size());
        return rv;
    }
*/
}
