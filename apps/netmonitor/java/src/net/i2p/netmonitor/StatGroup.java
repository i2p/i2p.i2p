package net.i2p.netmonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Stupid little structure to configure the DataHarvester's gathering of statistics.
 *
 */
public class StatGroup {
    private String _groupDescription;
    private List _stats;
    
    public StatGroup(String description) {
        _groupDescription = description;
        _stats = new ArrayList();
    }
    
    public String getDescription() { return _groupDescription; }
    public int getStatCount() { return _stats.size(); }
    public StatDescription getStat(int index) { return (StatDescription)_stats.get(index); }
    public void addStat(String description, String optionName, int optionField) {
        StatDescription descr = new StatDescription(description, optionName, optionField);
        _stats.add(descr);
    }
    
    public class StatDescription {
        private String _statDescription;
        private String _optionName;
        private int _optionField;
        
        public StatDescription(String descr, String optionName, int optionField) {
            _statDescription = descr;
            _optionName = optionName;
            _optionField = optionField;
        }
        
        /** brief description of this data point */
        public String getStatDescription() { return _statDescription; }
        /** 
         * if this is harvested from the RouterInfo's options, this specifies
         * which key in that map to pull from (or null if it isn't harvested
         * from there)
         */
        public String getOptionName() { return _optionName; }
        /** 
         * if this is harvested from the RouterInfo's options, this specifies
         * which field in value of that map to pull from (or -1 if it isn't harvested
         * from there)
         */
        public int getOptionField() { return _optionField; }
    }
}