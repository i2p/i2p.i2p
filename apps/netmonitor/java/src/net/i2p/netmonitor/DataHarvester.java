package net.i2p.netmonitor;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.data.RouterInfo;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Pull out important data from the published routerInfo and stash it away
 * in the netMonitor
 *
 */
class DataHarvester {
    private static final Log _log = new Log(DataHarvester.class);
    private static final DataHarvester _instance = new DataHarvester();
    public static final DataHarvester getInstance() { return _instance; }
    /**
     * Contains the list of StatGroup objects loaded from the harvest.config file
     * {@see StatGroupLoader} where each statGroup defines a set of stats to pull
     * from each router's options.
     *
     */
    private List _statGroups;
    
    /**
     * Where are we reading the stat groups from?  For now, "harvester.config".
     */
    private static final String STAT_GROUP_CONFIG_FILENAME = "harvester.config";
    
    protected DataHarvester() {
        _statGroups = StatGroupLoader.loadStatGroups(STAT_GROUP_CONFIG_FILENAME);
    }
    
    /**
     * Harvest all of the data from the peers and store it in the monitor.
     *
     * @param peers list of RouterInfo structures to harvest from
     */
    public void harvestData(NetMonitor monitor, List peers) {
        for (int i = 0; i < peers.size(); i++) {
            harvestData(monitor, (RouterInfo)peers.get(i), peers);
        }
    }
    
    /**
     * Pull out all the data we can for the specified peer
     *
     * @param peer who are we focusing on in this pass
     * @param peers everyone on the network, to co
     */
    private void harvestData(NetMonitor monitor, RouterInfo peer, List peers) {
        _log.info("Harvest the data from " + peer.getIdentity().getHash().toBase64());
        harvestRank(monitor, peer, peers);
        harvestRankAs(monitor, peer);
        harvestGroups(monitor, peer);
    }
    
    /**
     *  How does the peer rank other routers?  Stored in the peer summary as 
     * "rankAs", containing 4 longs (numFast, numReliable, numNotFailing, numFailing)
     *
     * @param peer who is doing the ranking
     */
    private void harvestRankAs(NetMonitor monitor, RouterInfo peer) {
        int numFast = 0;
        int numHighCapacity = 0;
        int numNotFailing = 0;
        int numFailing = 0;
        
        Properties props = peer.getOptions();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            if (key.startsWith("profile.")) {
                String val = (String)props.get(key);
                if (val.indexOf("fast") != -1)
                    numFast++;
                else if (val.indexOf("highCapacity") != -1)
                    numHighCapacity++;
                else if (val.indexOf("notFailing") != -1)
                    numNotFailing++;
                else if (val.indexOf("failing") != -1)
                    numFailing++;
            }
        }
        
