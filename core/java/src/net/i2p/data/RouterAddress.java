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
import java.util.Iterator;
import java.util.Properties;

import net.i2p.util.Log;

/**
 * Defines a method of communicating with a router
 *
 * @author jrandom
 */
public class RouterAddress extends DataStructureImpl {
    private final static Log _log = new Log(RouterAddress.class);
    private int _cost;
    private Date _expiration;
    private String _transportStyle;
    private Properties _options;

    public RouterAddress() {
        setCost(-1);
        setExpiration(null);
        setTransportStyle(null);
        setOptions(null);
    }

    /**
     * Retrieve the weighted cost of this address, relative to other methods of
     * contacting this router.  The value 0 means free and 255 means really expensive.
     * No value above 255 is allowed.
     *
     */
    public int getCost() {
        return _cost;
    }

    /**
     * Configure the weighted cost of using the address.
     * No value above 255 is allowed.
     *
     */
    public void setCost(int cost) {
        _cost = cost;
    }

    /**
     * Retrieve the date after which the address should not be used.  If this
     * is null, then the address never expires.
     *
     */
    public Date getExpiration() {
        return _expiration;
    }

    /**
     * Configure the expiration date of the address (null for no expiration)
     *
     */
    public void setExpiration(Date expiration) {
        _expiration = expiration;
    }

    /**
     * Retrieve the type of transport that must be used to communicate on this address.
     *
     */
    public String getTransportStyle() {
        return _transportStyle;
    }

    /**
     * Configure the type of transport that must be used to communicate on this address
     *
     */
    public void setTransportStyle(String transportStyle) {
        _transportStyle = transportStyle;
    }

    /**
     * Retrieve the transport specific options necessary for communication 
     *
     */
    public Properties getOptions() {
        return _options;
    }

    /**
     * Specify the transport specific options necessary for communication 
     *
     */
    public void setOptions(Properties options) {
        _options = options;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _cost = (int) DataHelper.readLong(in, 1);
        _expiration = DataHelper.readDate(in);
        _transportStyle = DataHelper.readString(in);
        _options = DataHelper.readProperties(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_cost < 0) || (_transportStyle == null) || (_options == null))
            throw new DataFormatException("Not enough data to write a router address");
        DataHelper.writeLong(out, 1, _cost);
        DataHelper.writeDate(out, _expiration);
        DataHelper.writeString(out, _transportStyle);
        DataHelper.writeProperties(out, _options);
    }
    
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof RouterAddress)) return false;
        RouterAddress addr = (RouterAddress) object;
        return DataHelper.eq(getCost(), addr.getCost()) && DataHelper.eq(getExpiration(), addr.getExpiration())
               && DataHelper.eq(getOptions(), addr.getOptions())
               && DataHelper.eq(getTransportStyle(), addr.getTransportStyle());
    }
    
    @Override
    public int hashCode() {
        return getCost() + DataHelper.hashCode(getTransportStyle()) + DataHelper.hashCode(getExpiration())
               + DataHelper.hashCode(getOptions());
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[RouterAddress: ");
        buf.append("\n\tTransportStyle: ").append(getTransportStyle());
        buf.append("\n\tCost: ").append(getCost());
        buf.append("\n\tExpiration: ").append(getExpiration());
        buf.append("\n\tOptions: #: ").append(getOptions().size());
        for (Iterator iter = getOptions().keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = getOptions().getProperty(key);
            buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
        }
        buf.append("]");
        return buf.toString();
    }
}