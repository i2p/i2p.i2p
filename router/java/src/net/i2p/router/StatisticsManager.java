package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;

/**
 * Maintain the statistics about the router
 *
 */
public class StatisticsManager implements Service {
    private final static Log _log = new Log(StatisticsManager.class);
    private static StatisticsManager _instance = new StatisticsManager();
    public static StatisticsManager getInstance() { return _instance; }
    private boolean _includePeerRankings;
    private int _publishedStats;
    
    public final static String PROP_PUBLISH_RANKINGS = "router.publishPeerRankings";
    public final static String DEFAULT_PROP_PUBLISH_RANKINGS = "false";
    public final static String PROP_MAX_PUBLISHED_PEERS = "router.publishPeerMax";
    public final static int DEFAULT_MAX_PUBLISHED_PEERS = 20;
    
    public StatisticsManager() {
	_includePeerRankings = false;
    }
        
    public void shutdown() {}
    public void startup() {
	String val = Router.getInstance().getConfigSetting(PROP_PUBLISH_RANKINGS);
	try {
	    if (val == null) {
		_log.info("Peer publishing setting " + PROP_PUBLISH_RANKINGS + " not set - using default " + DEFAULT_PROP_PUBLISH_RANKINGS);
		val = DEFAULT_PROP_PUBLISH_RANKINGS;
	    } else {
		_log.info("Peer publishing setting " + PROP_PUBLISH_RANKINGS + " set to " + val);
	    }
	    boolean v = Boolean.TRUE.toString().equalsIgnoreCase(val);
	    _includePeerRankings = v;
	    _log.debug("Setting includePeerRankings = " + v);
	} catch (Throwable t) {
	    _log.error("Error determining whether to publish rankings [" + PROP_PUBLISH_RANKINGS + "=" + val + "], so we're defaulting to FALSE"); 
	    _includePeerRankings = false;
	}
	val = Router.getInstance().getConfigSetting(PROP_MAX_PUBLISHED_PEERS);
	if (val == null) {
	    _publishedStats = DEFAULT_MAX_PUBLISHED_PEERS;
	} else {
	    try {
	    	int num = Integer.parseInt(val);
		_publishedStats = num;
	    } catch (NumberFormatException nfe) {
		_log.error("Invalid max number of peers to publish [" + val + "], defaulting to " + DEFAULT_MAX_PUBLISHED_PEERS, nfe);
		_publishedStats = DEFAULT_MAX_PUBLISHED_PEERS;
	    }
	}
    }  
    
    /** Retrieve a snapshot of the statistics that should be published */
    public Properties publishStatistics() { 
	Properties stats = new Properties();
	stats.setProperty("router.version", RouterVersion.VERSION);
	stats.setProperty("router.id", RouterVersion.ID);
	stats.setProperty("coreVersion", CoreVersion.VERSION);
	stats.setProperty("core.id", CoreVersion.ID);
	
	if (_includePeerRankings) {
	    stats.putAll(ProfileManager.getInstance().summarizePeers(_publishedStats));
	    
	    includeRate("transport.sendProcessingTime", stats);
	    includeRate("tcp.queueSize", stats);
	    includeRate("jobQueue.jobLag", stats);
	    includeRate("jobQueue.jobRun", stats);
	    includeRate("crypto.elGamal.encrypt", stats);
	    includeRate("jobQueue.readyJobs", stats);
	    includeRate("jobQueue.droppedJobs", stats);
	    stats.setProperty("stat_uptime", DataHelper.formatDuration(Router.getInstance().getUptime()));
	    stats.setProperty("stat__rateKey", "avg;maxAvg;pctLifetime;[sat;satLim;maxSat;maxSatLim;][num;lifetimeFreq;maxFreq]");
	    _log.debug("Publishing peer rankings");
	} else {
	    _log.debug("Not publishing peer rankings");
	}
	
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Building status: " + stats);
	return stats;
    }
    
    private void includeRate(String rateName, Properties stats) {
	RateStat rate = StatManager.getInstance().getRate(rateName);
	if (rate == null) return;
	for (int i = 0; i < rate.getPeriods().length; i++) {
	    Rate curRate = rate.getRate(rate.getPeriods()[i]);
	    if (curRate == null) continue;
	    stats.setProperty("stat_" + rateName + '.' + getPeriod(curRate), renderRate(curRate));
	}
    }
    
    private static String renderRate(Rate rate) {
	StringBuffer buf = new StringBuffer(255);
	buf.append(num(rate.getAverageValue())).append(';');
	buf.append(num(rate.getExtremeAverageValue())).append(';');
	buf.append(pct(rate.getPercentageOfLifetimeValue())).append(';');
	if (rate.getLifetimeTotalEventTime() > 0) {
	    buf.append(pct(rate.getLastEventSaturation())).append(';');
	    buf.append(num(rate.getLastSaturationLimit())).append(';');
	    buf.append(pct(rate.getExtremeEventSaturation())).append(';');
	    buf.append(num(rate.getExtremeSaturationLimit())).append(';');
	}
	buf.append(num(rate.getLastEventCount())).append(';');
	long numPeriods = rate.getLifetimePeriods();
	if (numPeriods > 0) {
	    double avgFrequency = rate.getLifetimeEventCount() / (double)numPeriods;
	    double peakFrequency = rate.getExtremeEventCount();
	    buf.append(num(avgFrequency)).append(';');
	    buf.append(num(rate.getExtremeEventCount())).append(';');
	}
	return buf.toString();
    }
    
    private static String getPeriod(Rate rate) { return DataHelper.formatDuration(rate.getPeriod()); }

    // TODO: get this to use some random locale, not the user's default (since its published)
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    
    private final static DecimalFormat _pct = new DecimalFormat("#0.00%", new DecimalFormatSymbols(Locale.UK));
    private final static String pct(double num) { synchronized (_pct) { return _pct.format(num); } }
   
    
    public String renderStatusHTML() { return ""; }
}
