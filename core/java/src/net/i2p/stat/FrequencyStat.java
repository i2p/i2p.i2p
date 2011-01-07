package net.i2p.stat;

/** coordinate an event frequency over various periods */
public class FrequencyStat {
    /** unique name of the statistic */
    private final String _statName;
    /** grouping under which the stat is kept */
    private final String _groupName;
    /** describe the stat */
    private final String _description;
    /** actual frequency objects for this statistic */
    private final Frequency _frequencies[];

    public FrequencyStat(String name, String description, String group, long periods[]) {
        _statName = name;
        _description = description;
        _groupName = group;
        _frequencies = new Frequency[periods.length];
        for (int i = 0; i < periods.length; i++)
            _frequencies[i] = new Frequency(periods[i]);
    }

    /** update all of the frequencies for the various periods */
    public void eventOccurred() {
        for (int i = 0; i < _frequencies.length; i++)
            _frequencies[i].eventOccurred();
    }

    /**
     * coalesce all the stats
     */
    public void coalesceStats() {
        for (int i = 0; i < _frequencies.length; i++)
            _frequencies[i].recalculate();
    }

    public String getName() {
        return _statName;
    }

    public String getGroupName() {
        return _groupName;
    }

    public String getDescription() {
        return _description;
    }

    public long[] getPeriods() {
        long rv[] = new long[_frequencies.length];
        for (int i = 0; i < _frequencies.length; i++)
            rv[i] = _frequencies[i].getPeriod();
        return rv;
    }

    public Frequency getFrequency(long period) {
        for (int i = 0; i < _frequencies.length; i++) {
            if (_frequencies[i].getPeriod() == period) return _frequencies[i];
        }
        return null;
    }

    /**
     * @return lifetime event count
     * @since 0.8.2
     */	
    public long getEventCount() {
        if ( (_frequencies == null) || (_frequencies.length <= 0) ) return 0;
        return _frequencies[0].getEventCount();
    }

    /**
     * @return lifetime average frequency in millisedonds, i.e. the average time between events, or Long.MAX_VALUE if no events ever
     * @since 0.8.2
     */	
    public long getFrequency() {
        if ( (_frequencies == null) || (_frequencies.length <= 0) ) return Long.MAX_VALUE;
        double d = _frequencies[0].getStrictAverageInterval();
        if (d > _frequencies[0].getPeriod())
            return Long.MAX_VALUE;
        return Math.round(d);
    }

    @Override
    public int hashCode() {
        return _statName.hashCode();
    }

    /** @since 0.8.2 */
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof FrequencyStat)) return false;
        return _statName.equals(((FrequencyStat)obj)._statName);
    }

}
