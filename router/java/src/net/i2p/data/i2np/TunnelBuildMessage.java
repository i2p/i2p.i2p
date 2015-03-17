package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 *  The basic build message with 8 records.
 */
public class TunnelBuildMessage extends TunnelBuildMessageBase {

    public static final int MESSAGE_TYPE = 21;

    public TunnelBuildMessage(I2PAppContext context) {
        super(context, MAX_RECORD_COUNT);
    }

    /** @since 0.7.12 */
    protected TunnelBuildMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    public int getType() { return MESSAGE_TYPE; }

    @Override
    public String toString() {
        return "[TunnelBuildMessage]";
    }
}
