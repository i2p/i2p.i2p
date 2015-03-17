package net.i2p.router;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.util.RandomSource;

/**
 * Wrap up the settings for a pool of tunnels.
 *
 */
public class TunnelPoolSettings {
    private Hash _destination;
    private String _destinationNickname;
    private int _quantity;
    private int _backupQuantity;
    // private int _rebuildPeriod;
    //private int _duration;
    private int _length;
    private int _lengthVariance;
    private int _lengthOverride;
    private boolean _isInbound;
    private boolean _isExploratory;
    private boolean _allowZeroHop;
    private int _IPRestriction;
    private final Properties _unknownOptions;
    private final Hash _randomKey;
    
    /** prefix used to override the router's defaults for clients */
    public static final String  PREFIX_DEFAULT = "router.defaultPool.";
    /** prefix used to configure the inbound exploratory pool */
    public static final String  PREFIX_INBOUND_EXPLORATORY = "router.inboundPool.";
    /** prefix used to configure the outbound exploratory pool */
    public static final String  PREFIX_OUTBOUND_EXPLORATORY = "router.outboundPool.";
    
    public static final String  PROP_NICKNAME = "nickname";
    public static final String  PROP_QUANTITY = "quantity";
    public static final String  PROP_BACKUP_QUANTITY = "backupQuantity";
    // public static final String  PROP_REBUILD_PERIOD = "rebuildPeriod";
    public static final String  PROP_DURATION = "duration";
    public static final String  PROP_LENGTH = "length";
    public static final String  PROP_LENGTH_VARIANCE = "lengthVariance";
    public static final String  PROP_ALLOW_ZERO_HOP = "allowZeroHop";
    public static final String  PROP_IP_RESTRICTION = "IPRestriction";
    
    public static final int     DEFAULT_QUANTITY = 2;
    public static final int     DEFAULT_BACKUP_QUANTITY = 0;
    // public static final int     DEFAULT_REBUILD_PERIOD = 60*1000;
    public static final int     DEFAULT_DURATION = 10*60*1000;
    public static final int     DEFAULT_LENGTH = 2;
    public static final int     DEFAULT_LENGTH_VARIANCE = 0;
    public static final boolean DEFAULT_ALLOW_ZERO_HOP = true;
    public static final int     DEFAULT_IP_RESTRICTION = 2;    // class B (/16)
    
    public TunnelPoolSettings() {
        _quantity = DEFAULT_QUANTITY;
        _backupQuantity = DEFAULT_BACKUP_QUANTITY;
        // _rebuildPeriod = DEFAULT_REBUILD_PERIOD;
        //_duration = DEFAULT_DURATION;
        _length = DEFAULT_LENGTH;
        _lengthVariance = DEFAULT_LENGTH_VARIANCE;
        _lengthOverride = -1;
        _allowZeroHop = DEFAULT_ALLOW_ZERO_HOP;
        _IPRestriction = DEFAULT_IP_RESTRICTION;
        _unknownOptions = new Properties();
        _randomKey = generateRandomKey();
    }
    
    /** how many tunnels should be available at all times */
    public int getQuantity() { return _quantity; }
    public void setQuantity(int quantity) { _quantity = quantity; }

    /** how many backup tunnels should be kept waiting in the wings */
    public int getBackupQuantity() { return _backupQuantity; }
    public void setBackupQuantity(int quantity) { _backupQuantity = quantity; }
    
    /**
     *  Convenience
     *  @return getQuantity() + getBackupQuantity()
     *  @since 0.8.11
     */
    public int getTotalQuantity() {
        return _quantity + _backupQuantity;
    }

    /** how long before tunnel expiration should new tunnels be built */
    // public int getRebuildPeriod() { return _rebuildPeriod; }
    // public void setRebuildPeriod(int periodMs) { _rebuildPeriod = periodMs; }
    
    /**
     *  How many remote hops should be in the tunnel NOT including us
     *  @return 0 to 7
     */
    public int getLength() { return _length; }

    /**
     *  How many remote hops should be in the tunnel NOT including us
     *  @param length 0 to 7 (not enforced here)
     */
    public void setLength(int length) { _length = length; }
    
    /** if there are no tunnels to build with, will this pool allow 0 hop tunnels? */
    public boolean getAllowZeroHop() { return _allowZeroHop; }
    public void setAllowZeroHop(boolean ok) { _allowZeroHop = ok; }
    
    /** 
     * how should the length be varied.  if negative, this randomly skews from
     * (length - variance) to (length + variance), or if positive, from length
     * to (length + variance), inclusive.
     *
     */
    public int getLengthVariance() { return _lengthVariance; }
    public void setLengthVariance(int variance) { _lengthVariance = variance; }

    /** 
     * A temporary length to be used due to network conditions.
     * If less than zero, the standard length should be used.
     * Unused until 0.8.11
     */
    public int getLengthOverride() { return _lengthOverride; }

    /** 
     * A temporary length to be used due to network conditions.
     * If less than zero, the standard length will be used.
     * Unused until 0.8.11
     */
    public void setLengthOverride(int length) { _lengthOverride = length; }

    /** is this an inbound tunnel? */
    public boolean isInbound() { return _isInbound; }
    public void setIsInbound(boolean isInbound) { _isInbound = isInbound; }
    
