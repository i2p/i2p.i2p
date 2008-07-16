package net.i2p.data.i2np;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 * Transmitted from the new outbound endpoint to the creator through a
 * reply tunnel
 */
public class TunnelBuildReplyMessage extends I2NPMessageImpl {
    private ByteArray _records[];
    
    public static final int MESSAGE_TYPE = 22;
    public static final int RECORD_COUNT = TunnelBuildMessage.RECORD_COUNT;

    public TunnelBuildReplyMessage(I2PAppContext context) {
        super(context);
        _records = new ByteArray[RECORD_COUNT];
    }

    public void setRecord(int index, ByteArray record) { _records[index] = record; }
    public ByteArray getRecord(int index) { return _records[index]; }
    
    public static final int RECORD_SIZE = TunnelBuildMessage.RECORD_SIZE;
    
    protected int calculateWrittenLength() { return RECORD_SIZE * RECORD_COUNT; }
    public int getType() { return MESSAGE_TYPE; }
    public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) 
            throw new I2NPMessageException("Message type is incorrect for this message");
        if (dataSize != calculateWrittenLength()) 
            throw new I2NPMessageException("Wrong length (expects " + calculateWrittenLength() + ", recv " + dataSize + ")");
        
        for (int i = 0; i < RECORD_COUNT; i++) {
            int off = offset + (i * RECORD_SIZE);
            int len = RECORD_SIZE;
            byte rec[] = new byte[RECORD_SIZE];
            System.arraycopy(data, off, rec, 0, RECORD_SIZE);
            setRecord(i, new ByteArray(rec));
            //setRecord(i, new ByteArray(data, off, len));
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
}
