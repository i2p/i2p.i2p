package org.klomp.snark.dht;
/*
 *  GPLv2
 */

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;

/**
 *  Used for Both outgoing and incoming tokens
 *
 * @since 0.8.4
 * @author zzz
 */
public class Token extends ByteArray {

    private static final int MY_TOK_LEN = 8;
    private final long lastSeen;

    /** outgoing - generate a random token */
    public Token(I2PAppContext ctx) {
        super(null);
        byte[] data = new byte[MY_TOK_LEN];
        ctx.random().nextBytes(data);
        setData(data);
        lastSeen = ctx.clock().now();
    }

    /** incoming  - save the token (arbitrary length) */
    public Token(I2PAppContext ctx, byte[] data) {
        super(data);
        lastSeen = ctx.clock().now();
    }

    public long lastSeen() {
        return lastSeen;
    }
}
