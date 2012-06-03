package org.klomp.snark.dht;
/*
 *  GPLv2
 */

import java.util.Date;

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
        setValid(MY_TOK_LEN);
        lastSeen = ctx.clock().now();
    }

    /** incoming  - save the token (arbitrary length) */
    public Token(I2PAppContext ctx, byte[] data) {
        super(data);
        lastSeen = ctx.clock().now();
    }

    /** incoming  - for lookup only, not storage, lastSeen is 0 */
    public Token(byte[] data) {
        super(data);
        lastSeen = 0;
    }

    public long lastSeen() {
        return lastSeen;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[Token: ");
        byte[] bs = getData();
        if (bs.length == 0) {
            buf.append("0 bytes");
        } else {
            buf.append(bs.length).append(" bytes: 0x");
            // backwards, but the same way BEValue does it
            for (int i = 0; i < bs.length; i++) {
                int b = bs[i] & 0xff;
                if (b < 16)
                    buf.append('0');
                buf.append(Integer.toHexString(b));
            }
        }
        if (lastSeen > 0)
            buf.append(" created ").append((new Date(lastSeen)).toString());
        buf.append(']');
        return buf.toString();
    }
}
