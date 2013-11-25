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
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import net.i2p.util.Addresses;
import net.i2p.util.OrderedProperties;

/**
 * Defines a method of communicating with a router
 *
 * For efficiency, the options methods and structures here are unsynchronized.
 * Initialize the structure with readBytes(), or call the setOptions().
 * Don't change it after that.
 *
 * To ensure integrity of the RouterInfo, methods that change an element of the
 * RouterInfo will throw an IllegalStateException after the RouterInfo is signed.
 *
 * As of 0.9.3, expiration MUST be all zeros as it is ignored on
 * readin and the signature will fail.
 * If we implement expiration, or other use for the field, we must allow
 * several releases for the change to propagate as it is backwards-incompatible.
 *
 * @author jrandom
 */
public class RouterAddress extends DataStructureImpl {
    private short _cost;
    //private Date _expiration;
    private String _transportStyle;
    private final Properties _options;
    // cached values
    private byte[] _ip;
    private int _port;

    public static final String PROP_HOST = "host";
    public static final String PROP_PORT = "port";

    public RouterAddress() {
        _options = new OrderedProperties();
    }

    /**
     *  For efficiency when created by a Transport.
     *  @param options not copied; do not reuse or modify
     *  @param cost 0-255
     *  @since IPv6
     */
    public RouterAddress(String style, OrderedProperties options, int cost) {
        _transportStyle = style;
        _options = options;
        if (cost < 0 || cost > 255)
            throw new IllegalArgumentException();
        _cost = (short) cost;
    }

    /**
     * Retrieve the weighted cost of this address, relative to other methods of
     * contacting this router.  The value 0 means free and 255 means really expensive.
     * No value above 255 is allowed.
     *
     * Unused before 0.7.12
     * @return 0-255
     */
    public int getCost() {
        return _cost;
    }

    /**
     * Configure the weighted cost of using the address.
     * No value negative or above 255 is allowed.
     *
     * WARNING - do not change cost on a published address or it will break the RI sig.
     * There is no check here.
     * Rarely used, use 3-arg constructor.
     *
     * NTCP is set to 10 and SSU to 5 by default, unused before 0.7.12
     */
    public void setCost(int cost) {
        if (cost < 0 || cost > 255)
            throw new IllegalArgumentException();
        _cost = (short) cost;
    }

    /**
     * Retrieve the date after which the address should not be used.  If this
     * is null, then the address never expires.
     * As of 0.9.3, expiration MUST be all zeros as it is ignored on
     * readin and the signature will fail.
     *
     * @deprecated unused for now
     * @return null always
     */
    public Date getExpiration() {
        //return _expiration;
        return null;
    }

