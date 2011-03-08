package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

/**
 * A six-byte Date and 2 bytes of flags, since a Date won't encroach
 * on the top two bytes until the year 10889.
 *
 * The flag format is not specified here. The bits may be used in
 * an application-specific manner. The application should
 * be designed so that a flags value of 0 is the default, for
 * compatibility with an 8-byte Date.
 *
 * If we really need some more bits we could use the first few bits
 * of the third byte.
 *
 * @author zzz
 * @since 0.8.4
 */
public class DateAndFlags extends DataStructureImpl {
    private int _flags;
    private long _date;

    public DateAndFlags() {}

    /**
     *  @param flags 0 - 65535
     */
    public DateAndFlags(long date, int flags) {
        _flags = flags;
        _date = date;
    }

    /**
     *  @param flags 0 - 65535
     */
    public DateAndFlags(Date date, int flags) {
        _flags = flags;
        _date = date.getTime();
    }

    public int getFlags() {
        return _flags;
    }

    /**
     *  @param flags 0 - 65535
     */
    public void setFlags(int flags) {
        _flags = flags;
    }

    /**
     *  The Date object is created here, it is not cached.
     *  Use getTime() if you only need the long value.
     */
    public Date getDate() {
        return new Date(_date);
    }

    public long getTime() {
        return (_date);
    }

    public void setDate(long date) {
        _date = date;
    }

    public void setDate(Date date) {
        _date = date.getTime();
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _flags = (int) DataHelper.readLong(in, 2);
        _date = DataHelper.readLong(in, 6);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        DataHelper.writeLong(out, 2, _flags);
        DataHelper.writeLong(out, 6, _date);
    }
    
    /**
     * Overridden for efficiency.
     */
    @Override
    public byte[] toByteArray() {
        byte[] rv = DataHelper.toLong(8, _date);
        rv[0] = (byte) ((_flags >> 8) & 0xff);
        rv[1] = (byte) (_flags & 0xff);
        return rv;
    }

    /**
     * Overridden for efficiency.
     * @param data non-null
     * @throws DataFormatException if null or wrong length
     */
    @Override
    public void fromByteArray(byte data[]) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        if (data.length != 8) throw new DataFormatException("Bad data length");
        _flags = (int) DataHelper.fromLong(data, 0, 2);
        _date = DataHelper.fromLong(data, 2, 6);
    }

    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof DateAndFlags)) return false;
        DateAndFlags daf = (DateAndFlags) object;
        return _date == daf._date && _flags == daf._flags;

    }
    
    @Override
    public int hashCode() {
        return _flags + (int) _date;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[DateAndFlags: ");
        buf.append("\n\tDate: ").append((new Date(_date)).toString());
        buf.append("\n\tFlags: 0x").append(Integer.toHexString(_flags));
        buf.append("]");
        return buf.toString();
    }
}
