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

import net.i2p.util.Log;

/**
 * Defines the tunnel ID that messages are passed through on a set of routers.
 * This is not globally unique, but must be unique on each router making up
 * the tunnel (otherwise they would get confused and send messages down the 
 * wrong one).
 *
 * @author jrandom
 */
public class TunnelId extends DataStructureImpl {
    private final static Log _log = new Log(TunnelId.class);
    private long _tunnelId;
    private int _type;
    
    public static final long MAX_ID_VALUE = (1l<<32l)-1l;

    public final static int TYPE_UNSPECIFIED = 0;
    public final static int TYPE_INBOUND = 1;
    public final static int TYPE_OUTBOUND = 2;
    public final static int TYPE_PARTICIPANT = 3;
    
    public TunnelId() { 
	setTunnelId(-1); 
	setType(TYPE_UNSPECIFIED);
    }
    
    public long getTunnelId() { return _tunnelId; }
    public void setTunnelId(long id) { _tunnelId = id; }
    
    /** 
     * is this tunnel inbound, outbound, or a participant (kept in memory only and used only for the router).s
     *
     * @return type of tunnel (per constants TYPE_UNSPECIFIED, TYPE_INBOUND, TYPE_OUTBOUND, TYPE_PARTICIPANT)
     */
    public int getType() { return _type; }
    public void setType(int type) { _type = type; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _tunnelId = DataHelper.readLong(in, 4);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_tunnelId < 0) throw new DataFormatException("Invalid tunnel ID: " + _tunnelId);
        DataHelper.writeLong(out, 4, _tunnelId);
    }
    
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof TunnelId))
            return false;
        return getTunnelId() == ((TunnelId)obj).getTunnelId();
    }
    
    public int hashCode() {
        return (int)getTunnelId(); 
    }
    
    public String toString() {
        return "[TunnelID: " + getTunnelId() + "]";
    }
}
