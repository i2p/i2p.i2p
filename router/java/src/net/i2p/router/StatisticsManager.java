package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;

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
    public final static String DEFAULT_PROP_PUBLISH_RANKINGS = "true";
    public final static String PROP_MAX_PUBLISHED_PEERS = "router.publishPeerMax";
    public final static int DEFAULT_MAX_PUBLISHED_PEERS = 10;

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
    public void restart() { 
        startup();
    }
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
        stats.setProperty("coreVersion", CoreVersion.VERSION);

        // No longer expose, to make build tracking more expensive
        // stats.setProperty("router.id", RouterVersion.ID);
        // stats.setProperty("core.id", CoreVersion.ID);

        int newlines = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream(Router.IDENTLOG);
            int c = -1;
            // perhaps later filter this to only include ident changes this
            // day/week/month
            while ( (c = in.read()) != -1) {
                if (c == '\n')
                    newlines++;
            }
        } catch (IOException ioe) {
            // ignore
        } finally {
            if (in != null)
                try { in.close(); } catch (IOException ioe) {}
        }
        if (newlines > 0)
            stats.setProperty("stat_identities", newlines+"");

        
        if (_includePeerRankings) {
            if (false)
                stats.putAll(_context.profileManager().summarizePeers(_publishedStats));

            includeThroughput(stats);
            //includeRate("router.invalidMessageTime", stats, new long[] { 10*60*1000 });
            //includeRate("router.duplicateMessageId", stats, new long[] { 24*60*60*1000 });
            //includeRate("tunnel.duplicateIV", stats, new long[] { 24*60*60*1000 });
            //includeRate("tunnel.fragmentedDropped", stats, new long[] { 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.fullFragments", stats, new long[] { 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.smallFragments", stats, new long[] { 10*60*1000, 3*60*60*1000 });
            includeRate("tunnel.testFailedTime", stats, new long[] { 10*60*1000 });
            
            includeRate("tunnel.buildFailure", stats, new long[] { 60*60*1000 });
            includeRate("tunnel.buildSuccess", stats, new long[] { 60*60*1000 });

            //includeRate("tunnel.batchDelaySent", stats, new long[] { 10*60*1000, 60*60*1000 });
            //includeRate("tunnel.batchMultipleCount", stats, new long[] { 10*60*1000, 60*60*1000 });
            includeRate("tunnel.corruptMessage", stats, new long[] { 60*60*1000l, 3*60*60*1000l });
            
            //includeRate("router.throttleTunnelProbTestSlow", stats, new long[] { 60*60*1000 });
            //includeRate("router.throttleTunnelProbTooFast", stats, new long[] { 60*60*1000 });
            //includeRate("router.throttleTunnelProcessingTime1m", stats, new long[] { 60*60*1000 });

            includeRate("router.fastPeers", stats, new long[] { 60*60*1000 });
            
            //includeRate("udp.statusOK", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusDifferent", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusReject", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusUnknown", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusKnownCharlie", stats, new long[] { 1*60*1000, 10*60*1000 });
            //includeRate("udp.addressUpdated", stats, new long[] { 1*60*1000 });
            //includeRate("udp.addressTestInsteadOfUpdate", stats, new long[] { 1*60*1000 });

            includeRate("clock.skew", stats, new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*1000 });
            
            //includeRate("transport.sendProcessingTime", stats, new long[] { 60*60*1000 });
            //includeRate("jobQueue.jobRunSlow", stats, new long[] { 10*60*1000l, 60*60*1000l });
            includeRate("crypto.elGamal.encrypt", stats, new long[] { 60*1000, 60*60*1000 });
            includeRate("tunnel.participatingTunnels", stats, new long[] { 5*60*1000, 60*60*1000 });
            //includeRate("tunnel.testSuccessTime", stats, new long[] { 10*60*1000l });
            includeRate("client.sendAckTime", stats, new long[] { 60*60*1000 }, true);
            //includeRate("udp.sendConfirmTime", stats, new long[] { 10*60*1000 });
            //includeRate("udp.sendVolleyTime", stats, new long[] { 10*60*1000 });
            //includeRate("udp.ignoreRecentDuplicate", stats, new long[] { 60*1000 });
            //includeRate("udp.congestionOccurred", stats, new long[] { 10*60*1000 });
            //includeRate("stream.con.sendDuplicateSize", stats, new long[] { 60*60*1000 });
            //includeRate("stream.con.receiveDuplicateSize", stats, new long[] { 60*60*1000 });

            // Round smaller uptimes to 1 hour, to frustrate uptime tracking
            long publishedUptime = _context.router().getUptime();
            if (publishedUptime < 60*60*1000) publishedUptime = 60*60*1000;

            stats.setProperty("stat_uptime", DataHelper.formatDuration(publishedUptime));
            //stats.setProperty("stat__rateKey", "avg;maxAvg;pctLifetime;[sat;satLim;maxSat;maxSatLim;][num;lifetimeFreq;maxFreq]");
            
            includeRate("tunnel.buildRequestTime", stats, new long[] { 60*1000, 10*60*1000 });
            //includeRate("tunnel.decryptRequestTime", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.buildClientExpire", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.buildClientReject", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.buildClientSuccess", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.buildExploratoryExpire", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.buildExploratoryReject", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.buildExploratorySuccess", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.rejectTimeout", stats, new long[] { 60*1000, 10*60*1000 });
            //includeRate("udp.packetDequeueTime", stats, new long[] { 60*1000 });
            //includeRate("udp.packetVerifyTime", stats, new long[] { 60*1000 });
            
            includeRate("tunnel.rejectOverloaded", stats, new long[] { 60*1000, 10*60*1000 });
            includeRate("tunnel.acceptLoad", stats, new long[] { 60*1000, 10*60*1000 });
            
            if (FloodfillNetworkDatabaseFacade.isFloodfill(_context.router().getRouterInfo())) {
                stats.setProperty("netdb.knownRouters", ""+_context.netDb().getKnownRouters());
                stats.setProperty("netdb.knownLeaseSets", ""+_context.netDb().getKnownLeaseSets());
            }
            
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
            if (periods[i] > _context.router().getUptime()) continue;
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
            if (curRate.getLifetimeEventCount() <= 0) continue;
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
                buf.append(num((double)rate.getLifetimeEventCount())).append(';');
            }
        }
        return buf.toString();
    }

    private void includeThroughput(Properties stats) {
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        if (sendRate != null) {
            if (_context.router().getUptime() > 5*60*1000) {
                Rate r = sendRate.getRate(5*60*1000);
                if (r != null)
                    stats.setProperty("stat_bandwidthSendBps.5m", num(r.getAverageValue()) + ';' + num(r.getExtremeAverageValue()) + ";0;0;");
            }
            if (_context.router().getUptime() > 60*60*1000) { 
                Rate r = sendRate.getRate(60*60*1000);
                if (r != null)
                    stats.setProperty("stat_bandwidthSendBps.60m", num(r.getAverageValue()) + ';' + num(r.getExtremeAverageValue()) + ";0;0;");
            }
        }
        
        RateStat recvRate = _context.statManager().getRate("bw.recvRate");
        if (recvRate != null) {
            if (_context.router().getUptime() > 5*60*1000) {
                Rate r = recvRate.getRate(5*60*1000);
                if (r != null)
                    stats.setProperty("stat_bandwidthReceiveBps.5m", num(r.getAverageValue()) + ';' + num(r.getExtremeAverageValue()) + ";0;0;");
            }
            if (_context.router().getUptime() > 60*60*1000) {
                Rate r = recvRate.getRate(60*60*1000);
                if (r != null)
                    stats.setProperty("stat_bandwidthReceiveBps.60m", num(r.getAverageValue()) + ';' + num(r.getExtremeAverageValue()) + ";0;0;");
            }
        }
    }

    
    private String getPeriod(Rate rate) { return DataHelper.formatDuration(rate.getPeriod()); }

    private final String num(double num) { 
        if (num < 0) num = 0;
        synchronized (_fmt) { return _fmt.format(num); } 
    }
    private final String pct(double num) { 
        if (num < 0) num = 0;
        synchronized (_pct) { return _pct.format(num); } 
    }
   
    public void renderStatusHTML(Writer out) { }
}
