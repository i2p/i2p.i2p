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
import net.i2p.util.Log;

/**
 * Defines the message ID of a message delivered between a router and a client
 * in a particular session.  These IDs are not globally unique.
 *
 * @author jrandom
 */
public class MessageId extends DataStructureImpl {
    private final static Log _log = new Log(MessageId.class);
    private int _messageId;

    public MessageId() {
        setMessageId(-1);
    }

    public int getMessageId() {
        return _messageId;
    }

    public void setMessageId(int id) {
        _messageId = id;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _messageId = (int) DataHelper.readLong(in, 4);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_messageId < 0) throw new DataFormatException("Invalid message ID: " + _messageId);
        DataHelper.writeLong(out, 4, _messageId);
    }

    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof MessageId)) return false;
        return DataHelper.eq(getMessageId(), ((MessageId) object).getMessageId());
    }

    public int hashCode() {
        return getMessageId();
    }

    public String toString() {
        return "[MessageId: " + getMessageId() + "]";
    }
}