package net.i2p.data.i2np;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * Transmitted from the new outbound endpoint to the creator through a
 * reply tunnel.
 * Variable size, small records.
 * Preliminary, see proposal 157.
 *
 * @since 0.9.49
 */
public class ShortTunnelBuildReplyMessage extends TunnelBuildReplyMessage {
    public static final int MESSAGE_TYPE = 26;
    public static final int SHORT_RECORD_SIZE = ShortTunnelBuildMessage.SHORT_RECORD_SIZE;

    /** zero record count, will be set with readMessage() */
    public ShortTunnelBuildReplyMessage(I2PAppContext context) {
        super(context, 0);
    }

    public ShortTunnelBuildReplyMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    @Override
    protected int calculateWrittenLength() { return 1 + super.calculateWrittenLength(); }

    @Override
    public int getType() { return MESSAGE_TYPE; }

    @Override
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) 
            throw new I2NPMessageException("Message type is incorrect for this message");
        int r = data[offset] & 0xff;
        if (r <= 0 || r > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + r);
        RECORD_COUNT = r;
        if (dataSize != calculateWrittenLength()) 
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        _records = new EncryptedBuildRecord[RECORD_COUNT];
        offset++;
        for (int i = 0; i < RECORD_COUNT; i++) {
            byte rec[] = new byte[SHORT_RECORD_SIZE];
            System.arraycopy(data, offset, rec, 0, SHORT_RECORD_SIZE);
            setRecord(i, new ShortEncryptedBuildRecord(rec));
            offset += SHORT_RECORD_SIZE;
        }
    }
    
    @Override
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        if (RECORD_COUNT <= 0 || RECORD_COUNT > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + RECORD_COUNT);
        out[curIndex++] = (byte) RECORD_COUNT;
        for (int i = 0; i < RECORD_COUNT; i++) {
            System.arraycopy(_records[i].getData(), 0, out, curIndex, SHORT_RECORD_SIZE);
            curIndex += SHORT_RECORD_SIZE;
        }
        return curIndex;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[ShortTunnelBuildReplyMessage: " +
                   "\n\tRecords: ").append(getRecordCount())
           .append(']');
        return buf.toString();
    }
}
