package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.SimpleTimer;

/**
 * Coalesce the stats framework every minute
 *
 * @since 0.8.12 moved from Router.java
 */
public class CoalesceStatsEvent implements SimpleTimer.TimedEvent {
    private final RouterContext _ctx;
    private final long _maxMemory;
    private static final long LOW_MEMORY_THRESHOLD = 5 * 1024 * 1024;

    public CoalesceStatsEvent(RouterContext ctx) { 
        _ctx = ctx; 
        // NOTE TO TRANSLATORS - each of these phrases is a description for a statistic
        // to be displayed on /stats.jsp and in the graphs on /graphs.jsp.
        // Please keep relatively short so it will fit on the graphs.
        ctx.statManager().createRequiredRateStat("bw.receiveBps", _x("Message receive rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRequiredRateStat("bw.sendBps", _x("Message send rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRequiredRateStat("bw.sendRate", _x("Low-level send rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        ctx.statManager().createRequiredRateStat("bw.recvRate", _x("Low-level receive rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        ctx.statManager().createRequiredRateStat("router.activePeers", _x("How many peers we are actively talking with"), "Throttle", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRateStat("router.activeSendPeers", "How many peers we've sent to this minute", "Throttle", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRateStat("router.highCapacityPeers", "How many high capacity peers we know", "Throttle", new long[] { 5*60*1000, 60*60*1000 });
        ctx.statManager().createRequiredRateStat("router.fastPeers", _x("Known fast peers"), "Throttle", new long[] { 5*60*1000, 60*60*1000 });
        _maxMemory = Runtime.getRuntime().maxMemory();
        String legend = "(Bytes)";
        if (_maxMemory < Long.MAX_VALUE)
            legend += " Max is " + DataHelper.formatSize(_maxMemory) + 'B';
        // router.memoryUsed currently has the max size in the description so it can't be tagged
        ctx.statManager().createRequiredRateStat("router.memoryUsed", legend, "Router", new long[] { 60*1000 });
    }

    private RouterContext getContext() { return _ctx; }

    public void timeReached() {
        int active = getContext().commSystem().countActivePeers();
        getContext().statManager().addRateData("router.activePeers", active, 60*1000);

        int activeSend = getContext().commSystem().countActiveSendPeers();
        getContext().statManager().addRateData("router.activeSendPeers", activeSend, 60*1000);

        int fast = getContext().profileOrganizer().countFastPeers();
        getContext().statManager().addRateData("router.fastPeers", fast, 60*1000);

        int highCap = getContext().profileOrganizer().countHighCapacityPeers();
        getContext().statManager().addRateData("router.highCapacityPeers", highCap, 60*1000);

        getContext().statManager().addRateData("bw.sendRate", (long)getContext().bandwidthLimiter().getSendBps());
        getContext().statManager().addRateData("bw.recvRate", (long)getContext().bandwidthLimiter().getReceiveBps());
        
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        getContext().statManager().addRateData("router.memoryUsed", used);
        if (_maxMemory - used < LOW_MEMORY_THRESHOLD)
            Router.clearCaches();

        getContext().tunnelDispatcher().updateParticipatingStats(Router.COALESCE_TIME);

        getContext().statManager().coalesceStats();

        RateStat receiveRate = getContext().statManager().getRate("transport.receiveMessageSize");
        if (receiveRate != null) {
            Rate rate = receiveRate.getRate(60*1000);
            if (rate != null) { 
                double bytes = rate.getLastTotalValue();
                double bps = (bytes*1000.0d)/rate.getPeriod(); 
                getContext().statManager().addRateData("bw.receiveBps", (long)bps, 60*1000);
            }
        }

        RateStat sendRate = getContext().statManager().getRate("transport.sendMessageSize");
        if (sendRate != null) {
            Rate rate = sendRate.getRate(60*1000);
            if (rate != null) {
                double bytes = rate.getLastTotalValue();
                double bps = (bytes*1000.0d)/rate.getPeriod(); 
                getContext().statManager().addRateData("bw.sendBps", (long)bps, 60*1000);
            }
        }
    }
    
    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     *  @since 0.8.7
     */
    private static final String _x(String s) {
        return s;
    }
}
