package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.OrderedProperties;

/**
 * Request the other side to send us what they think the current time is.
 * Only supported from client to router.
 *
 * Since 0.8.7, optionally include a version string.
 * Since 0.9.11, optionally include options.
 */
public class GetDateMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 32;
    private String _version;
    private Properties _options;

    public GetDateMessage() {
        super();
    }

    /**
     *  @param version the client's version String to be sent to the router; may be null
     *  @since 0.8.7
     */
    public GetDateMessage(String version) {
        super();
        _version = version;
    }

    /**
     *  Defaults in GetDateMessage options are, in general, NOT honored.
     *  Defaults are not serialized out-of-JVM, and the router does not recognize defaults in-JVM.
     *  Client side must promote defaults to the primary map.
     *
     *  @param version the client's version String to be sent to the router; may be null;
     *                 must be non-null if options is non-null and non-empty.
     *  @param options Client options to be sent to the router; primarily for authentication; may be null;
     *                 keys and values 255 bytes (not chars) max each
     *  @since 0.9.11
     */
    public GetDateMessage(String version, Properties options) {
        super();
        if (version == null && options != null && !options.isEmpty())
            throw new IllegalArgumentException();
        _version = version;
        _options = options;
    }

    /**
     *  @return may be null
     *  @since 0.8.7
     */
    public String getVersion() {
        return _version;
    }

    /**
     *  Retrieve any configuration options for the connection.
     *  Primarily for authentication.
     *
     *  @return may be null
     *  @since 0.9.11
     */
    public Properties getOptions() {
        return _options;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        if (size > 0) {
            try {
                _version = DataHelper.readString(in);
                if (size > 1 + _version.length())  // assume ascii
                    _options = DataHelper.readProperties(in);
            } catch (DataFormatException dfe) {
                throw new I2CPMessageException("Bad version string", dfe);
            }
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_version == null)
            return new byte[0];
        ByteArrayOutputStream os = new ByteArrayOutputStream(_options != null ? 128 : 16);
        try {
            DataHelper.writeString(os, _version);
            if (_options != null && !_options.isEmpty())
                DataHelper.writeProperties(os, _options, true);  // UTF-8
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[GetDateMessage]");
        buf.append("\n\tVersion: ").append(_version);
        if (_options != null && !_options.isEmpty()) {
            buf.append("\n\tOptions: #: ").append(_options.size());
            Properties sorted = new OrderedProperties();
            sorted.putAll(_options);
            for (Map.Entry<Object, Object> e : sorted.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
            }
        }
        return buf.toString();
    }
}
