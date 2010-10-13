package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
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
 * Defines the structure for why abuse was reported either by the client to
 * the router or by the router to the client
 *
 * @author jrandom
 */
public class AbuseReason extends DataStructureImpl {
    private String _reason;

    public AbuseReason() {
    }

    public String getReason() {
        return _reason;
    }

    public void setReason(String reason) {
        _reason = reason;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _reason = DataHelper.readString(in);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_reason == null) throw new DataFormatException("Invalid abuse reason");
        DataHelper.writeString(out, _reason);
    }

    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof AbuseReason)) return false;
        return DataHelper.eq(_reason, ((AbuseReason) object).getReason());
    }

    @Override
    public int hashCode() {
        return DataHelper.hashCode(_reason);
    }

    @Override
    public String toString() {
        return "[AbuseReason: " + _reason + "]";
    }
}
