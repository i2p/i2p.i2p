package org.klomp.snark.v2;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataHelper;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.Storage;

/**
 * Merkle utilities
 *
 * @since 0.9.71
 */
public class V2Util {

    public static final int MIN = 16*1024;

    /**
     *  Merkle hashes for empty data of sizes 16KB, 32KB, 64KB, ...
     *  Index is Integer.numberOfTrailingZeros(size) - 14,
     *  or the height of the layer in the Merkle tree.
     */
    private static final List<MerkleHash> fakeMerkleHashes = new ArrayList<MerkleHash>();

    /**
     *  16K min and power of two
     *  Storage.MAX_PIECE_SIZE is checked elsewhere but do it here also.
     */
    public static boolean isValidPieceLength(int plen) {
        return plen >= MIN &&
               //plen <= Storage.MAX_PIECE_SIZE &&
               (plen & (plen - 1)) == 0;  // isPowerOfTwo
    }

    /**
     *
     *  @param pieces two minimum
     *  @param plen piece length, 16K minimum, power of two
     */
    public static boolean checkMerkle(MerkleHash root, List<MerkleHash> pieces, int plen) {
        if (!isValidPieceLength(plen))
            throw new IllegalArgumentException("Bad piece length " + plen);
        int sz = pieces.size();
        if (sz < 2)
            return false;
        return root.equals(calculateMerkleRoot(pieces, plen));
    }

    /**
     *
     *  @param pieces two minimum
     *  @param plen piece length, 16K minimum, power of two
     */
    public static MerkleHash calculateMerkleRoot(List<MerkleHash> pieces, int plen) {
        if (!isValidPieceLength(plen))
            throw new IllegalArgumentException("Bad piece length " + plen);
        CalcMerkleRoot merk = new CalcMerkleRoot(pieces, plen);
        return merk.calc();
    }

