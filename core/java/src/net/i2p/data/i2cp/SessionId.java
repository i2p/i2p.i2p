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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;

/**
 * Defines the token passed between the router and client to associate messages
 * with a particular session.  These IDs are not globally unique.
 *
 * @author jrandom
 */
public class SessionId extends DataStructureImpl {
    private int _sessionId;

    public SessionId() {
        _sessionId = -1;
    }

    public int getSessionId() {
        return _sessionId;
    }

    public void setSessionId(int id) {
        _sessionId = id;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _sessionId = (int) DataHelper.readLong(in, 2);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_sessionId < 0) throw new DataFormatException("Invalid session ID: " + _sessionId);
        DataHelper.writeLong(out, 2, _sessionId);
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SessionId)) return false;
        return _sessionId == ((SessionId) obj).getSessionId();
    }

    @Override
    public int hashCode() {
        return _sessionId;
    }

    @Override
    public String toString() {
        return "[SessionId: " + _sessionId + "]";
    }
}
