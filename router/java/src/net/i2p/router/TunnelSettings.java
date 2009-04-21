package net.i2p.router;
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

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;

/**
 * Wrap up the settings specified for a particular tunnel
 *
 */
public class TunnelSettings extends DataStructureImpl {
    private I2PAppContext _context;
    private int _depth;
    private long _msgsPerMinuteAvg;
    private long _bytesPerMinuteAvg;
    private long _msgsPerMinutePeak;
    private long _bytesPerMinutePeak;
    private boolean _includeDummy;
    private boolean _reorder;
    private long _expiration;
    private long _created;
    
    public TunnelSettings(I2PAppContext context) {
        _context = context;
        _depth = 0;
        _msgsPerMinuteAvg = 0;
        _msgsPerMinutePeak = 0;
        _bytesPerMinuteAvg = 0;
        _bytesPerMinutePeak = 0;
        _includeDummy = false;
        _reorder = false;
        _expiration = 0;
        _created = _context.clock().now();
    }
    
    public int getDepth() { return _depth; }
    public void setDepth(int depth) { _depth = depth; }
    public long getMessagesPerMinuteAverage() { return _msgsPerMinuteAvg; }
    public long getMessagesPerMinutePeak() { return _msgsPerMinutePeak; }
    public long getBytesPerMinuteAverage() { return _bytesPerMinuteAvg; }
    public long getBytesPerMinutePeak() { return _bytesPerMinutePeak; }
    public void setMessagesPerMinuteAverage(long msgs) { _msgsPerMinuteAvg = msgs; }
    public void setMessagesPerMinutePeak(long msgs) { _msgsPerMinutePeak = msgs; }
    public void setBytesPerMinuteAverage(long bytes) { _bytesPerMinuteAvg = bytes; }
    public void setBytesPerMinutePeak(long bytes) { _bytesPerMinutePeak = bytes; }
    public boolean getIncludeDummy() { return _includeDummy; }
    public void setIncludeDummy(boolean include) { _includeDummy = include; }
    public boolean getReorder() { return _reorder; }
    public void setReorder(boolean reorder) { _reorder = reorder; }
    public long getExpiration() { return _expiration; }
    public void setExpiration(long expiration) { _expiration = expiration; }
    public long getCreated() { return _created; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        Boolean b = DataHelper.readBoolean(in);
        if (b == null) throw new DataFormatException("Null includeDummy boolean value");
        _includeDummy = b.booleanValue();
        b = DataHelper.readBoolean(in);
        if (b == null) throw new DataFormatException("Null reorder boolean value");
        _reorder = b.booleanValue();
        _depth = (int)DataHelper.readLong(in, 1);
        _bytesPerMinuteAvg = DataHelper.readLong(in, 4);
        _bytesPerMinutePeak = DataHelper.readLong(in, 4);
        Date exp = DataHelper.readDate(in);
        if (exp == null)
            _expiration = 0;
        else
            _expiration = exp.getTime();
        _msgsPerMinuteAvg = DataHelper.readLong(in, 4);
        _msgsPerMinutePeak = DataHelper.readLong(in, 4);
        Date created = DataHelper.readDate(in);
        if (created != null)
            _created = created.getTime();
        else
            _created = _context.clock().now();
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        DataHelper.writeBoolean(out, _includeDummy ? Boolean.TRUE : Boolean.FALSE);
        DataHelper.writeBoolean(out, _reorder ? Boolean.TRUE : Boolean.FALSE);
        DataHelper.writeLong(out, 1, _depth);
        DataHelper.writeLong(out, 4, _bytesPerMinuteAvg);
        DataHelper.writeLong(out, 4, _bytesPerMinutePeak);
        if (_expiration <= 0)
            DataHelper.writeDate(out, new Date(0));
        else
            DataHelper.writeDate(out, new Date(_expiration));
        DataHelper.writeLong(out, 4, _msgsPerMinuteAvg);
        DataHelper.writeLong(out, 4, _msgsPerMinutePeak);
        DataHelper.writeDate(out, new Date(_created));
    }
    
    
    @Override
    public int hashCode() {
        int rv = 0;
        rv += _includeDummy ? 100 : 0;
        rv += _reorder ? 50 : 0;
        rv += _depth;
        rv += _bytesPerMinuteAvg;
        rv += _bytesPerMinutePeak;
        rv += _expiration;
        rv += _msgsPerMinuteAvg;
        rv += _msgsPerMinutePeak;
        return rv;
    }
    
    @Override
    public boolean equals(Object obj) {
        if ( (obj != null) && (obj instanceof TunnelSettings) ) {
            TunnelSettings settings = (TunnelSettings)obj;
            return settings.getBytesPerMinuteAverage() == getBytesPerMinuteAverage() &&
            settings.getBytesPerMinutePeak() == getBytesPerMinutePeak() &&
            settings.getDepth() == getDepth() &&
            settings.getExpiration() == getExpiration() &&
            settings.getIncludeDummy() == getIncludeDummy() &&
            settings.getMessagesPerMinuteAverage() == getMessagesPerMinuteAverage() &&
            settings.getMessagesPerMinutePeak() == getMessagesPerMinutePeak() &&
            settings.getReorder() == getReorder();
        } else {
            return false;
        }
    }
}
