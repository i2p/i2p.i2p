package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.RandomSource;

public class SessionTag extends ByteArray {
    public final static int BYTE_LENGTH = 32;

    public SessionTag() {
        super();
    }

    public SessionTag(boolean create) {
        super();
        if (create) {
            byte buf[] = new byte[BYTE_LENGTH];
            RandomSource.getInstance().nextBytes(buf);
            setData(buf);
        }
    }

    public SessionTag(byte val[]) {
        super();
        setData(val);
    }

    public void setData(byte val[]) throws IllegalArgumentException {
        if (val == null) super.setData(null);
        if (val.length != BYTE_LENGTH)
            throw new IllegalArgumentException("SessionTags must be " + BYTE_LENGTH + " bytes");
        super.setData(val);
    }
}