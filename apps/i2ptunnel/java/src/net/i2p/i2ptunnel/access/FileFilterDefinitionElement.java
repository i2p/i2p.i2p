package net.i2p.i2ptunnel.access;

import java.util.Map;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

class FileFilterDefinitionElement extends FilterDefinitionElement {

    private final File file;

    FileFilterDefinitionElement(File file, Threshold threshold) {
        super(threshold);
        this.file = file;
    }

    @Override
    public void update(Map<String, DestTracker> map) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String b32;
            while((b32 = reader.readLine()) != null) {
                if (map.containsKey(b32))
                    continue;
                map.put(b32, new DestTracker(b32, threshold));
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }
}
