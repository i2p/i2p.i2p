package net.i2p.stat;

import java.util.Properties;

import net.i2p.util.Log;

/** object orientation gives you hairy palms. */
class PersistenceHelper {
    private final static Log _log = new Log(PersistenceHelper.class);
    private final static String NL = System.getProperty("line.separator");

    public final static void add(StringBuilder buf, String prefix, String name, String description, double value) {
        buf.append("# ").append(prefix).append(name).append(NL);
        buf.append("# ").append(description).append(NL);
        buf.append(prefix).append(name).append('=').append(value).append(NL).append(NL);
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