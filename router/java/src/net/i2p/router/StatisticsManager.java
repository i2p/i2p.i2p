package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.OutputStream;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Maintain the statistics about the router
 *
 */
public class StatisticsManager implements Service {
    private Log _log;
    private RouterContext _context;
    private boolean _includePeerRankings;
    private int _publishedStats;
    
    public final static String PROP_PUBLISH_RANKINGS = "router.publishPeerRankings";
    public final static String DEFAULT_PROP_PUBLISH_RANKINGS = "false";
    public final static String PROP_MAX_PUBLISHED_PEERS = "router.publishPeerMax";
    public final static int DEFAULT_MAX_PUBLISHED_PEERS = 20;

    private final DecimalFormat _fmt;
    private final DecimalFormat _pct;

    public StatisticsManager(RouterContext context) {
        _context = context;
        _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
        _pct = new DecimalFormat("#0.00%", new DecimalFormatSymbols(Locale.UK));
        _log = context.logManager().getLog(StatisticsManager.class);
        _includePeerRankings = false;
    }
        
    public void shutdown() {}
    public void startup() {
        String val = _context.router().getConfigSetting(PROP_PUBLISH_RANKINGS);
        try {
            if (val == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer publishing setting " + PROP_PUBLISH_RANKINGS 
                              + " not set - using default " + DEFAULT_PROP_PUBLISH_RANKINGS);
                val = DEFAULT_PROP_PUBLISH_RANKINGS;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer publishing setting " + PROP_PUBLISH_RANKINGS 
                              + " set to " + val);
            }
            boolean v = Boolean.TRUE.toString().equalsIgnoreCase(val);
            _includePeerRankings = v;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Setting includePeerRankings = " + v);
        } catch (Throwable t) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error determining whether to publish rankings [" 
                           + PROP_PUBLISH_RANKINGS + "=" + val 
                           + "], so we're defaulting to FALSE"); 
            _includePeerRankings = false;
        }
        val = _context.router().getConfigSetting(PROP_MAX_PUBLISHED_PEERS);
        if (val == null) {
            _publishedStats = DEFAULT_MAX_PUBLISHED_PEERS;
        } else {
            try {
                int num = Integer.parseInt(val);
                _publishedStats = num;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid max number of peers to publish [" + val 
                               + "], defaulting to " + DEFAULT_MAX_PUBLISHED_PEERS, nfe);
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
            stats.putAll(_context.profileManager().summarizePeers(_publishedStats));

            includeThroughput(stats);
            includeRate("transport.sendProcessingTime", stats, new long[] { 60*1000, 60*60*1000 });
            //includeRate("tcp.queueSize", stats);
            includeRate("jobQueue.jobLag", stats, new long[] { 60*1000, 60*60*1000 });
            includeRate("jobQueue.jobRun", stats, new long[] { 60*1000, 60*60*1000 });
            includeRate("crypto.elGamal.encrypt", stats, new long[] { 60*1000, 60*60*1000 });
            includeRate("crypto.garlic.decryptFail", stats, new long[] { 60*60*1000, 24*60*60*1000 });
            includeRate("tunnel.unknownTunnelTimeLeft", stats, new long[] { 60*60*1000, 24*60*60*1000 });
            includeRate("jobQueue.readyJobs", stats, new long[] { 60*1000, 60*60*1000 });
            //includeRate("jobQueue.droppedJobs", stats, new long[] { 60*60*1000, 24*60*60*1000 });
            includeRate("inNetPool.dropped", stats, new long[] { 60*60*1000, 24*60*60*1000 });
            includeRate("tunnel.participatingTunnels", stats, new long[] { 5*60*1000, 60*60*1000 });
            includeRate("tunnel.testSuccessTime", stats, new long[] { 60*60*1000l, 24*60*60*1000l });
            includeRate("tunnel.outboundMessagesProcessed", stats, new long[] { 10*60*1000, 60*60*1000 });
            includeRate("tunnel.inboundMessagesProcessed", stats, new long[] { 10*60*1000, 60*60*1000 });
            includeRate("tunnel.participatingMessagesProcessed", stats, new long[] { 10*60*1000, 60*60*1000 });
            includeRate("tunnel.expiredAfterAcceptTime", stats, new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
            includeRate("tunnel.bytesAllocatedAtAccept", stats, new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
            includeRate("netDb.lookupsReceived", stats, new long[] { 5*60*1000, 60*60*1000 });
            includeRate("netDb.lookupsHandled", stats, new long[] { 5*60*1000, 60*60*1000 });
            includeRate("netDb.lookupsMatched", stats, new long[] { 5*60*1000, 60*60*1000 });
            includeRate("netDb.storeSent", stats, new long[] { 5*60*1000, 60*60*1000 });
            includeRate("netDb.successPeers", stats, new long[] { 60*60*1000 });
            includeRate("netDb.failedPeers", stats, new long[] { 60*60*1000 });
            includeRate("netDb.searchCount", stats, new long[] { 3*60*60*1000});
            //includeRate("inNetMessage.timeToDiscard", stats, new long[] { 5*60*1000, 10*60*1000, 60*60*1000 });
            //includeRate("outNetMessage.timeToDiscard", stats, new long[] { 5*60*1000, 10*60*1000, 60*60*1000 });
            includeRate("router.throttleNetworkCause", stats, new long[] { 10*60*1000, 60*60*1000 });
            includeRate("transport.receiveMessageSize", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.sendMessageSize", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.sendMessageSmall", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.sendMessageMedium", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.sendMessageLarge", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.receiveMessageSmall", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.receiveMessageMedium", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("transport.receiveMessageLarge", stats, new long[] { 5*60*1000, 60*60*1000 });
            includeRate("client.sendAckTime", stats, new long[] { 60*60*1000, 24*60*60*1000l }, true);
            stats.setProperty("stat_uptime", DataHelper.formatDuration(_context.router().getUptime()));
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
        includeRate(rateName, stats, null);
    }
    private void includeRate(String rateName, Properties stats, long selectedPeriods[]) {
        includeRate(rateName, stats, selectedPeriods, false);
    }
    /**
     * @param fudgeQuantity the data being published in this stat is too sensitive to, uh
     *                      publish, so we're kludge the quantity (allowing the fairly safe
     *                      publication of the average values
     */
    private void includeRate(String rateName, Properties stats, long selectedPeriods[], 
                             boolean fudgeQuantity) {
        RateStat rate = _context.statManager().getRate(rateName);
        if (rate == null) return;
        long periods[] = rate.getPeriods();
        for (int i = 0; i < periods.length; i++) {
            if (selectedPeriods != null) {
                boolean found = false;
                for (int j = 0; j < selectedPeriods.length; j++) {
                    if (selectedPeriods[j] == periods[i]) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
            }

            Rate curRate = rate.getRate(periods[i]);
            if (curRate == null) continue;
            stats.setProperty("stat_" + rateName + '.' + getPeriod(curRate), renderRate(curRate, fudgeQuantity));
        }
    }
    
    private String renderRate(Rate rate, boolean fudgeQuantity) {
        StringBuffer buf = new StringBuffer(128);
        buf.append(num(rate.getAverageValue())).append(';');
        buf.append(num(rate.getExtremeAverageValue())).append(';');
        buf.append(pct(rate.getPercentageOfLifetimeValue())).append(';');
        if (rate.getLifetimeTotalEventTime() > 0) {
            buf.append(pct(rate.getLastEventSaturation())).append(';');
            buf.append(num(rate.getLastSaturationLimit())).append(';');
            buf.append(pct(rate.getExtremeEventSaturation())).append(';');
            buf.append(num(rate.getExtremeSaturationLimit())).append(';');
        }
        long numPeriods = rate.getLifetimePeriods();
        if (fudgeQuantity) {
            buf.append("666").append(';');
            if (numPeriods > 0) {
                buf.append("666").append(';');
                buf.append("666").append(';');
            }
        } else {
            buf.append(num(rate.getLastEventCount())).append(';');
            if (numPeriods > 0) {
                double avgFrequency = rate.getLifetimeEventCount() / (double)numPeriods;
                double peakFrequency = rate.getExtremeEventCount();
                buf.append(num(avgFrequency)).append(';');
                buf.append(num(rate.getExtremeEventCount())).append(';');
            }
        }
        return buf.toString();
    }

    private String renderThroughput(double bytes, long ms) {
        if (bytes <= 0) 
            return "0;0;0;0;";
        else
            return num(bytes/(ms/1000)) + ";0;0;0;";
    }
    
    private void includeThroughput(Properties stats) {
        double sendBytes5m = 0;
        double sendBytes60m = 0;
        double recvBytes5m = 0;
        double recvBytes60m = 0;
        
        RateStat sendRate = _context.statManager().getRate("transport.sendMessageSize");
        if (sendRate != null) {
            Rate r = sendRate.getRate(5*60*1000);
            if (r != null)
                sendBytes5m = r.getLastTotalValue();
            r = sendRate.getRate(60*60*1000);
            if (r != null)
                sendBytes60m = r.getLastTotalValue();
        }
        
        RateStat recvRate = _context.statManager().getRate("transport.receiveMessageSize");
        if (recvRate != null) {
            Rate r = recvRate.getRate(5*60*1000);
            if (r != null)
                recvBytes5m = r.getLastTotalValue();
            r = recvRate.getRate(60*60*1000);
            if (r != null)
                recvBytes60m = r.getLastTotalValue();
        }
        
        String throughputRate = renderThroughput(sendBytes5m, 5*60*1000);
        stats.setProperty("stat_bandwidthSendBps.5m", throughputRate);
        throughputRate = renderThroughput(sendBytes60m, 60*60*1000);
        stats.setProperty("stat_bandwidthSendBps.60m", throughputRate);
        throughputRate = renderThroughput(recvBytes5m, 5*60*1000);
        stats.setProperty("stat_bandwidthReceiveBps.5m", throughputRate);
        throughputRate = renderThroughput(recvBytes60m, 60*60*1000);
        stats.setProperty("stat_bandwidthReceiveBps.60m", throughputRate);
    }

    
    private String getPeriod(Rate rate) { return DataHelper.formatDuration(rate.getPeriod()); }

    private final String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final String pct(double num) { synchronized (_pct) { return _pct.format(num); } }
   
    public void renderStatusHTML(OutputStream out) { }
}
