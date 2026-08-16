package org.klomp.snark.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Process the file tree.
 *
 * @since 0.9.71
 */
public class FileTree {

    private FileTree() {}

    /**
     * This adds padding files and their lengths.
     * Padding files will have a "p" attribute.
     * Padding files will have a null hash.
     *
     * @param tree the file tree, other five args are out parameters
     * @param hashes entries will be null for padding files and zero-length files
     * @param atts entries will be null if none
     */
    public static void addfiles(Map<String, BEValue> tree, int piece_length, List<List<String>> files, List<Long> lengths,
                                List<MerkleHash> hashes, List<String> base, List<String> atts,
                                boolean v2Only) throws InvalidBEncodingException {
        x_addfiles(tree, piece_length, files, lengths, hashes, base, atts, v2Only);
    }

    /**
     * Recursive
     * @param tree the file tree, other five args are out parameters
     * @param hashes entries will be null for padding files and zero-length files
     * @param atts entries will be null if none
     */
    private static void x_addfiles(Map<String, BEValue> tree, int piece_length, List<List<String>> files, List<Long> lengths,
                                   List<MerkleHash> hashes, List<String> base, List<String> atts,
                                   boolean v2Only) throws InvalidBEncodingException {
        // must be sorted
        List<String> keys = new ArrayList<String>(tree.keySet());
        if (keys.size() > 1)
            Collections.sort(keys);
        for (String name : keys) {
            BEValue bev = tree.get(name);
            if (name.equals("")) {
                if (base.isEmpty())
                    throw new InvalidBEncodingException("file at root");
                if (tree.size() > 1)
                    throw new InvalidBEncodingException("dir+file");
                Map<String, BEValue> props = bev.getMap();
                BEValue lngth = props.get("length");
                if (lngth == null)
                    throw new InvalidBEncodingException("Missing length number");
                long l = lngth.getLong();
                List<String> file = new ArrayList<String>(base);
                files.add(file);
                lengths.add(Long.valueOf(l));
                if (l < 0)
                    throw new InvalidBEncodingException("Negative file length");
                if (l != 0) {
                    BEValue h = props.get("pieces root");
                    if (h == null)
                        throw new InvalidBEncodingException("Missing hash");
                    byte[] hb = h.getBytes();
                    if (hb.length != 32)
                        throw new InvalidBEncodingException("bad hash");
                    hashes.add(new MerkleHash(hb));
                } else {
                    // dummy
                    hashes.add(null);
                }
                BEValue a = props.get("attr");
                if (a != null) {
                    atts.add(a.getString());
                } else {
                    atts.add(null);
                }
            } else {
                // go around again
                base.add(name);
                Map<String, BEValue> entries = bev.getMap();
                x_addfiles(entries, piece_length, files, lengths, hashes, base, atts, v2Only);
                base.remove(base.size() - 1);
            }
        }
    }

    /**
     * This adds padding files and their lengths and attributes
     * to the three List parameters.
     * Padding files will have a "p" attribute.
     *
     * This may add files with duplicate pad lengths and names;
     * clients are expected to not complain about dups.
     *
     * @param files in/out param
     * @param lengths in/out param
     * @param atts in/out param, must be empty, will be filled up with null or "p"
     * @return true if params were modified.
     */
    public static boolean addPaddingFiles(int piece_length, List<List<String>> files, List<Long> lengths,
                                          List<String> atts) {
        int len = lengths.size();
        if (len <= 1)
            return false;
        while (atts.size() < len) {
            atts.add(null);
        }
        int origLen = len;

        // Warning: i and len will be modified in loop
        // we never pad the last file, so limit is len - 1
        for (int i = 0; i < len - 1; i++) {
            long l = lengths.get(i).longValue();
            long rem = l % piece_length;
            if (rem != 0) {
                long pad = piece_length - rem;
                i++;
                List<String> file = new ArrayList<String>(2);
                file.add(".pad");
                file.add(Long.toString(pad));
                files.add(i, file);
                lengths.add(i, Long.valueOf(pad));
                atts.add(i, "p");
                len++;
            }
        }
        return origLen != len;
    }
}
