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
import java.util.Date;

import net.i2p.util.Clock;

/**
 * Defines the proof that a particular router / tunnel is allowed to receive
 * messages for a particular Destination during some period of time.
 *
 * @author jrandom
 */
public class Lease extends DataStructureImpl {
    private Hash _gateway;
    private TunnelId _tunnelId;
    private Date _end;
    //private int _numSuccess;
    //private int _numFailure;

    public Lease() {
    }

    /** Retrieve the router at which the destination can be contacted
     * @return identity of the router acting as a gateway
     */
    public Hash getGateway() {
        return _gateway;
    }

    /** Configure the router at which the destination can be contacted
     * @param ident router acting as the gateway
     */
    public void setGateway(Hash ident) {
        _gateway = ident;
    }

    /** Tunnel on the gateway to communicate with
     * @return tunnel ID
     */
    public TunnelId getTunnelId() {
        return _tunnelId;
    }

    /** Configure the tunnel on the gateway to communicate with
     * @param id tunnel ID
     */
    public void setTunnelId(TunnelId id) {
        _tunnelId = id;
    }

    public Date getEndDate() {
        return _end;
    }

    public void setEndDate(Date date) {
        _end = date;
    }

    /**
     * Transient attribute of the lease, used to note how many times messages sent
     * to the destination through the current lease were successful.
     *
     */
/****
    public int getNumSuccess() {
        return _numSuccess;
    }
****/

/****
    public void setNumSuccess(int num) {
        _numSuccess = num;
    }
****/

    /**
     * Transient attribute of the lease, used to note how many times messages sent
     * to the destination through the current lease failed.
     *
     */
/****
    public int getNumFailure() {
        return _numFailure;
    }
****/

/****
    public void setNumFailure(int num) {
        _numFailure = num;
    }
****/

    /** has this lease already expired? */
    public boolean isExpired() {
        return isExpired(0);
    }

    /** has this lease already expired (giving allowing up the fudgeFactor milliseconds for clock skew)? */
    public boolean isExpired(long fudgeFactor) {
        if (_end == null) return true;
        return _end.getTime() < Clock.getInstance().now() - fudgeFactor;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        //_gateway = new Hash();
        //_gateway.readBytes(in);
        _gateway = Hash.create(in);
        _tunnelId = new TunnelId();
        _tunnelId.readBytes(in);
        _end = DataHelper.readDate(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_gateway == null) || (_tunnelId == null))
            throw new DataFormatException("Not enough data to write out a Lease");

        _gateway.writeBytes(out);
        _tunnelId.writeBytes(out);
        DataHelper.writeDate(out, _end);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof Lease)) return false;
        Lease lse = (Lease) object;
        return DataHelper.eq(_end, lse.getEndDate())
               && DataHelper.eq(_tunnelId, lse.getTunnelId())
               && DataHelper.eq(_gateway, lse.getGateway());

    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_end) + DataHelper.hashCode(_gateway)
               + DataHelper.hashCode(_tunnelId);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[Lease: ");
        buf.append("\n\tEnd Date: ").append(_end);
        buf.append("\n\tGateway: ").append(_gateway);
        buf.append("\n\tTunnelId: ").append(_tunnelId);
        buf.append("]");
        return buf.toString();
    }
}
