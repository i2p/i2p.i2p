package net.i2p.netmonitor;

/**
 * Actual data point (though its not fully normalized, but KISS)
 *
 */
public class PeerStat {
    private String _statName;
    private String _description;
    private String _valueDescriptions[];
    private long _sampleDate;
    private long _lvalues[];
    private double _dvalues[];
    
    public PeerStat(String name, String description, String valueDescriptions[], long sampleDate, double values[]) {
        this(name, description, valueDescriptions, sampleDate, null, values);
    }
    public PeerStat(String name, String description, String valueDescriptions[], long sampleDate, long values[]) {
        this(name, description, valueDescriptions, sampleDate, values, null);
    }
    private PeerStat(String name, String description, String valueDescriptions[], long sampleDate, long lvalues[], double dvalues[]) {
        _statName = name;
        _description = description;
        _valueDescriptions = valueDescriptions;
        _sampleDate = sampleDate;
        _lvalues = lvalues;
        _dvalues = dvalues;
    }
  
    /** unique name of the stat */
    public String getStatName() { return _statName; }
    /** one line summary of the stat */
    public String getDescription() { return _description; }
    /** description of each value */
    public String getValueDescription(int index) { return _valueDescriptions[index]; }
    /** description of all values */
    public String[] getValueDescriptions() { return _valueDescriptions; }
    /** when did the router publish the info being sampled? */
    public long getSampleDate() { return _sampleDate; }
    /** if the values are integers, this contains their values */
    public long[] getLongValues() { return _lvalues; }
    /** if the values are floating point numbers, this contains their values */
    public double[] getDoubleValues() { return _dvalues; }
    /** are the values floating poing numbers? */
    public boolean getIsDouble() { return _dvalues != null; }
}