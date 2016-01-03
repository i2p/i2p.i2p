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
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DateAndFlags;

/**
 * Same as SendMessageMessage, but with an expiration to be passed to the router
 *
 * As of 0.8.4, retrofitted to use DateAndFlags. Backwards compatible.
 *
 * @author zzz
 */
public class SendMessageExpiresMessage extends SendMessageMessage {
    /* FIXME hides another field FIXME */
    public final static int MESSAGE_TYPE = 36;
    private final DateAndFlags _daf;

    public SendMessageExpiresMessage() {
        this(new DateAndFlags());
    }

    /** @since 0.9.2 */
    public SendMessageExpiresMessage(DateAndFlags options) {
        super();
        _daf = options;
    }

    /**
     *  The Date object is created here, it is not cached.
     *  Use getExpirationTime() if you only need the long value.
     */
    public Date getExpiration() {
        return _daf.getDate();
    }

    /**
     *  Use this instead of getExpiration().getTime()
     *  @since 0.8.4
     */
    public long getExpirationTime() {
        return _daf.getTime();
    }

    public void setExpiration(Date d) {
        _daf.setDate(d);
    }

    /**
     *  @since 0.8.4
     */
    public void setExpiration(long d) {
        _daf.setDate(d);
    }

    /**
     *  @since 0.8.4
     */
    public int getFlags() {
        return _daf.getFlags();
    }

    /**
     *  @since 0.8.4
     */
    public void setFlags(int f) {
        _daf.setFlags(f);
    }

    /**
     * Read the body into the data structures
     *
     * @throws IOException 
     */
    @Override
    public void readMessage(InputStream in, int length, int type) throws I2CPMessageException, IOException {
        super.readMessage(in, length, type);

        try {
            _daf.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     * Write out the full message to the stream, including the 4 byte size and 1 
     * byte type header.  Override the parent so we can be more mem efficient
     *
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if (_sessionId == null)
            throw new I2CPMessageException("No session ID");
        if (_destination == null)
            throw new I2CPMessageException("No dest");
        if (_payload == null)
            throw new I2CPMessageException("No payload");
        if (_nonce < 0)
            throw new I2CPMessageException("No nonce");
        int len = 2 + _destination.size() + _payload.getSize() + 4 + 4 + DataHelper.DATE_LENGTH;
        
        try {
            DataHelper.writeLong(out, 4, len);
            out.write((byte) MESSAGE_TYPE);
            _sessionId.writeBytes(out);
            _destination.writeBytes(out);
            _payload.writeBytes(out);
            DataHelper.writeLong(out, 4, _nonce);
            _daf.writeBytes(out);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing the msg", dfe);
        }
    }
    
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[SendMessageExpiresMessage: ");
        buf.append("\n\tSessionId: ").append(_sessionId);
        buf.append("\n\tNonce: ").append(_nonce);
        buf.append("\n\tDestination: ").append(_destination);
        buf.append("\n\tExpiration: ").append(getExpiration());
        buf.append("\n\tPayload: ").append(_payload);
        buf.append("]");
        return buf.toString();
    }
}
