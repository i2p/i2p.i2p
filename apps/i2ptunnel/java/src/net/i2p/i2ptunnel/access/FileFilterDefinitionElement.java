package net.i2p.i2ptunnel.access;

import java.util.Map;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import net.i2p.data.Hash;

/**
 * An element of filter definition that reads hashes of remote destinations
 * from a file.
 *
 * @since 0.9.40
 */
class FileFilterDefinitionElement extends FilterDefinitionElement {

    private final File file;

    /**
     * @param file file to read the remote destinations from
     * @param threshold threshold to apply to all those destinations
     */
    FileFilterDefinitionElement(File file, Threshold threshold) {
        super(threshold);
        this.file = file;
    }

    @Override
    public void update(Map<Hash, DestTracker> map) throws IOException {
        if (!(file.exists() && file.isFile()))
            return;
        BufferedReader reader = null; 
        try {
            reader = new BufferedReader(new FileReader(file)); 
            String b32;
            while((b32 = reader.readLine()) != null) {
                Hash hash = fromBase32(b32);
                if (map.containsKey(hash))
                    continue;
                map.put(hash, new DestTracker(hash, threshold));
            }
        } catch (InvalidDefinitionException bad32) {
            throw new IOException("invalid access list entry", bad32);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }
}
