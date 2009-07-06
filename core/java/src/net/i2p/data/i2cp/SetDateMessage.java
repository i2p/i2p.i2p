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
import net.i2p.util.Log;

/**
 * Tell the other side what time it is
 *
 */
public class SetDateMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(SetDateMessage.class);
    public final static int MESSAGE_TYPE = 33;
    private Date _date;

    public SetDateMessage() {
        super();
        setDate(new Date(Clock.getInstance().now()));
    }

    public Date getDate() {
        return _date;
    }

    public void setDate(Date date) {
        _date = date;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _date = DataHelper.readDate(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_date == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            DataHelper.writeDate(os, _date);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof SetDateMessage)) {
            SetDateMessage msg = (SetDateMessage) object;
            return DataHelper.eq(getDate(), msg.getDate());
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[SetDateMessage");
        buf.append("\n\tDate: ").append(getDate());
        buf.append("]");
        return buf.toString();
    }
}