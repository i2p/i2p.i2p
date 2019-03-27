package net.i2p.i2ptunnel.access;

import java.util.Map;
import java.io.IOException;

abstract class FilterDefinitionElement {

    protected final Threshold threshold;

    FilterDefinitionElement(Threshold threshold) {
        this.threshold = threshold;
    }

    abstract void update(Map<String, DestTracker> map) throws IOException;

    Threshold getThreshold() {
        return threshold;
    }
}
