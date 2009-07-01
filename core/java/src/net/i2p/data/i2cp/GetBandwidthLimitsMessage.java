package net.i2p.data.i2cp;

/*
 * public domain
 *
 */

import java.io.IOException;
import java.io.InputStream;

import net.i2p.util.Log;

/**
 * Request the router tells us the current bw limits
 *
 * @author zzz
 */
public class GetBandwidthLimitsMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(GetBandwidthLimitsMessage.class);
    public final static int MESSAGE_TYPE = 8;

    public GetBandwidthLimitsMessage() {
        super();
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        // noop
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        byte rv[] = new byte[0];
        return rv;
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof GetBandwidthLimitsMessage)) {
            return true;
        }
        
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[GetBandwidthLimitsMessage]");
        return buf.toString();
    }
}
