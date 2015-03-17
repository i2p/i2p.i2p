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
 * Provides a severity level (larger numbers are more severe) in association with
 * a client reporting abusive behavior to the router or the router reporting it
 * to the client
 *
 * @author jrandom
 */
public class AbuseSeverity extends DataStructureImpl {
    private int _severityId;

    public AbuseSeverity() {
        _severityId = -1;
    }

    public int getSeverity() {
        return _severityId;
    }

    public void setSeverity(int id) {
        _severityId = id;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _severityId = (int) DataHelper.readLong(in, 1);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_severityId < 0) throw new DataFormatException("Invalid abuse severity: " + _severityId);
        DataHelper.writeLong(out, 1, _severityId);
    }

    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof AbuseSeverity)) return false;
        return _severityId == ((AbuseSeverity) object).getSeverity();
    }

    @Override
    public int hashCode() {
        return _severityId;
    }

    @Override
    public String toString() {
        return "[AbuseSeverity: " + _severityId + "]";
    }
}
