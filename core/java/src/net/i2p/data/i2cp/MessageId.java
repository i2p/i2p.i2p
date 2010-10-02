package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;

/**
 * Defines the message ID of a message delivered between a router and a client
 * in a particular session.  These IDs are not globally unique.
 *
 * @author jrandom
 */
public class MessageId extends DataStructureImpl {
    private long _messageId;

    public MessageId() {
        _messageId = -1;
    }
    public MessageId(long id) {
        _messageId = id;
    }

    public long getMessageId() {
        return _messageId;
    }

    public void setMessageId(long id) {
        _messageId = id;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _messageId = DataHelper.readLong(in, 4);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_messageId < 0) throw new DataFormatException("Invalid message ID: " + _messageId);
        DataHelper.writeLong(out, 4, _messageId);
    }

    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof MessageId)) return false;
        return _messageId == ((MessageId) object).getMessageId();
    }

    @Override
    public int hashCode() {
        return (int)_messageId;
    }

    @Override
    public String toString() {
        return "[MessageId: " + _messageId + "]";
    }
}
