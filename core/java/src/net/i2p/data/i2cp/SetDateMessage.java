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
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Clock;

/**
 * Tell the other side what time it is.
 * Only supported from router to client.
 *
 * Since 0.8.7, optionally include a version string.
 */
public class SetDateMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 33;
    private Date _date;
    private String _version;

    public SetDateMessage() {
        super();
        _date = new Date(Clock.getInstance().now());
    }

    /**
     *  @param version the router's version String to be sent to the client; may be null
     *  @since 0.8.7
     */
    public SetDateMessage(String version) {
        this();
        _version = version;
    }

    public Date getDate() {
        return _date;
    }

    public void setDate(Date date) {
        _date = date;
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
        try {
            _date = DataHelper.readDate(in);
            if (size > DataHelper.DATE_LENGTH)
                _version = DataHelper.readString(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_date == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
            DataHelper.writeDate(os, _date);
            if (_version != null)
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
        return MESSAGE_TYPE ^ DataHelper.hashCode(_version) ^ DataHelper.hashCode(_date);
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof SetDateMessage)) {
            SetDateMessage msg = (SetDateMessage) object;
            return DataHelper.eq(_date, msg._date) && DataHelper.eq(_version, msg._version);
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[SetDateMessage");
        buf.append("\n\tDate: ").append(_date);
        buf.append("\n\tVersion: ").append(_version);
        buf.append("]");
        return buf.toString();
    }
}
