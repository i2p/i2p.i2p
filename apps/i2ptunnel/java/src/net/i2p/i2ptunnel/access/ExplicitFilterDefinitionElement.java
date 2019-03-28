package net.i2p.i2ptunnel.access;

import java.util.Map;

import net.i2p.data.Hash;

/**
 * A filter definition element that includes a single, explicitly defined 
 * remote destination
 *
 * @since 0.9.40
 */
class ExplicitFilterDefinitionElement extends FilterDefinitionElement {

    private final Hash hash;

    /**
     * @param b32 A string with the .b32 representation of the remote destination
     * @param threshold threshold to apply to that destination
     * @throws InvalidDefinitionException if the b32 string is not valid b32
     */
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
