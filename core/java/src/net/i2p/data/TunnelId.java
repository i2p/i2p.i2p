package net.i2p.data;
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

/**
 * Defines the tunnel ID that messages are passed through on a set of routers.
 * This is not globally unique, but must be unique on each router making up
 * the tunnel (otherwise they would get confused and send messages down the 
 * wrong one).
 *
 * Note that a TunnelId must be greater than zero,
 * as the DatabaseStoreMessage uses a zero ID to request
 * a direct reply.
 *
 * @author jrandom
 */
public class TunnelId extends DataStructureImpl {
    private long _tunnelId;
    
    public static final long MAX_ID_VALUE = 0xffffffffL;
    
    public TunnelId() { 
        _tunnelId = -1;
    }

    /**
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     */
    public TunnelId(long id) { 
        setTunnelId(id);
    }

    public long getTunnelId() { return _tunnelId; }

    /**
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     */
    public void setTunnelId(long id) { 
        if (id <= 0 || id > MAX_ID_VALUE)
            throw new IllegalArgumentException("bad id " + id);
        _tunnelId = id; 
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _tunnelId = DataHelper.readLong(in, 4);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_tunnelId < 0) throw new DataFormatException("Invalid tunnel ID: " + _tunnelId);
        DataHelper.writeLong(out, 4, _tunnelId);
    }

    /**
     * Overridden for efficiency.
     */
    @Override
    public byte[] toByteArray() {
        return DataHelper.toLong(4, _tunnelId);
    }

    /**
     * Overridden for efficiency.
     * @param data non-null
     * @throws DataFormatException if null or wrong length
     */
    @Override
    public void fromByteArray(byte data[]) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        if (data.length != 4) throw new DataFormatException("Bad data length");
        _tunnelId = (int) DataHelper.fromLong(data, 0, 4);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ( (obj == null) || !(obj instanceof TunnelId))
            return false;
        return _tunnelId == ((TunnelId)obj)._tunnelId;
    }
    
    @Override
    public int hashCode() {
        return (int)_tunnelId; 
    }
    
    @Override
    public String toString() { return String.valueOf(_tunnelId); }
}
