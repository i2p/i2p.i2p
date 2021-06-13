package net.i2p.data.i2np;

import net.i2p.I2PAppContext;

/**
 * Internal use only, to convert an inbound STBM to a reply.
 * Never serialized/deserialized/sent/received.
 * See BuildHandler and BuildReplyHandler.
 *
 * @since 0.9.51
 */
public class ShortTunnelBuildReplyMessage extends TunnelBuildReplyMessage {
    /**
     *  Impossible value, more than 1 byte
     */
    public static final int MESSAGE_TYPE = 999;
    public static final int SHORT_RECORD_SIZE = ShortTunnelBuildMessage.SHORT_RECORD_SIZE;

    public ShortTunnelBuildReplyMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    /**
     *  @param record must be ShortEncryptedBuildRecord or null
     */
    @Override
    public void setRecord(int index, EncryptedBuildRecord record) {
        if (record != null && record.length() != SHORT_RECORD_SIZE)
            throw new IllegalArgumentException();
        super.setRecord(index, record);
    }

    @Override
    protected int calculateWrittenLength() { return 0; }

    @Override
    public int getType() { return MESSAGE_TYPE; }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[ShortTunnelBuildReplyMessage: " +
                   "\n\tID: ").append(getUniqueId())
           .append("\n\tRecords: ").append(getRecordCount())
           .append(']');
        return buf.toString();
    }
}
