package net.i2p.data.i2np;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Sent from the OBEP to the tunnel creator via an inbound tunnel.
 * Contains one plaintext variable-sized reply record for the creator
 * and a variable number of encrypted records for the following hops.
 * This message must be garlic-encrypted to hide the contents from the OBGW.
 *
 * Preliminary, see proposal 157.
 *
 * @since 0.9.50
 */
public class OutboundTunnelBuildReplyMessage extends TunnelBuildReplyMessage {
    public static final int MESSAGE_TYPE = 26;
    public static final int SHORT_RECORD_SIZE = ShortTunnelBuildMessage.SHORT_RECORD_SIZE;
    public static final int MAX_PLAINTEXT_RECORD_SIZE = 172;

    private int _plaintextSlot = -1;
    private byte[] _plaintextRecord;

    /** zero record count, will be set with readMessage() */
    public OutboundTunnelBuildReplyMessage(I2PAppContext context) {
        super(context, 0);
    }

    public OutboundTunnelBuildReplyMessage(I2PAppContext context, int records) {
        super(context, records);
    }

    /**
     *  @param record must be ShortEncryptedBuildRecord or null
     *  @throws IllegalArgumentException on bad slot or record length.
     */
    @Override
    public void setRecord(int index, EncryptedBuildRecord record) {
        if (record != null && (record.length() != SHORT_RECORD_SIZE || index == _plaintextSlot))
            throw new IllegalArgumentException();
        super.setRecord(index, record);
    }

    /**
     *  Set the slot and data for the plaintext record.
     *  Empty properties will be used.
     *
     *  @param reply 0-255
     *  @throws IllegalArgumentException on bad slot or data length.
     *  @since 0.9.51
     */
    public void setPlaintextRecord(int slot, int reply) {
        // 00 00 reply
        byte[] data = new byte[3];
        data[2] = (byte) reply;
        setPlaintextRecord(slot, data);
    }

    /**
     *  Set the slot and data for the plaintext record.
     *
     *  @param reply 0-255
     *  @param props may be null
     *  @throws IllegalArgumentException on bad slot or data length.
     *  @since 0.9.51
     */
    public void setPlaintextRecord(int slot, int reply, Properties props) throws DataFormatException {
        if (props == null || props.isEmpty()) {
            setPlaintextRecord(slot, reply);
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataHelper.writeProperties(baos, props);
        } catch (IOException ioe) {}
        baos.write((byte) reply);
        setPlaintextRecord(slot, baos.toByteArray());
    }

    /**
     *  Set the slot and data for the plaintext record.
     *  @throws IllegalArgumentException on bad slot or data length.
     */
    public void setPlaintextRecord(int slot, byte[] data) {
        if (slot < 0 || slot >= RECORD_COUNT || data.length == 0 || data.length > MAX_PLAINTEXT_RECORD_SIZE ||
            (_records != null && _records[slot] != null))
            throw new IllegalArgumentException();
        _plaintextSlot = slot;
        _plaintextRecord = data;
    }

    /**
     *  Get the slot for the plaintext record.
     *  getRecord() for this slot will return null.
     */
    public int getPlaintextSlot() {
        return _plaintextSlot;
    }

    /**
     *  Get the data for the plaintext record.
     */
    public byte[] getPlaintextRecord() {
        return _plaintextRecord;
    }

    /**
     *  Get the data for the plaintext record.
     *  @since 0.9.51
     */
    public int getPlaintextReply() {
        return _plaintextRecord[_plaintextRecord.length - 1] & 0xff;
    }

    /**
     *  Get the data for the plaintext record.
     *  @since 0.9.51
     */
    public Properties getPlaintextOptions() throws DataFormatException {
        Properties props = new Properties();
        DataHelper.fromProperties(_plaintextRecord, 0, props);
        return props;
    }

    @Override
    protected int calculateWrittenLength() {
        if (_plaintextRecord == null)
            throw new IllegalStateException("Plaintext record not set");
        return 4 + _plaintextRecord.length + ((RECORD_COUNT - 1) * SHORT_RECORD_SIZE);
    }

    @Override
    public int getType() { return MESSAGE_TYPE; }

    @Override
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) 
            throw new I2NPMessageException("Message type is incorrect for this message");
        int r = data[offset++] & 0xff;
        if (r <= 0 || r > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + r);
        RECORD_COUNT = r;
        _plaintextSlot = data[offset++] & 0xff;
        if (_plaintextSlot < 0 || _plaintextSlot >= r)
            throw new I2NPMessageException("Bad slot " + _plaintextSlot);
        int size = (int) DataHelper.fromLong(data, offset, 2);
        if (size <= 0 || size > MAX_PLAINTEXT_RECORD_SIZE)
            throw new I2NPMessageException("Bad size " + size);
        offset += 2;
        _plaintextRecord = new byte[size];
        System.arraycopy(data, offset, _plaintextRecord, 0, size);
        offset += size;

        if (dataSize != calculateWrittenLength()) 
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        _records = new EncryptedBuildRecord[RECORD_COUNT];
        for (int i = 0; i < RECORD_COUNT; i++) {
            if (i == _plaintextSlot)
                continue;
            byte rec[] = new byte[SHORT_RECORD_SIZE];
            System.arraycopy(data, offset, rec, 0, SHORT_RECORD_SIZE);
            setRecord(i, new ShortEncryptedBuildRecord(rec));
            offset += SHORT_RECORD_SIZE;
        }
    }
    
    @Override
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        if (_plaintextRecord == null)
            throw new I2NPMessageException("Plaintext record not set");
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        if (RECORD_COUNT <= 0 || RECORD_COUNT > MAX_RECORD_COUNT)
            throw new I2NPMessageException("Bad record count " + RECORD_COUNT);
        out[curIndex++] = (byte) RECORD_COUNT;
        out[curIndex++] = (byte) _plaintextSlot;
        DataHelper.toLong(out, curIndex, 2, _plaintextRecord.length);
        curIndex += 2;
        System.arraycopy(_plaintextRecord, 0, out, curIndex, _plaintextRecord.length);
        curIndex += _plaintextRecord.length;
        for (int i = 0; i < RECORD_COUNT; i++) {
            if (i == _plaintextSlot)
                continue;
            System.arraycopy(_records[i].getData(), 0, out, curIndex, SHORT_RECORD_SIZE);
            curIndex += SHORT_RECORD_SIZE;
        }
        return curIndex;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[OutboundTunnelBuildReplyMessage: " +
                   "\n\tID: ").append(getUniqueId())
           .append("\n\tRecords: ").append(getRecordCount())
           .append(']');
        return buf.toString();
    }
}
