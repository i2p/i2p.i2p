package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.Properties;

/**
 * Wrap up the client settings specifying their tunnel criteria
 *
 */
public class ClientTunnelSettings {
    private int _numInbound;
    private int _numOutbound;
    private int _depthInbound;
    private int _depthOutbound;
    private long _msgsPerMinuteAvgInbound;
    private long _bytesPerMinuteAvgInbound;
    private long _msgsPerMinutePeakInbound;
    private long _bytesPerMinutePeakInbound;
    private boolean _includeDummyInbound;
    private boolean _includeDummyOutbound;
    private boolean _reorderInbound;
    private boolean _reorderOutbound;
    private long _inboundDuration;
    private boolean _enforceStrictMinimumLength;
    
    public final static String PROP_NUM_INBOUND = "tunnels.numInbound";
    public final static String PROP_NUM_OUTBOUND = "tunnels.numOutbound";
    public final static String PROP_DEPTH_INBOUND = "tunnels.depthInbound";
    public final static String PROP_DEPTH_OUTBOUND = "tunnels.depthOutbound";
    public final static String PROP_MSGS_AVG = "tunnels.messagesPerMinuteAverage";
    public final static String PROP_MSGS_PEAK = "tunnels.messagesPerMinutePeak";
    public final static String PROP_BYTES_AVG = "tunnels.bytesPerMinuteAverage";
    public final static String PROP_BYTES_PEAK = "tunnels.bytesPerMinutePeak";
    public final static String PROP_DUMMY_INBOUND = "tunnels.includeDummyTrafficInbound";
    public final static String PROP_DUMMY_OUTBOUND = "tunnels.includeDummyTrafficOutbound";
    public final static String PROP_REORDER_INBOUND = "tunnels.reorderInboundMessages";
    public final static String PROP_REORDER_OUTBOUND = "tunnels.reoderOutboundMessages";
    public final static String PROP_DURATION = "tunnels.tunnelDuration";        
    /** 
     * if tunnels.strictMinimumLength=true then never accept a tunnel shorter than the client's
     * request, otherwise we'll try to meet that minimum, but if we don't have any that length, 
     * we'll accept the longest we do have.
     *
     */
    public final static String PROP_STRICT_MINIMUM_LENGTH = "tunnels.enforceStrictMinimumLength";
    
    public final static int     DEFAULT_NUM_INBOUND = 2;
    public final static int     DEFAULT_NUM_OUTBOUND = 1;
    public final static int     DEFAULT_DEPTH_INBOUND = 2;
    public final static int     DEFAULT_DEPTH_OUTBOUND = 2;
    public final static long    DEFAULT_MSGS_AVG = 0;
    public final static long    DEFAULT_MSGS_PEAK = 0;
    public final static long    DEFAULT_BYTES_AVG = 0;
    public final static long    DEFAULT_BYTES_PEAK = 0;
    public final static boolean DEFAULT_DUMMY_INBOUND = false;
    public final static boolean DEFAULT_DUMMY_OUTBOUND = false;
    public final static boolean DEFAULT_REORDER_INBOUND = false;
    public final static boolean DEFAULT_REORDER_OUTBOUND = false;
    public final static long    DEFAULT_DURATION = 10*60*1000;
    public final static boolean DEFAULT_STRICT_MINIMUM_LENGTH = true;
    
    public ClientTunnelSettings() {
	_numInbound = 0;
	_numOutbound = 0;
	_depthInbound = 0;
	_depthOutbound = 0;
	_msgsPerMinuteAvgInbound = 0;
	_bytesPerMinuteAvgInbound = 0;
	_msgsPerMinutePeakInbound = 0;
	_bytesPerMinutePeakInbound = 0;
	_includeDummyInbound = false;
	_includeDummyOutbound = false;
	_reorderInbound = false;
	_reorderOutbound = false;
	_inboundDuration = -1;
	_enforceStrictMinimumLength = false;
    }
        
    public int getNumInboundTunnels() { return _numInbound; }
    public int getNumOutboundTunnels() { return _numOutbound; }
    public int getDepthInbound() { return _depthInbound; }
    public int getDepthOutbound() { return _depthOutbound; }
    public long getMessagesPerMinuteInboundAverage() { return _msgsPerMinuteAvgInbound; }
    public long getMessagesPerMinuteInboundPeak() { return _msgsPerMinutePeakInbound; }
    public long getBytesPerMinuteInboundAverage() { return _bytesPerMinuteAvgInbound; }
    public long getBytesPerMinuteInboundPeak() { return _bytesPerMinutePeakInbound; }
    public boolean getIncludeDummyInbound() { return _includeDummyInbound; }
    public boolean getIncludeDummyOutbound() { return _includeDummyOutbound; }
    public boolean getReorderInbound() { return _reorderInbound; }
    public boolean getReorderOutbound() { return _reorderOutbound; }
    public long getInboundDuration() { return _inboundDuration; }
    public boolean getEnforceStrictMinimumLength() { return _enforceStrictMinimumLength; }
    
