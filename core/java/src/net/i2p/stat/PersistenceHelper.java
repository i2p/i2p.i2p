package net.i2p.stat;

import java.util.Date;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 *  Output rate data.
 *  This is used via ProfilePersistenceHelper and the output
 *  must be compatible.
 */
class PersistenceHelper {
    private final static Log _log = new Log(PersistenceHelper.class);
    private final static String NL = System.getProperty("line.separator");

    public final static void add(StringBuilder buf, String prefix, String name, String description, double value) {
        buf.append("# ").append(prefix).append(name).append(NL);
        buf.append("# ").append(description).append(NL);
        buf.append(prefix).append(name).append('=').append(value).append(NL).append(NL);
    }

    /** @since 0.8.5 */
    public final static void addDate(StringBuilder buf, String prefix, String name, String description, long value) {
        String when = value > 0 ? (new Date(value)).toString() : "Never";
        add(buf, prefix, name, description + ' ' + when, value);
    }

    /** @since 0.8.5 */
    public final static void addTime(StringBuilder buf, String prefix, String name, String description, long value) {
        String when = DataHelper.formatDuration(value);
        add(buf, prefix, name, description + ' ' + when, value);
    }

    public final static void add(StringBuilder buf, String prefix, String name, String description, long value) {
        buf.append("# ").append(prefix).append(name).append(NL);
        buf.append("# ").append(description).append(NL);
        buf.append(prefix).append(name).append('=').append(value).append(NL).append(NL);
    }

    public final static long getLong(Properties props, String prefix, String name) {
        String val = props.getProperty(prefix + name);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                _log.warn("Error formatting " + val + " into a long", nfe);
            }
        } else {
            _log.warn("Key " + prefix + name + " does not exist");
        }
        return 0;
    }

    public final static double getDouble(Properties props, String prefix, String name) {
        String val = props.getProperty(prefix + name);
        if (val != null) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException nfe) {
                _log.warn("Error formatting " + val + " into a double", nfe);
            }
        } else {
            _log.warn("Key " + prefix + name + " does not exist");
        }
        return 0;
    }
}
