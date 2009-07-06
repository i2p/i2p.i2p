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

import net.i2p.util.Log;

/**
 * Request the other side to send us what they think the current time is
 *
 */
public class GetDateMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(GetDateMessage.class);
    public final static int MESSAGE_TYPE = 32;

    public GetDateMessage() {
        super();
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        // noop
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        byte rv[] = new byte[0];
        return rv;
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof GetDateMessage)) {
            return true;
        }
        
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[GetDateMessage]");
        return buf.toString();
    }
}