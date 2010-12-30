package net.i2p.data.i2cp;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Request the router look up the dest for a hash
 */
public class DestLookupMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 34;
    private Hash _hash;

    public DestLookupMessage() {
        super();
    }

    public DestLookupMessage(Hash h) {
        _hash = h;
    }

    public Hash getHash() {
        return _hash;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        //Hash h = new Hash();
        try {
            //h.readBytes(in);
            _hash = Hash.create(in);
        //} catch (DataFormatException dfe) {
        } catch (IllegalArgumentException dfe) {
            throw new I2CPMessageException("Unable to load the hash", dfe);
        }
        //_hash = h;
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_hash == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(Hash.HASH_LENGTH);
        try {
            _hash.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the hash", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof DestLookupMessage)) {
            DestLookupMessage msg = (DestLookupMessage) object;
            return DataHelper.eq(getHash(), msg.getHash());
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DestLookupMessage: ");
        buf.append("\n\tHash: ").append(_hash);
        buf.append("]");
        return buf.toString();
    }
}
