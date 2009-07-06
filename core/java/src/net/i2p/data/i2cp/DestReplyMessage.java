package net.i2p.data.i2cp;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

/**
 * Response to DestLookupMessage
 *
 */
public class DestReplyMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 35;
    private Destination _dest;

    public DestReplyMessage() {
        super();
    }

    public DestReplyMessage(Destination d) {
        _dest = d;
    }

    public Destination getDestination() {
        return _dest;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            Destination d = new Destination();
            d.readBytes(in);
            _dest = d;
        } catch (DataFormatException dfe) {
            _dest = null; // null dest allowed
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_dest == null)
            return new byte[0];  // null response allowed
        ByteArrayOutputStream os = new ByteArrayOutputStream(_dest.size());
        try {
            _dest.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the dest", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    public boolean equals(Object object) {
        if ((object != null) && (object instanceof DestReplyMessage)) {
            DestReplyMessage msg = (DestReplyMessage) object;
            return DataHelper.eq(getDestination(), msg.getDestination());
        }
        return false;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DestReplyMessage: ");
        buf.append("\n\tDestination: ").append(_dest);
        buf.append("]");
        return buf.toString();
    }
}
