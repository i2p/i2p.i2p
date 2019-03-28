package net.i2p.i2ptunnel.access;

import java.util.Map;
import java.io.IOException;

import net.i2p.data.Hash;
import net.i2p.data.Base32;

/**
 * Base class for elements found in filter definition files
 *
 * @since 0.9.40
 */
abstract class FilterDefinitionElement {

    protected final Threshold threshold;

    FilterDefinitionElement(Threshold threshold) {
        this.threshold = threshold;
    }

    /**
     * Updates the provided map with the hash(es) of remote destinations
     * mentioned in this element
     */
    abstract void update(Map<Hash, DestTracker> map) throws IOException;

    Threshold getThreshold() {
        return threshold;
    }

    /**
     * Utility method to create a Hash object from a .b32 string
     */
    protected static Hash fromBase32(String b32) throws InvalidDefinitionException {
        if (!b32.endsWith(".b32.i2p"))
            throw new InvalidDefinitionException("Invalid b32 " + b32);
        b32 = b32.substring(0, b32.length() - 8);
        return new Hash(Base32.decode(b32));
    }
}
