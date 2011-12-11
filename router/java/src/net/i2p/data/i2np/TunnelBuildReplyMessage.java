package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  The basic build reply message with 8 records.
 * Transmitted from the new outbound endpoint to the creator through a
 * reply tunnel
 */
public class TunnelBuildReplyMessage extends TunnelBuildMessageBase {

    public static final int MESSAGE_TYPE = 22;

    public TunnelBuildReplyMessage(I2PAppContext context) {
        super(context, MAX_RECORD_COUNT);
    }

    /** @since 0.7.12 */
    protected TunnelBuildReplyMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    public int getType() { return MESSAGE_TYPE; }

    @Override
    public String toString() {
        return "[TunnelBuildReplyMessage]";
    }
}
