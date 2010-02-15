package net.i2p.data.i2np;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 *
 */
public class TunnelBuildMessage extends I2NPMessageImpl {
    protected ByteArray _records[];
    protected int RECORD_COUNT;
    public static final int MAX_RECORD_COUNT = 8;
    
    public static final int MESSAGE_TYPE = 21;

    public TunnelBuildMessage(I2PAppContext context) {
        this(context, MAX_RECORD_COUNT);
    }

    /** @since 0.7.10 */
    protected TunnelBuildMessage(I2PAppContext context, int records) {
        super(context);
        if (records > 0) {
            RECORD_COUNT = records;
            _records = new ByteArray[records];
        }
        // else will be initialized by readMessage() in VTBM
    }

    public void setRecord(int index, ByteArray record) { _records[index] = record; }
    public ByteArray getRecord(int index) { return _records[index]; }
    /** @since 0.7.10 */
    public int getRecordCount() { return RECORD_COUNT; }
    
    public static final int RECORD_SIZE = 512+16;
    
    protected int calculateWrittenLength() { return RECORD_SIZE * RECORD_COUNT; }
    public int getType() { return MESSAGE_TYPE; }
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) 
            throw new I2NPMessageException("Message type is incorrect for this message");
        if (dataSize != calculateWrittenLength()) 
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        
        for (int i = 0; i < RECORD_COUNT; i++) {
            int off = offset + (i * RECORD_SIZE);
            byte rec[] = new byte[RECORD_SIZE];
            System.arraycopy(data, off, rec, 0, RECORD_SIZE);
            setRecord(i, new ByteArray(rec)); //new ByteArray(data, off, len));
        }
    }
    
    protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
        int remaining = out.length - (curIndex + calculateWrittenLength());
        if (remaining < 0)
            throw new I2NPMessageException("Not large enough (too short by " + remaining + ")");
        for (int i = 0; i < RECORD_COUNT; i++) {
            System.arraycopy(_records[i].getData(), _records[i].getOffset(), out, curIndex, RECORD_SIZE);
            curIndex += RECORD_SIZE;
        }
        return curIndex;
    }

    @Override
    public String toString() {
        return "[TunnelBuildMessage]";
    }
}