    public void setNumInboundTunnels(int num) { _numInbound = num; }
    public void setNumOutboundTunnels(int num) { _numOutbound = num; }
    public void setEnforceStrictMinimumLength(boolean enforce) { _enforceStrictMinimumLength = enforce; }
    
    public void readFromProperties(Properties props) {
	_numInbound = getInt(props.getProperty(PROP_NUM_INBOUND), DEFAULT_NUM_INBOUND);
	_numOutbound = getInt(props.getProperty(PROP_NUM_OUTBOUND), DEFAULT_NUM_OUTBOUND);
	_depthInbound = getInt(props.getProperty(PROP_DEPTH_INBOUND), DEFAULT_DEPTH_INBOUND);
	_depthOutbound = getInt(props.getProperty(PROP_DEPTH_OUTBOUND), DEFAULT_DEPTH_OUTBOUND);
	_msgsPerMinuteAvgInbound = getLong(props.getProperty(PROP_MSGS_AVG), DEFAULT_MSGS_AVG);
	_bytesPerMinuteAvgInbound = getLong(props.getProperty(PROP_MSGS_PEAK), DEFAULT_BYTES_AVG);
	_msgsPerMinutePeakInbound = getLong(props.getProperty(PROP_BYTES_AVG), DEFAULT_MSGS_PEAK);
	_bytesPerMinutePeakInbound = getLong(props.getProperty(PROP_BYTES_PEAK), DEFAULT_BYTES_PEAK);
	_includeDummyInbound = getBoolean(props.getProperty(PROP_DUMMY_INBOUND), DEFAULT_DUMMY_INBOUND);
	_includeDummyOutbound = getBoolean(props.getProperty(PROP_DUMMY_OUTBOUND), DEFAULT_DUMMY_OUTBOUND);
	_reorderInbound = getBoolean(props.getProperty(PROP_REORDER_INBOUND), DEFAULT_REORDER_INBOUND);
	_reorderOutbound = getBoolean(props.getProperty(PROP_REORDER_OUTBOUND), DEFAULT_REORDER_OUTBOUND);
	_inboundDuration = getLong(props.getProperty(PROP_DURATION), DEFAULT_DURATION);
	_enforceStrictMinimumLength = getBoolean(props.getProperty(PROP_STRICT_MINIMUM_LENGTH), DEFAULT_STRICT_MINIMUM_LENGTH);
    }
    
    public void writeToProperties(Properties props) {
	if (props == null) return;
	props.setProperty(PROP_NUM_INBOUND, ""+_numInbound);
	props.setProperty(PROP_NUM_OUTBOUND, ""+_numOutbound);
	props.setProperty(PROP_DEPTH_INBOUND, ""+_depthInbound);
	props.setProperty(PROP_DEPTH_OUTBOUND, ""+_depthOutbound);
	props.setProperty(PROP_MSGS_AVG, ""+_msgsPerMinuteAvgInbound);
	props.setProperty(PROP_MSGS_PEAK, ""+_msgsPerMinutePeakInbound);
	props.setProperty(PROP_BYTES_AVG, ""+_bytesPerMinuteAvgInbound);
	props.setProperty(PROP_BYTES_PEAK, ""+_bytesPerMinutePeakInbound);
	props.setProperty(PROP_DUMMY_INBOUND, (_includeDummyInbound ? Boolean.TRUE.toString() : Boolean.FALSE.toString()));
	props.setProperty(PROP_DUMMY_OUTBOUND, (_includeDummyOutbound ? Boolean.TRUE.toString() : Boolean.FALSE.toString()));
	props.setProperty(PROP_REORDER_INBOUND, (_reorderInbound ? Boolean.TRUE.toString() : Boolean.FALSE.toString()));
	props.setProperty(PROP_REORDER_OUTBOUND, (_reorderOutbound ? Boolean.TRUE.toString() : Boolean.FALSE.toString()));
	props.setProperty(PROP_DURATION, ""+_inboundDuration);
	props.setProperty(PROP_STRICT_MINIMUM_LENGTH, (_enforceStrictMinimumLength ? Boolean.TRUE.toString() : Boolean.FALSE.toString()));
    }

    public String toString() {
	StringBuffer buf = new StringBuffer();
	Properties p = new Properties();
	writeToProperties(p);
	buf.append("Client tunnel settings:\n");
	buf.append("====================================\n");
	for (Iterator iter = p.keySet().iterator(); iter.hasNext(); ) {
	    String name = (String)iter.next();
	    String val  = p.getProperty(name);
	    buf.append(name).append(" = [").append(val).append("]\n");
	}
	buf.append("====================================\n");
	return buf.toString();
    }
    
    ////
    ////
    
    private static final boolean getBoolean(String str, boolean defaultValue) { 
	if (str == null) return defaultValue;
	String s = str.toUpperCase();
	boolean v = "TRUE".equals(s) || "YES".equals(s);
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
