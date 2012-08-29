package org.klomp.snark.dht;
/*
 *  GPLv2
 */

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 *  Used for both incoming and outgoing message IDs
 *
 * @since 0.8.4
 * @author zzz
 */
class MsgID extends ByteArray {

    /** BEP 5: 2 bytes, incremented */
    private static final int MY_TOK_LEN = 8;
    private static final int MAX_TOK_LEN = 16;

    /** outgoing - generate a random ID */
    public MsgID(I2PAppContext ctx) {
        super(null);
        byte[] data = new byte[MY_TOK_LEN];
        ctx.random().nextBytes(data);
        setData(data);
        setValid(MY_TOK_LEN);
    }

    /** incoming  - save the ID (arbitrary length) */
    public MsgID(byte[] data) {
        super(data);
        // lets not get carried away
        if (data.length > MAX_TOK_LEN)
            throw new IllegalArgumentException();
    }
}
