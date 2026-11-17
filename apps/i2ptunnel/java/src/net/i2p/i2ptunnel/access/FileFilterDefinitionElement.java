package net.i2p.i2ptunnel.access;

import java.util.HashMap;
import java.util.Map;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * An element of filter definition that reads hashes of remote destinations
 * from a file.
 *
 * @since 0.9.40
 */
class FileFilterDefinitionElement extends FilterDefinitionElement {

    private final File file;
    private final Map<Hash, DestTracker> lastLoaded = new HashMap<>();
    private long lastLoading = Long.MIN_VALUE;

    /**
     * @param file file to read the remote destinations from
     * @param threshold threshold to apply to all those destinations
     */
    FileFilterDefinitionElement(File file, Threshold threshold) {
        super(threshold);
        this.file = file;
    }

    @Override
    public synchronized void update(Map<Hash, DestTracker> map) throws IOException {
        if (!(file.exists() && file.isFile())) {
            // A recorder's list may not exist at startup. Once loaded, keep the
            // previous policy if a file disappears during replacement or a disk error.
            if (lastLoading != Long.MIN_VALUE)
                throw new IOException("Access list disappeared: " + file);
            return;
        }
        long modified = file.lastModified();
        if (modified != lastLoading) {
            Map<Hash, DestTracker> loaded = new HashMap<Hash, DestTracker>();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            try {
                String b32;
                while ((b32 = reader.readLine()) != null) {
                    if (b32.length() == 0)
                        continue;
                    Hash hash;
                    try {
                        hash = fromBase32(b32);
                    } catch (InvalidDefinitionException bad32) {
                        Log log = I2PAppContext.getGlobalContext().logManager().getLog(FileFilterDefinitionElement.class);
                        log.error("Invalid access list entry \"" + b32 + "\" in " + file, bad32);
                        continue;
                    }
                    // Cache the complete file, including entries shadowed by an
                    // earlier rule. They may become effective on a later reload.
                    if (!loaded.containsKey(hash))
                        loaded.put(hash, new DestTracker(hash, threshold));
                }
            } finally {
                reader.close();
            }
            // Publish only after a complete, successful read and close.
            lastLoaded.clear();
            lastLoaded.putAll(loaded);
            lastLoading = modified;
        }
        for (Map.Entry<Hash, DestTracker> entry : lastLoaded.entrySet()) {
            if (!map.containsKey(entry.getKey()))
                map.put(entry.getKey(), entry.getValue());
        }
    }
}
