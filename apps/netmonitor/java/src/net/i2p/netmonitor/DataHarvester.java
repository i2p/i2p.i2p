package net.i2p.netmonitor;

import net.i2p.data.RouterInfo;
import net.i2p.util.Log;
import net.i2p.util.Clock;
import java.util.Properties;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Locale;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;

/**
 * Pull out important data from the published routerInfo and stash it away
 * in the netMonitor
 *
 */
class DataHarvester {
    private static final Log _log = new Log(DataHarvester.class);
    private static final DataHarvester _instance = new DataHarvester();
    public static final DataHarvester getInstance() { return _instance; }
    
    protected DataHarvester() {}
    
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
        harvestEncryptionTime(monitor, peer);
        harvestDroppedJobs(monitor, peer);
        harvestProcessingTime(monitor, peer);
    }
    
    /**
     *  How does the peer rank other routers?  Stored in the peer summary as 
     * "rankAs", containing 4 longs (numFast, numReliable, numNotFailing, numFailing)
     *
     * @param peer who is doing the ranking
     */
    private void harvestRankAs(NetMonitor monitor, RouterInfo peer) {
        int numFast = 0;
        int numReliable = 0;
        int numNotFailing = 0;
        int numFailing = 0;
        
        Properties props = peer.getOptions();
        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            if (key.startsWith("profile.")) {
                String val = (String)props.get(key);
                if (val.indexOf("fastReliable") != -1)
                    numFast++;
                else if (val.indexOf("reliable") != -1)
                    numReliable++;
                else if (val.indexOf("notFailing") != -1)
                    numNotFailing++;
                else if (val.indexOf("failing") != -1)
                    numFailing++;
            }
        }
        
        long rankAs[] = new long[4];
        rankAs[0] = numFast;
        rankAs[1] = numReliable;
        rankAs[2] = numNotFailing;
        rankAs[3] = numFailing;
        String description = "how we rank peers"; 
        String valDescr[] = new String[4];
        valDescr[0] = "# peers we rank as fast";
        valDescr[1] = "# peers we rank as reliable";
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
        int numReliable = 0;
        int numNotFailing = 0;
        int numFailing = 0;
        
        // now count 'em
        for (int i = 0; i < peers.size(); i++) {
            RouterInfo cur = (RouterInfo)peers.get(i);
            if (peer == cur) continue;
            String prop = "profile." + peer.getIdentity().getHash().toBase64().replace('=', '_');
            String val = cur.getOptions().getProperty(prop);
            if ( (val == null) || (val.length() <= 0) ) continue;
            if (val.indexOf("fastReliable") != -1)
                numFast++;
            else if (val.indexOf("reliable") != -1)
                numReliable++;
            else if (val.indexOf("notFailing") != -1)
                numNotFailing++;
            else if (val.indexOf("failing") != -1)
                numFailing++;
        }
        
        long rank[] = new long[4];
        rank[0] = numFast;
        rank[1] = numReliable;
        rank[2] = numNotFailing;
        rank[3] = numFailing;
        String description = "how peers rank us";
        String valDescr[] = new String[4];
        valDescr[0] = "# peers ranking us as fast";
        valDescr[1] = "# peers ranking us as reliable";
        valDescr[2] = "# peers ranking us as not failing";
        valDescr[3] = "# peers ranking us as failing";
        // we use the current date, not the published date, since this sample doesnt come from them
        monitor.addData(peer.getIdentity().getHash().toBase64(), "rank", description, valDescr, Clock.getInstance().now(), rank);
    }
    
    /**
     * How long does it take the peer to perform an elGamal encryption?  Stored in 
     * the peer summary as "encryptTime", containing 4 doubles (numMs for 1 minute,
     * quantity in the last minute, numMs for 1 hour, quantity in the last hour)
     *
     * @param peer who are we checking the encryption time of
     */
    private void harvestEncryptionTime(NetMonitor monitor, RouterInfo peer) {
        double minuteMs = getDouble(peer, "stat_crypto.elGamal.encrypt.60s", 0);
        double hourMs = getDouble(peer, "stat_crypto.elGamal.encrypt.60m", 0);
        double minuteQuantity = getDouble(peer, "stat_crypto.elGamal.encrypt.60s", 7);
        double hourQuantity = getDouble(peer, "stat_crypto.elGamal.encrypt.60m", 7);
        if ( (minuteMs == -1) || (hourMs == -1) || (minuteQuantity == -1) || (hourQuantity == -1) )
            return;
        
        double times[] = new double[4];
        times[0] = minuteMs;
        times[1] = minuteQuantity;
        times[2] = hourMs;
        times[3] = hourQuantity;
        
        String description = "how long it takes to do an ElGamal encryption";
        String valDescr[] = new String[4];
        valDescr[0] = "encryption time avg ms (minute)";
        valDescr[1] = "# encryptions (minute)";
        valDescr[2] = "encryption time avg ms (hour)";
        valDescr[3] = "# encryptions (hour)";
        monitor.addData(peer.getIdentity().getHash().toBase64(), "encryptTime", description, valDescr, peer.getPublished(), times);
    }
    
    /**
     * How jobs has the peer dropped in the last minute / hour?  Stored in 
     * the peer summary as "droppedJobs", containing 2 doubles (num jobs for 1 minute,
     * num jobs for 1 hour)
     *
     * @param peer who are we checking the frequency of dropping jobs for
     */
    private void harvestDroppedJobs(NetMonitor monitor, RouterInfo peer) {
        double minute = getDouble(peer, "stat_jobQueue.droppedJobs.60s", 0);
        double hour = getDouble(peer, "stat_jobQueue.droppedJobs.60m", 0);
        double quantity[] = new double[2];
        quantity[0] = minute;
        quantity[1] = hour;
        if ( (minute == -1) || (hour == -1) )
            return;
        
        String valDescr[] = new String[2];
        valDescr[0] = "# dropped jobs (minute)";
        valDescr[1] = "# dropped jobs (hour)";
        String description = "how many dropped jobs";
        monitor.addData(peer.getIdentity().getHash().toBase64(), "droppedJobs", description, valDescr, peer.getPublished(), quantity);
    }

    /**
     * How long does it take to process an outbound message?  Stored in 
     * the peer summary as "processingTime", containing 4 doubles (avg ms for 1 minute,
     * num messages for 1 minute, avg ms for 1 hour, num messages for 1 hour)
     *
     * @param peer who are we checking the frequency of dropping jobs for
     */
    private void harvestProcessingTime(NetMonitor monitor, RouterInfo peer) {
        double minuteMs = getDouble(peer, "stat_transport.sendProcessingTime.60s", 0);
        double minuteFreq = getDouble(peer, "stat_transport.sendProcessingTime.60s", 7);
        double hourMs = getDouble(peer, "stat_transport.sendProcessingTime.60m", 0);
        double hourFreq = getDouble(peer, "stat_transport.sendProcessingTime.60m", 7);
        if ( (minuteMs == -1) || (hourMs == -1) || (minuteFreq == -1) || (hourFreq == -1) )
            return;
        
        double times[] = new double[4];
        times[0] = minuteMs;
        times[1] = minuteFreq;
        times[2] = hourMs;
        times[3] = hourFreq;
        
        String valDescr[] = new String[4];
        valDescr[0] = "process time avg ms (minute)";
        valDescr[1] = "process events (minute)";
        valDescr[2] = "process time avg ms (hour)";
        valDescr[3] = "process events (hour)";
        String description = "how long does it take to process a message";
        monitor.addData(peer.getIdentity().getHash().toBase64(), "processingTime", description, valDescr, peer.getPublished(), times);
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