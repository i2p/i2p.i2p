package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.  
 */

import net.i2p.data.SimpleDataStructure;

/**
 * 48 byte hash
 *
 * @since 0.9.8
 */
public class Hash384 extends SimpleDataStructure {

    public final static int HASH_LENGTH = 48;
    
    public Hash384() {
        super();
    }

    /** @throws IllegalArgumentException if data is not correct length (null is ok) */
    public Hash384(byte data[]) {
        super(data);
    }

    public int length() {
        return HASH_LENGTH;
    }
}