    /**
     * Configure the expiration date of the address (null for no expiration)
     * As of 0.9.3, expiration MUST be all zeros as it is ignored on
     * readin and the signature will fail.
     *
     * Unused for now, always null
     * @deprecated unused for now
     */
    public void setExpiration(Date expiration) {
        //_expiration = expiration;
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
     * @throws IllegalStateException if was already set
     * @deprecated unused, use 3-arg constructor
     */
    public void setTransportStyle(String transportStyle) {
        if (_transportStyle != null)
            throw new IllegalStateException();
        _transportStyle = transportStyle;
    }

    /**
     * Retrieve the transport specific options necessary for communication 
     *
     * @deprecated use getOptionsMap()
     * @return sorted, non-null, NOT a copy, do not modify
     */
    public Properties getOptions() {
        return _options;
    }

    /**
     * Retrieve the transport specific options necessary for communication 
     *
     * @return an unmodifiable view, non-null, sorted
     * @since 0.8.13
     */
    public Map<Object, Object> getOptionsMap() {
        return Collections.unmodifiableMap(_options);
    }

    /**
     * @since 0.8.13
     */
    public String getOption(String opt) {
        return _options.getProperty(opt);
    }

    /**
     * Specify the transport specific options necessary for communication.
     * Makes a copy.
     * @param options non-null
     * @throws IllegalStateException if was already set
     * @deprecated unused, use 3-arg constructor
     */
    public void setOptions(Properties options) {
        if (!_options.isEmpty())
            throw new IllegalStateException();
        _options.putAll(options);
    }
    
    /**
     *  Caching version of InetAddress.getByName(getOption("host")).getAddress(), which is slow.
     *  Caches numeric host names only.
     *  Will resolve but not cache resolution of DNS host names.
     *
     *  @return IP or null
     *  @since 0.9.3
     */
    public byte[] getIP() {
        if (_ip != null)
            return _ip;
        byte[] rv = null;
        String host = getHost();
        if (host != null) {
            rv = Addresses.getIP(host);
            if (rv != null &&
                (host.replaceAll("[0-9\\.]", "").length() == 0 ||
                 host.replaceAll("[0-9a-fA-F:]", "").length() == 0)) {
                _ip = rv;
            }
        }
        return rv;
    }
    
    /**
     *  Convenience, same as getOption("host").
     *  Does no parsing, so faster than getIP().
     *
     *  @return host string or null
     *  @since IPv6
     */
    public String getHost() {
        return _options.getProperty(PROP_HOST);
    }
    
    /**
     *  Caching version of Integer.parseInt(getOption("port"))
     *  Caches valid ports 1-65535 only.
     *
     *  @return 1-65535 or 0 if invalid
     *  @since 0.9.3
     */
    public int getPort() {
        if (_port != 0)
            return _port;
        String port = _options.getProperty(PROP_PORT);
        if (port != null) {
            try {
                int rv = Integer.parseInt(port);
                if (rv > 0 && rv <= 65535)
                    _port = rv;
            } catch (NumberFormatException nfe) {}
        }
        return _port;
    }

    /**
     *  As of 0.9.3, expiration MUST be all zeros as it is ignored on
     *  readin and the signature will fail.
     *  @throws IllegalStateException if was already read in
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_transportStyle != null)
            throw new IllegalStateException();
        _cost = (short) DataHelper.readLong(in, 1);
        //_expiration = DataHelper.readDate(in);
        DataHelper.readDate(in);
        _transportStyle = DataHelper.readString(in);
        // reduce Object proliferation
        if (_transportStyle.equals("SSU"))
            _transportStyle = "SSU";
        else if (_transportStyle.equals("NTCP"))
            _transportStyle = "NTCP";
        DataHelper.readProperties(in, _options);
    }
    
    /**
     *  As of 0.9.3, expiration MUST be all zeros as it is ignored on
     *  readin and the signature will fail.
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_transportStyle == null)
            throw new DataFormatException("uninitialized");
        DataHelper.writeLong(out, 1, _cost);
        //DataHelper.writeDate(out, _expiration);
        DataHelper.writeDate(out, null);
        DataHelper.writeString(out, _transportStyle);
        DataHelper.writeProperties(out, _options);
    }
    
    /**
     * Transport, host, and port only.
     * Never look at cost or other properties.
     */
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof RouterAddress)) return false;
        RouterAddress addr = (RouterAddress) object;
        return
               getPort() == addr.getPort() &&
               DataHelper.eq(getHost(), addr.getHost()) &&
               DataHelper.eq(_transportStyle, addr._transportStyle);
               //DataHelper.eq(_options, addr._options) &&
               //DataHelper.eq(_expiration, addr._expiration);
    }
    
    /**
     *  Everything, including Transport, host, port, options, and cost
     *  @param addr may be null
     *  @since IPv6
     */
    public boolean deepEquals(RouterAddress addr) {
        return
               equals(addr) &&
               _cost == addr._cost &&
               _options.equals(addr._options);
    }
    
    /**
     * Just use a few items for speed (expiration is always null).
     * Never look at cost or other properties.
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_transportStyle) ^
               DataHelper.hashCode(getIP()) ^
               getPort();
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
        buf.append("\n\tType: ").append(_transportStyle);
        buf.append("\n\tCost: ").append(_cost);
        //buf.append("\n\tExpiration: ").append(_expiration);
            buf.append("\n\tOptions (").append(_options.size()).append("):");
            for (Map.Entry<Object, Object> e : _options.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
            }
        buf.append("]");
        return buf.toString();
    }
}
