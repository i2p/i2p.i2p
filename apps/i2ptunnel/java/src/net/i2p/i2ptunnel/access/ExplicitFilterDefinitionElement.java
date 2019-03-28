package net.i2p.i2ptunnel.access;

import java.util.Map;

import net.i2p.data.Hash;

class ExplicitFilterDefinitionElement extends FilterDefinitionElement {

    private final Hash hash;

    ExplicitFilterDefinitionElement(String b32, Threshold threshold) throws InvalidDefinitionException {
        super(threshold);
        this.hash = fromBase32(b32);
    }

    @Override
    public void update(Map<Hash, DestTracker> map) {
        if (map.containsKey(hash))
            return;
        map.put(hash, new DestTracker(hash, threshold));
    }
}
