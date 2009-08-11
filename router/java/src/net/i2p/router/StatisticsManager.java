package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Writer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
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
    
    public final static String PROP_PUBLISH_RANKINGS = "router.publishPeerRankings";
    public final static String DEFAULT_PROP_PUBLISH_RANKINGS = "true";

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
        String val = _context.getProperty(PROP_PUBLISH_RANKINGS, DEFAULT_PROP_PUBLISH_RANKINGS);
        _includePeerRankings = Boolean.valueOf(val);
    }
    
    /** Retrieve a snapshot of the statistics that should be published */
    public Properties publishStatistics() { 
        Properties stats = new Properties();
        stats.setProperty("router.version", RouterVersion.VERSION);
        stats.setProperty("coreVersion", CoreVersion.VERSION);

        // No longer expose, to make build tracking more expensive
        // stats.setProperty("router.id", RouterVersion.ID);
        // stats.setProperty("core.id", CoreVersion.ID);

/***
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
***/
        
        if (_includePeerRankings) {
            long publishedUptime = _context.router().getUptime();
            // Don't publish these for first hour
            if (publishedUptime > 62*60*1000)
                includeAverageThroughput(stats);
            //includeRate("router.invalidMessageTime", stats, new long[] { 10*60*1000 });
            //includeRate("router.duplicateMessageId", stats, new long[] { 24*60*60*1000 });
            //includeRate("tunnel.duplicateIV", stats, new long[] { 24*60*60*1000 });
            //includeRate("tunnel.fragmentedDropped", stats, new long[] { 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.fullFragments", stats, new long[] { 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.smallFragments", stats, new long[] { 10*60*1000, 3*60*60*1000 });
            //includeRate("tunnel.testFailedTime", stats, new long[] { 10*60*1000 });
            
            //includeRate("tunnel.batchDelaySent", stats, new long[] { 10*60*1000, 60*60*1000 });
            //includeRate("tunnel.batchMultipleCount", stats, new long[] { 10*60*1000, 60*60*1000 });
            //includeRate("tunnel.corruptMessage", stats, new long[] { 60*60*1000l, 3*60*60*1000l });
            
            //includeRate("router.throttleTunnelProbTestSlow", stats, new long[] { 60*60*1000 });
            //includeRate("router.throttleTunnelProbTooFast", stats, new long[] { 60*60*1000 });
            //includeRate("router.throttleTunnelProcessingTime1m", stats, new long[] { 60*60*1000 });

            //includeRate("router.fastPeers", stats, new long[] { 60*60*1000 });
            
            //includeRate("udp.statusOK", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusDifferent", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusReject", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusUnknown", stats, new long[] { 20*60*1000 });
            //includeRate("udp.statusKnownCharlie", stats, new long[] { 1*60*1000, 10*60*1000 });
            //includeRate("udp.addressUpdated", stats, new long[] { 1*60*1000 });
            //includeRate("udp.addressTestInsteadOfUpdate", stats, new long[] { 1*60*1000 });

            //includeRate("clock.skew", stats, new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*1000 });
            
            //includeRate("transport.sendProcessingTime", stats, new long[] { 60*60*1000 });
            //includeRate("jobQueue.jobRunSlow", stats, new long[] { 10*60*1000l, 60*60*1000l });
            //includeRate("crypto.elGamal.encrypt", stats, new long[] { 60*60*1000 });
            // total event count can be used to track uptime
            boolean hideTotals = ! RouterVersion.VERSION.equals("0.7.6");
            includeRate("tunnel.participatingTunnels", stats, new long[] { 60*60*1000 }, hideTotals);
            //includeRate("tunnel.testSuccessTime", stats, new long[] { 10*60*1000l });
            //includeRate("client.sendAckTime", stats, new long[] { 60*60*1000 }, true);
            //includeRate("udp.sendConfirmTime", stats, new long[] { 10*60*1000 });
            //includeRate("udp.sendVolleyTime", stats, new long[] { 10*60*1000 });
            //includeRate("udp.ignoreRecentDuplicate", stats, new long[] { 60*1000 });
            //includeRate("udp.congestionOccurred", stats, new long[] { 10*60*1000 });
            //includeRate("stream.con.sendDuplicateSize", stats, new long[] { 60*60*1000 });
            //includeRate("stream.con.receiveDuplicateSize", stats, new long[] { 60*60*1000 });

            //stats.setProperty("stat__rateKey", "avg;maxAvg;pctLifetime;[sat;satLim;maxSat;maxSatLim;][num;lifetimeFreq;maxFreq]");
            
            //includeRate("tunnel.decryptRequestTime", stats, new long[] { 60*1000, 10*60*1000 });
            //includeRate("udp.packetDequeueTime", stats, new long[] { 60*1000 });
            //includeRate("udp.packetVerifyTime", stats, new long[] { 60*1000 });
            
            //includeRate("tunnel.buildRequestTime", stats, new long[] { 10*60*1000 });
            long rate = 60*60*1000;
            includeTunnelRates("Client", stats, rate);
            includeTunnelRates("Exploratory", stats, rate);
            //includeRate("tunnel.rejectTimeout", stats, new long[] { 10*60*1000 });
            //includeRate("tunnel.rejectOverloaded", stats, new long[] { 10*60*1000 });
            //includeRate("tunnel.acceptLoad", stats, new long[] { 10*60*1000 });
        }

        // So that we will still get build requests
        stats.setProperty("stat_uptime", "90m");
        if (FloodfillNetworkDatabaseFacade.isFloodfill(_context.router().getRouterInfo())) {
            stats.setProperty("netdb.knownRouters", ""+_context.netDb().getKnownRouters());
            stats.setProperty("netdb.knownLeaseSets", ""+_context.netDb().getKnownLeaseSets());
        }

        return stats;
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
        StringBuilder buf = new StringBuilder(128);
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
                buf.append(num(avgFrequency)).append(';');
                buf.append(num(rate.getExtremeEventCount())).append(';');
                buf.append(num((double)rate.getLifetimeEventCount())).append(';');
            }
        }
        return buf.toString();
    }

    private static final String[] tunnelStats = { "Expire", "Reject", "Success" };

    /**
     *  Add tunnel build rates with some mods to hide absolute quantities
     *  In particular, report counts normalized to 100 (i.e. a percentage)
     */
    private void includeTunnelRates(String tunnelType, Properties stats, long selectedPeriod) {
        long totalEvents = 0;
        for (String tunnelStat : tunnelStats) {
            String rateName = "tunnel.build" + tunnelType + tunnelStat;
            RateStat stat = _context.statManager().getRate(rateName);
            if (stat == null) continue;
            Rate curRate = stat.getRate(selectedPeriod);
            if (curRate == null) continue;
            totalEvents += curRate.getLastEventCount();
        }
        if (totalEvents <= 0)
            return;
        for (String tunnelStat : tunnelStats) {
            String rateName = "tunnel.build" + tunnelType + tunnelStat;
            RateStat stat = _context.statManager().getRate(rateName);
            if (stat == null) continue;
            Rate curRate = stat.getRate(selectedPeriod);
            if (curRate == null) continue;
            double fudgeQuantity = 100.0d * curRate.getLastEventCount() / totalEvents;
            stats.setProperty("stat_" + rateName + '.' + getPeriod(curRate), renderRate(curRate, fudgeQuantity));
        }
    }
    
    private String renderRate(Rate rate, double fudgeQuantity) {
        StringBuilder buf = new StringBuilder(128);
        buf.append(num(rate.getAverageValue())).append(';');
        buf.append(num(rate.getExtremeAverageValue())).append(';');
        buf.append(pct(rate.getPercentageOfLifetimeValue())).append(';');
        if (rate.getLifetimeTotalEventTime() > 0) {
            // bah saturation
            buf.append("0;0;0;0;");
        }
        long numPeriods = rate.getLifetimePeriods();
        buf.append(num(fudgeQuantity)).append(';');
        return buf.toString();
    }

    /* report the same data for tx and rx, for enhanced anonymity */
    private void includeAverageThroughput(Properties stats) {
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        RateStat recvRate = _context.statManager().getRate("bw.recvRate");
        if (sendRate == null || recvRate == null)
            return;
        Rate s = sendRate.getRate(60*60*1000);
        Rate r = recvRate.getRate(60*60*1000);
        if (s == null || r == null)
            return;
        double speed = (s.getAverageValue() + r.getAverageValue()) / 2;
        double max = Math.max(s.getExtremeAverageValue(), r.getExtremeAverageValue());
        String str = num(speed) + ';' + num(max) + ";0;0;";
        stats.setProperty("stat_bandwidthSendBps.60m", str);
        stats.setProperty("stat_bandwidthReceiveBps.60m", str);
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
