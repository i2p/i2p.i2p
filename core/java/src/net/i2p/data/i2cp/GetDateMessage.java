package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Request the other side to send us what they think the current time is/
 * Only supported from client to router.
 *
 * Since 0.8.7, optionally include a version string.
 */
public class GetDateMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 32;
    private String _version;

    public GetDateMessage() {
        super();
    }

    /**
     *  @param version the client's version String to be sent to the router; may be null
     *  @since 0.8.7
     */
    public GetDateMessage(String version) {
        super();
        _version = version;
    }

    /**
     *  @return may be null
     *  @since 0.8.7
     */
    public String getVersion() {
        return _version;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        if (size > 0) {
            try {
                _version = DataHelper.readString(in);
            } catch (DataFormatException dfe) {
                throw new I2CPMessageException("Bad version string", dfe);
            }
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_version == null)
            return new byte[0];
        ByteArrayOutputStream os = new ByteArrayOutputStream(16);
        try {
            DataHelper.writeString(os, _version);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public int hashCode() {
        return MESSAGE_TYPE ^ DataHelper.hashCode(_version);
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof GetDateMessage)) {
            return DataHelper.eq(_version, ((GetDateMessage)object)._version);
        }
        
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[GetDateMessage]");
        buf.append("\n\tVersion: ").append(_version);
        return buf.toString();
    }
}
