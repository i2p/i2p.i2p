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

    /**
     *  @param id 0-65535
     *  @since 0.9.11
     */
    public SessionId(int id) {
        if (id < 0 || id > 65535)
            throw new IllegalArgumentException();
        _sessionId = id;
    }

    public int getSessionId() {
        return _sessionId;
    }

    /**
     *  @param id 0-65535
     *  @throws IllegalArgumentException
     *  @throws IllegalStateException if already set
     */
    public void setSessionId(int id) {
        if (id < 0 || id > 65535)
            throw new IllegalArgumentException();
        if (_sessionId >= 0)
            throw new IllegalStateException();
        _sessionId = id;
    }

    /**
     *  @throws IllegalStateException if already set
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_sessionId >= 0)
            throw new IllegalStateException();
        _sessionId = (int) DataHelper.readLong(in, 2);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_sessionId < 0) throw new DataFormatException("Invalid session ID: " + _sessionId);
        DataHelper.writeLong(out, 2, _sessionId);
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SessionId)) return false;
        return _sessionId == ((SessionId) obj)._sessionId;
    }

    @Override
    public int hashCode() {
        return 777 * _sessionId;
    }

    @Override
    public String toString() {
        return "[SessionId: " + _sessionId + "]";
    }
}
