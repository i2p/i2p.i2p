package net.i2p.stat;

/** coordinate an event frequency over various periods */
public class FrequencyStat {
    /** unique name of the statistic */
    private String _statName;
    /** grouping under which the stat is kept */
    private String _groupName;
    /** describe the stat */
    private String _description;
    /** actual frequency objects for this statistic */
    private Frequency _frequencies[];

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

    /** coalesce all the stats */
    public void coalesceStats() {
        //for (int i = 0; i < _frequencies.length; i++)
        //	_frequencies[i].coalesceStats();
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

    public int hashCode() {
        return _statName.hashCode();
    }
}
