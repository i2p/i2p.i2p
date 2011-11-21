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
import java.util.Map;
import java.util.Properties;

import net.i2p.util.OrderedProperties;

/**
 * Defines a method of communicating with a router
 *
 * @author jrandom
 */
public class RouterAddress extends DataStructureImpl {
    private int _cost;
    private Date _expiration;
    private String _transportStyle;
    private Properties _options;

    public RouterAddress() {
        _cost = -1;
    }

    /**
     * Retrieve the weighted cost of this address, relative to other methods of
     * contacting this router.  The value 0 means free and 255 means really expensive.
     * No value above 255 is allowed.
     *
     * Unused before 0.7.12
     */
    public int getCost() {
        return _cost;
    }

    /**
     * Configure the weighted cost of using the address.
     * No value above 255 is allowed.
     *
     * NTCP is set to 10 and SSU to 5 by default, unused before 0.7.12
     */
    public void setCost(int cost) {
        _cost = cost;
    }

    /**
     * Retrieve the date after which the address should not be used.  If this
     * is null, then the address never expires.
     *
     * @deprecated unused for now
     */
    public Date getExpiration() {
        return _expiration;
    }

    /**
     * Configure the expiration date of the address (null for no expiration)
     *
     * Unused for now, always null
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
        return
               _cost == addr._cost &&
               DataHelper.eq(_transportStyle, addr._transportStyle) &&
               DataHelper.eq(_options, addr._options) &&
               DataHelper.eq(_expiration, addr._expiration);
    }
    
    /**
     * Just use style and hashCode for speed (expiration is always null).
     * If we add multiple addresses of the same style, this may need to be changed.
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_transportStyle) ^ _cost;
    }
    
    /**
     *  This is used on peers.jsp so sort options so it looks better.
     *  We don't just use OrderedProperties for _options because DataHelper.writeProperties()
     *  sorts also.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[RouterAddress: ");
        buf.append("\n\tTransportStyle: ").append(_transportStyle);
        buf.append("\n\tCost: ").append(_cost);
        buf.append("\n\tExpiration: ").append(_expiration);
        if (_options != null) {
            buf.append("\n\tOptions: #: ").append(_options.size());
            Properties p = new OrderedProperties();
            p.putAll(_options);
            for (Map.Entry e : p.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
            }
        }
        buf.append("]");
        return buf.toString();
    }
}
