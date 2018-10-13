package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

/**
 * Like a Lease, but points to another LeaseSet.
 *
 * TunnelId unused. Flags unsupported.
 * The LeaseSet Hash is available at getGateway() / setGateway().
 * Length is 40.
 *
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public class MetaLease extends Lease {

    private int _cost;

    public int getCost() {
        return _cost;
    }

    public void setCost(int cost) {
        _cost = cost;
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public TunnelId getTunnelId() {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void setTunnelId(TunnelId id) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _gateway = Hash.create(in);
        // flags
        DataHelper.skip(in, 3);
        _cost = in.read();
        _end = new Date(DataHelper.readLong(in, 4) * 1000);
    }
    
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_gateway == null)
            throw new DataFormatException("Not enough data to write out a Lease");
        _gateway.writeBytes(out);
        // flags
        DataHelper.writeLong(out, 3, 0);
        out.write(_cost);
        DataHelper.writeLong(out, 4, _end.getTime() / 1000);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof MetaLease)) return false;
        MetaLease lse = (MetaLease) object;
        return DataHelper.eq(_end, lse.getEndDate())
               && _cost == lse._cost
               && DataHelper.eq(_gateway, lse.getGateway());
    }
    
    @Override
    public int hashCode() {
        return (int) _end.getTime() ^ DataHelper.hashCode(_gateway)
               ^ _cost;
    }
}