    /** is this an exploratory tunnel (or a client tunnel) */
    public boolean isExploratory() { return _isExploratory; }
    public void setIsExploratory(boolean isExploratory) { _isExploratory = isExploratory; }
    
    // Duration is hardcoded
    //public int getDuration() { return _duration; }
    //public void setDuration(int ms) { _duration = ms; }
    
    /** what destination is this a tunnel for (or null if none) */
    public Hash getDestination() { return _destination; }
    public void setDestination(Hash dest) { _destination = dest; }

    /** random key used for peer ordering */
    public Hash getRandomKey() { return _randomKey; }

    /** what user supplied name was given to the client connected (can be null) */
    public String getDestinationNickname() { return _destinationNickname; }
    public void setDestinationNickname(String name) { _destinationNickname = name; }
    
    /**
     *  How many bytes to match to determine if a router's IP is too close to another's
     *  to be in the same tunnel
     *  (1-4, 0 to disable)
     *
     */
    public int getIPRestriction() { int r = _IPRestriction; if (r>4) r=4; else if (r<0) r=0; return r;}
    public void setIPRestriction(int b) { _IPRestriction = b; }
    
    public Properties getUnknownOptions() { return _unknownOptions; }
    
    public void readFromProperties(String prefix, Map<Object, Object> props) {
        for (Map.Entry e : props.entrySet()) {
            String name = (String) e.getKey();
            String value = (String) e.getValue();
            if (name.startsWith(prefix)) {
                if (name.equalsIgnoreCase(prefix + PROP_ALLOW_ZERO_HOP))
                    _allowZeroHop = getBoolean(value, DEFAULT_ALLOW_ZERO_HOP);
                else if (name.equalsIgnoreCase(prefix + PROP_BACKUP_QUANTITY))
                    _backupQuantity = getInt(value, DEFAULT_BACKUP_QUANTITY);
                //else if (name.equalsIgnoreCase(prefix + PROP_DURATION))
                //    _duration = getInt(value, DEFAULT_DURATION);
                else if (name.equalsIgnoreCase(prefix + PROP_LENGTH))
                    _length = getInt(value, DEFAULT_LENGTH);
                else if (name.equalsIgnoreCase(prefix + PROP_LENGTH_VARIANCE))
                    _lengthVariance = getInt(value, DEFAULT_LENGTH_VARIANCE);
                else if (name.equalsIgnoreCase(prefix + PROP_QUANTITY))
                    _quantity = getInt(value, DEFAULT_QUANTITY);
                // else if (name.equalsIgnoreCase(prefix + PROP_REBUILD_PERIOD))
                //     _rebuildPeriod = getInt(value, DEFAULT_REBUILD_PERIOD);
                else if (name.equalsIgnoreCase(prefix + PROP_NICKNAME))
                    _destinationNickname = value;
                else if (name.equalsIgnoreCase(prefix + PROP_IP_RESTRICTION))
                    _IPRestriction = getInt(value, DEFAULT_IP_RESTRICTION);
                else
                    _unknownOptions.setProperty(name.substring((prefix != null ? prefix.length() : 0)), value);
            }
        }
	}
    
    public void writeToProperties(String prefix, Properties props) {
        if (props == null) return;
        props.setProperty(prefix + PROP_ALLOW_ZERO_HOP, ""+_allowZeroHop);
        props.setProperty(prefix + PROP_BACKUP_QUANTITY, ""+_backupQuantity);
        //props.setProperty(prefix + PROP_DURATION, ""+_duration);
        props.setProperty(prefix + PROP_LENGTH, ""+_length);
        props.setProperty(prefix + PROP_LENGTH_VARIANCE, ""+_lengthVariance);
        if (_destinationNickname != null)
            props.setProperty(prefix + PROP_NICKNAME, ""+_destinationNickname);
        props.setProperty(prefix + PROP_QUANTITY, ""+_quantity);
        // props.setProperty(prefix + PROP_REBUILD_PERIOD, ""+_rebuildPeriod);
        props.setProperty(prefix + PROP_IP_RESTRICTION, ""+_IPRestriction);
        for (Map.Entry e : _unknownOptions.entrySet()) {
            String name = (String) e.getKey();
            String val = (String) e.getValue();
            props.setProperty(prefix + name, val);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        Properties p = new Properties();
        writeToProperties("", p);
        buf.append("Tunnel pool settings:\n");
        buf.append("====================================\n");
        for (Map.Entry e : p.entrySet()) {
            String name = (String) e.getKey();
            String val = (String) e.getValue();
            buf.append(name).append(" = [").append(val).append("]\n");
        }
        buf.append("is inbound? ").append(_isInbound).append("\n");
        buf.append("is exploratory? ").append(_isExploratory).append("\n");
        buf.append("====================================\n");
        return buf.toString();
    }
    
    // used for strict peer ordering
    private static Hash generateRandomKey() {
        byte hash[] = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(hash);
        return new Hash(hash);
    }
    
    private static final boolean getBoolean(String str, boolean defaultValue) { 
        if (str == null) return defaultValue;
        boolean v = Boolean.valueOf(str).booleanValue() ||
                    (str != null && "YES".equals(str.toUpperCase(Locale.US)));
        return v;
    }

    private static final int getInt(String str, int defaultValue) { return (int)getLong(str, defaultValue); }

    private static final long getLong(String str, long defaultValue) {
        if (str == null) return defaultValue;
        try {
            long val = Long.parseLong(str);
            return val;
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }
}
