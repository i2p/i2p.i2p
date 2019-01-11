package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

/**
 * Like Lease but with 4-byte timestamps.
 * Length is 40.
 *
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public class Lease2 extends Lease {

    public static final int LENGTH = 40;
    
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _gateway = Hash.create(in);
        _tunnelId = new TunnelId();
        _tunnelId.readBytes(in);
        _end = new Date(DataHelper.readLong(in, 4) * 1000);
    }
    
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_gateway == null) || (_tunnelId == null))
            throw new DataFormatException("Not enough data to write out a Lease");
        _gateway.writeBytes(out);
        _tunnelId.writeBytes(out);
        DataHelper.writeLong(out, 4, _end.getTime() / 1000);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof Lease2)) return false;
        Lease2 lse = (Lease2) object;
        return DataHelper.eq(_end, lse.getEndDate())
               && DataHelper.eq(_tunnelId, lse.getTunnelId())
               && DataHelper.eq(_gateway, lse.getGateway());
    }
    
    @Override
    public int hashCode() {
        return (int) _end.getTime() ^ DataHelper.hashCode(_gateway)
               ^ (int) _tunnelId.getTunnelId();
    }
}
