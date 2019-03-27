package net.i2p.i2ptunnel.access;

import java.io.File;

class Recorder {

    private final File file;
    private final Threshold threshold;

    Recorder(File file, Threshold threshold) {
        this.file = file;
        this.threshold = threshold;
    }

    File getFile() {
        return file;
    }

    Threshold getThreshold() {
        return threshold;
    }
}
