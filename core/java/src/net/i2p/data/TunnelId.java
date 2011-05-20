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
 * @author jrandom
 */
public class TunnelId extends DataStructureImpl {
    private long _tunnelId;
    
    public static final long MAX_ID_VALUE = (1l<<32l)-2l;
    
    public TunnelId() { 
        _tunnelId = -1;
    }

    public TunnelId(long id) { 
        if (id <= 0) throw new IllegalArgumentException("wtf, tunnelId " + id);
        _tunnelId = id;
    }

    public long getTunnelId() { return _tunnelId; }

    public void setTunnelId(long id) { 
        _tunnelId = id; 
        if (id <= 0) throw new IllegalArgumentException("wtf, tunnelId " + id);
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
        if ( (obj == null) || !(obj instanceof TunnelId))
            return false;
        return _tunnelId == ((TunnelId)obj).getTunnelId();
    }
    
    @Override
    public int hashCode() {
        return (int)_tunnelId; 
    }
    
    @Override
    public String toString() { return String.valueOf(_tunnelId); }
}
