package net.i2p.data.i2cp;

/*
 * public domain
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Tell the other side the limits
 *
 * @author zzz
 */
public class BandwidthLimitsMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 23;
    private static final int LIMITS = 16;
    private int[] data;

    public BandwidthLimitsMessage() {
        super();
        data = new int[LIMITS];
    }

    /**
     * Let's define it this way.
     * Leave some extra. This is only local and rarely sent so we don't care about waste.
     *
     * 0) Client inbound limit (KBps)
     * 1) Client outbound limit (KBps)
     * 2) Router inbound limit (KBps)
     * 3) Router inbound burst limit (KBps)
     * 4) Router outbound limit (KBps)
     * 5) Router outbound burst limit (KBps)
     * 6) Router burst time (seconds)
     * 7-15) undefined
     */
    public BandwidthLimitsMessage(int in, int out) {
        this();
        data[0] = in;
        data[1] = out;
    }

    public int[] getLimits() {
        return data;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            for (int i = 0; i < LIMITS; i++) {
                data[i] = (int) DataHelper.readLong(in, 4);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            for (int i = 0; i < LIMITS; i++) {
                DataHelper.writeLong(os, 4, data[i]);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof BandwidthLimitsMessage)) {
            BandwidthLimitsMessage msg = (BandwidthLimitsMessage) object;
            return DataHelper.eq(data, msg.getLimits());
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[BandwidthLimitsMessage");
        buf.append("\n\tIn: ").append(data[0]);
        buf.append("\n\tOut: ").append(data[1]);
        buf.append("]");
        return buf.toString();
    }
}
