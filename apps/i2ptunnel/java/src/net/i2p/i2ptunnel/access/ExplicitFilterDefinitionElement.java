package net.i2p.i2ptunnel.access;

import java.util.Map;

class ExplicitFilterDefinitionElement extends FilterDefinitionElement {

    private final String b32;

    ExplicitFilterDefinitionElement(String b32, Threshold threshold) {
        super(threshold);
        this.b32 = b32;
    }

    @Override
    public void update(Map<String, DestTracker> map) {
        if (map.containsKey(b32))
            return;
        map.put(b32, new DestTracker(b32, threshold));
    }
}