    /**
     *  @return the root hash
     */
    public static MerkleHash calculateMerkleRoot(File f) throws IOException {
        long len = f.length();
        if (len == 0)
            return emptyMerkleHash(MIN);
        if (len > Integer.MAX_VALUE / 2)
            throw new UnsupportedOperationException();  // TODO
        int plen;
        if (len < MIN) {
            plen = MIN;
        } else if ((len & (len - 1)) == 0) {
            // isPowerOfTwo
            plen = (int) len;
        } else {
            // next power of two
            // so calculateMerkleFull() only returns one value
            // to reduce memory usage,
            // and we don't exceed the merkle tree size required,
            // which results in bad results
            // next power of two
            plen = (int) (Long.highestOneBit(len) << 1);
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            if (len > 8 * MIN)
                in = new BufferedInputStream(in, 4 * MIN);
            return calculateMerkleRoot(in, plen);
        } finally {
            if (in != null)
                try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Warn, will return bad value if plen is greater than the next power
     *  of two of the input size
     *
     *  @param plen piece length, 16K minimum, power of two
     *  @return the root hash
     */
    private static MerkleHash calculateMerkleRoot(InputStream in, int plen) throws IOException {
        List<MerkleHash> hashes = calculateMerklePieces(in, plen);
        if (hashes.size() == 1)
            return hashes.get(0);
        return calculateMerkleRoot(hashes, plen);
    }

    /**
     *  @param plen will use only if smaller than file size
     *  @return the piece hashes
     */
    public static List<MerkleHash> calculateMerklePieces(File f, int plen) throws IOException {
        long len = f.length();
        if (len == 0)
            return Collections.singletonList(emptyMerkleHash(MIN));
        if (len > Integer.MAX_VALUE / 2)
            throw new UnsupportedOperationException();  // TODO
        if (len < MIN) {
            plen = MIN;
        } else if ((len & (len - 1)) == 0) {
            // isPowerOfTwo
            if (len < plen)
                plen = (int) len;
        } else {
            // next power of two
            // so calculateMerkleFull() only returns one value
            // to reduce memory usage,
            // and we don't exceed the merkle tree size required,
            // which results in bad results
            // next power of two
            int nplen = (int) (Long.highestOneBit(len) << 1);
            if (nplen < plen)
                plen = nplen;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            if (len > 8 * MIN)
                in = new BufferedInputStream(in, 4 * MIN);
            return calculateMerklePieces(in, plen);
        } finally {
            if (in != null)
                try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Warn, will return bad value if plen is greater than the next power
     *  of two of the input size
     *
     *  @param plen piece length, 16K minimum, power of two
     *  @return the piece hashes
     */
    private static List<MerkleHash> calculateMerklePieces(InputStream in, int plen) throws IOException {
        if (!isValidPieceLength(plen))
            throw new IllegalArgumentException("Bad piece length " + plen);
        CalcMerklePieceHashes calc = new CalcMerklePieceHashes(in, plen);
        try {
            return calc.calc();
        } finally {
            try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Calculate the piece hashes from an input stream
     */
    private static class CalcMerklePieceHashes {
        private final InputStream in;
        private final List<MerkleHash> rv;
        private final int plen;
        private final SHA256Generator sha;
        private final MessageDigest md;
        private final byte[] buf;

        /**
         *  Warn, will return bad value if plen is greater than the next power
         *  of two of the input size
         *
         *  Caller must close stream after calc()
         *  @param pclen piece length, 16K minimum, power of two
         */
        public CalcMerklePieceHashes(InputStream in, int pclen) {
            this.in = in;
            plen = pclen;
            rv = new ArrayList<MerkleHash>(plen >> 14);
            sha = SHA256Generator.getInstance();
            md = sha.acquire();
            buf = new byte[16384];
        }

        public List<MerkleHash> calc() throws IOException {
            boolean done;
            do {
                done = calculateMerkleFull(plen);
            } while (!done);
            sha.release(md);
            return rv;
        }

        /**
         *  Recursive.
         *  Will add ONE hash to rv if clen == plen.
         *  Call repeatedly until returns true.
         *  @param clen chunk length, 16K minimum, power of two
         *  @return true when done
         */
        private boolean calculateMerkleFull(int clen) throws IOException {
            if (clen == MIN) {
                // push hash on stack whether we read something or not
                int len = read16K();
                if (len == 0) {
                    rv.add(emptyMerkleHash(MIN));
                    return true;
                }
                byte[] h = new byte[32];
                // "The last block may be shorter than 16KB"
                // The hash is over the actual data only, NOT padded to 16K
                sha.calculateHash(buf, 0, len, h, 0);
                //System.out.println("Add hash of data length " + len + " " + net.i2p.data.Base64.encode(h));
                rv.add(new MerkleHash(h));
                return len < MIN;
            } else {
                clen /= 2;
                // calculate left side
                boolean done = calculateMerkleFull(clen);
                byte[] h1 = rv.get(rv.size() - 1).getData();
                byte[] h2;
                boolean done2;
                if (done) {
                    // skip the right side
                    done2 = true;
                    h2 = emptyMerkleHash(clen).getData();
                } else {
                    // calculate right side
                    done2 = calculateMerkleFull(clen);
                    h2 = rv.get(rv.size() - 1).getData();
                }
                if (clen < plen) {
                    // pop off the chunk hashes,
                    // but only if we are at a layer smaller than the piece size
                    rv.remove(rv.size() - 1);
                    if (!done)
                        rv.remove(rv.size() - 1);
                }
                md.update(h1);
                byte[] h = md.digest(h2);
                //System.out.println("Merge two hashes at level " + (clen * 2) + " done? " + done2 + " " + net.i2p.data.Base64.encode(h));
                rv.add(new MerkleHash(h));
                return done2;
            }
        }
    
        /**
         *  Read in to buf
         *  @return amount read, 0-16384
         */
        private int read16K() throws IOException {
            int cur = 0;
            do {
                int numRead = in.read(buf, cur, MIN - cur);
                if (numRead == -1)
                    break;
                cur += numRead;
            } while (cur < MIN);
            return cur;
        }
    }

    /**
     *  Calculate the Merkle root hash over a list of hashes (piece hashes)
     */
    private static class CalcMerkleRoot {
        private final List<MerkleHash> pieces;
        private final int plen;
        private final SHA256Generator sha;
        private final MessageDigest md;

        /**
         *  @param pclen piece length, 16K minimum, power of two
         */
        public CalcMerkleRoot(List<MerkleHash> pcs, int pclen) {
            pieces = pcs;
            plen = pclen;
            sha = SHA256Generator.getInstance();
            md = sha.acquire();
        }

        public MerkleHash calc() {
            int sz = pieces.size();
            if (sz < 1)
                throw new IllegalArgumentException("no pieces");
            if (sz < 2)
                return pieces.get(0);
            // next power of two
            int po2 = Integer.highestOneBit(sz);
            if (po2 != sz)
                po2 *= 2;
            MerkleHash rv = new MerkleHash(merkle(0, po2));
            sha.release(md);
            return rv;
        }

        /**
         *  Recursive.
         *  @param off offset into pieces
         *  @param len number of pieces, two minimum, power of two
         */
        private byte[] merkle(int off, int len) {
            if (off >= pieces.size())
                return emptyMerkleHash(plen * len).getData();
            byte[] h1;
            byte[] h2;
            if (len == 2) {
                h1 = pieces.get(off).getData();
                off++;
                if (off < pieces.size()) {
                    h2 = pieces.get(off).getData();
                } else {
                    h2 = emptyMerkleHash(plen).getData();
                }
            } else {
                len /= 2;
                h1 = merkle(off, len);
                h2 = merkle(off + len, len);
            }
            md.update(h1);
            return md.digest(h2);
        }
    }

    /**
     *  Calculate, if we haven't calculated it already.
     *  @param plen piece length, 16K minimum, power of two
     */
    private synchronized static MerkleHash emptyMerkleHash(int plen) {
        if (!isValidPieceLength(plen))
            throw new IllegalArgumentException("Bad piece length " + plen);
        int idx = Integer.numberOfTrailingZeros(plen) - 14;
        if (fakeMerkleHashes.isEmpty()) {
            // "The remaining leaf hashes beyond the end of the file required to
            //  construct the upper layers of the merkle tree are set to zero."
            // 16 KB
            //System.out.println("Empty hash at level 0 is " + MerkleHash.FAKE_HASH.toBase64());
            fakeMerkleHashes.add(MerkleHash.FAKE_HASH);
        }
        while (idx >= fakeMerkleHashes.size()) {
            byte[] h = fakeMerkleHashes.get(fakeMerkleHashes.size() - 1).getData();
            SHA256Generator sha = SHA256Generator.getInstance();
            MessageDigest md = sha.acquire();
            md.update(h);
            byte[] rv = md.digest(h);
            sha.release(md);
            fakeMerkleHashes.add(new MerkleHash(rv));
            //System.out.println("Empty hash at level " + (fakeMerkleHashes.size() - 1) + " is " + net.i2p.data.Base64.encode(rv));
        }
        return fakeMerkleHashes.get(idx);
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: V2Util file [piecelength (KB)]");
            System.exit(1);
        }
        File f = new File(args[0]);
        long len = f.length();
        System.out.println("File:         " + args[0]);

        int pclen;
        if (args.length > 1) {
            pclen = Integer.parseInt(args[1]) * 1024;
        } else {
            long po2 = Long.highestOneBit(len);
            if (po2 != len)
                po2 *= 2;
            if (po2 < MIN)
                po2 = MIN;
            if (po2 > Storage.MAX_PIECE_SIZE)
                po2 = Storage.MAX_PIECE_SIZE;
            pclen = (int) po2;
        }

        MerkleHash h;
        if (args.length > 1) {
            System.out.println("File length:  " + len);
            List<MerkleHash> hs = calculateMerklePieces(f, pclen);
            int pcs = (int) ((len - 1) / pclen) + 1;
            System.out.println("Pieces:       " + pcs);
            System.out.println("Piece hashes: " + hs.size());
            for (int i = 0; i < hs.size(); i++) {
                System.out.println("    " + i + "    " + I2PSnarkUtil.toHex(hs.get(i).getData()));
            }
            h = calculateMerkleRoot(hs, pclen);
        } else {
            h = calculateMerkleRoot(f);
            System.out.println("File length:  " + len);
        }
        System.out.println("Root hash:    " + I2PSnarkUtil.toHex(h.getData()));
    }
}