        long rankAs[] = new long[4];
        rankAs[0] = numFast;
        rankAs[1] = numHighCapacity;
        rankAs[2] = numNotFailing;
        rankAs[3] = numFailing;
        String description = "how we rank peers"; 
        String valDescr[] = new String[4];
        valDescr[0] = "# peers we rank as fast";
        valDescr[1] = "# peers we rank as high capacity";
        valDescr[2] = "# peers we rank as not failing";
        valDescr[3] = "# peers we rank as failing";
        monitor.addData(peer.getIdentity().getHash().toBase64(), "rankAs", description, valDescr, peer.getPublished(), rankAs);
    }
    
    /**
     * How do other peers rank the current peer?  Stored in the peer summary as 
     * "rank", containing 4 longs (numFast, numReliable, numNotFailing, numFailing)
     *
     * @param peer who do we want to check the network's perception of
     * @param peers peers whose rankings we will use
     */
    private void harvestRank(NetMonitor monitor, RouterInfo peer, List peers) {
        int numFast = 0;
        int numHighCapacity = 0;
        int numNotFailing = 0;
        int numFailing = 0;
        
        // now count 'em
        for (int i = 0; i < peers.size(); i++) {
            RouterInfo cur = (RouterInfo)peers.get(i);
            if (peer == cur) continue;
            String prop = "profile." + peer.getIdentity().getHash().toBase64().replace('=', '_');
            String val = cur.getOptions().getProperty(prop);
            if ( (val == null) || (val.length() <= 0) ) continue;
            if (val.indexOf("fast") != -1)
                numFast++;
            else if (val.indexOf("highCapacity") != -1)
                numHighCapacity++;
            else if (val.indexOf("notFailing") != -1)
                numNotFailing++;
            else if (val.indexOf("failing") != -1)
                numFailing++;
        }
        
        long rank[] = new long[4];
        rank[0] = numFast;
        rank[1] = numHighCapacity;
        rank[2] = numNotFailing;
        rank[3] = numFailing;
        String description = "how peers rank us";
        String valDescr[] = new String[4];
        valDescr[0] = "# peers ranking us as fast";
        valDescr[1] = "# peers ranking us as high capacity";
        valDescr[2] = "# peers ranking us as not failing";
        valDescr[3] = "# peers ranking us as failing";
        // we use the current date, not the published date, since this sample doesnt come from them
        monitor.addData(peer.getIdentity().getHash().toBase64(), "rank", description, valDescr, Clock.getInstance().now(), rank);
    }
    
    /**
     * Harvest all data points from the peer 
     * 
     */
    private void harvestGroups(NetMonitor monitor, RouterInfo peer) {
        _log.debug("Harvesting group data for " + peer.getIdentity().getHash().toBase64());
        for (int i = 0; i < _statGroups.size(); i++) {
            StatGroup group = (StatGroup)_statGroups.get(i);
            harvestGroup(monitor, peer, group);
        }
    }
    
    /**
     * Harvest the data points for the given group from the peer and toss them 
     * into the monitor
     *
     */
    private void harvestGroup(NetMonitor monitor, RouterInfo peer, StatGroup group) {
        _log.debug("Harvesting group data for " + peer.getIdentity().getHash().toBase64() + " / " + group.getDescription());
        double values[] = harvestGroupValues(peer, group);
        if (values == null) return;
           
        String valDescr[] = new String[group.getStatCount()];
        for (int i = 0; i < group.getStatCount(); i++)
            valDescr[i] = group.getStat(i).getStatDescription();
        monitor.addData(peer.getIdentity().getHash().toBase64(), group.getDescription(), group.getDescription(), valDescr, peer.getPublished(), values);
    }
    
    /** 
     * Pull up a list of all values associated with the group (in the order that the 
     * group specifies).  
     *
     * @return values or null on error
     */
    private double[] harvestGroupValues(RouterInfo peer, StatGroup group) {
        List values = new ArrayList(8);
        for (int i = 0; i < group.getStatCount(); i++) {
            StatGroup.StatDescription stat = group.getStat(i);
            double val = getDouble(peer, stat.getOptionName(), stat.getOptionField());
            if (val == -1) 
                return null;
            else
                values.add(new Double(val));
        }
        double rv[] = new double[values.size()];
        for (int i = 0; i < values.size(); i++)
            rv[i] = ((Double)values.get(i)).doubleValue();
        return rv;
    }
    
    /**
     * Pull a value from the peer's option as a double, assuming the standard semicolon
     * delimited formatting
     *
     * @param peer peer to query
     * @param key peer option to check
     * @param index 0-based index into the semicolon delimited values to pull out
     * @return value, or -1 if there was an error
     */
    private static final double getDouble(RouterInfo peer, String key, int index) {
        String val = peer.getOptions().getProperty(key);
        if (val == null) return -1;
        StringTokenizer tok = new StringTokenizer(val, ";");
        for (int i = 0; i < index; i++) {
            if (!tok.hasMoreTokens()) return -1;
            tok.nextToken(); // ignore
        }
        if (!tok.hasMoreTokens()) return -1;
        String cur = tok.nextToken();
        try {
            return getDoubleValue(cur);
        } catch (ParseException pe) {
            _log.warn("Unable to parse out the double from field " + index + " out of " + val + " for " + key, pe);
            return -1;
        } 
    }
 
    /** this mimics the format used in the router's StatisticsManager */
    private static final DecimalFormat _numFmt = new DecimalFormat("###,###,###,###,##0.00", new DecimalFormatSymbols(Locale.UK));

    /**
     * Converts a number (double) to text
     * @param val the number to convert
     * @return the textual representation
     */
    private static final double getDoubleValue(String val) throws ParseException {
        synchronized (_numFmt) {
            Number n = _numFmt.parse(val);
            return n.doubleValue();
        }
    }
}