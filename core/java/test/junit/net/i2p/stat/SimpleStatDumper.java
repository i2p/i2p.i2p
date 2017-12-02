package net.i2p.stat;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

public class SimpleStatDumper {
    private final static Log _log = new Log(SimpleStatDumper.class);

    public static void dumpStats(I2PAppContext context, int logLevel) {
        if (!_log.shouldLog(logLevel)) return;

        StringBuilder buf = new StringBuilder(4 * 1024);
        dumpFrequencies(context, buf);
        dumpRates(context, buf);
        _log.log(logLevel, buf.toString());
    }

    @SuppressWarnings("deprecation")
    private static void dumpFrequencies(I2PAppContext ctx, StringBuilder buf) {
        Set<String> frequencies = new TreeSet<String>(ctx.statManager().getFrequencyNames());
        for (String name : frequencies) {
            FrequencyStat freq = ctx.statManager().getFrequency(name);
            buf.append('\n');
            buf.append(freq.getGroupName()).append('.').append(freq.getName()).append(": ")
               .append(freq.getDescription()).append('\n');
            long periods[] = freq.getPeriods();
            Arrays.sort(periods);
            for (int i = 0; i < periods.length; i++) {
                buf.append('\t').append(periods[i]).append(':');
                Frequency curFreq = freq.getFrequency(periods[i]);
                buf.append(" average interval: ").append(curFreq.getAverageInterval());
                buf.append(" min average interval: ").append(curFreq.getMinAverageInterval());
                buf.append('\n');
            }
        }
    }

    private static void dumpRates(I2PAppContext ctx, StringBuilder buf) {
        Set<String> rates = new TreeSet<String>(ctx.statManager().getRateNames());
        for (String name : rates) {
            RateStat rate = ctx.statManager().getRate(name);
            buf.append('\n');
            buf.append(rate.getGroupName()).append('.').append(rate.getName()).append(": ")
               .append(rate.getDescription()).append('\n');
            long periods[] = rate.getPeriods();
            Arrays.sort(periods);
            for (int i = 0; i < periods.length; i++) {
                buf.append('\t').append(periods[i]).append(':');
                Rate curRate = rate.getRate(periods[i]);
                dumpRate(curRate, buf);
                buf.append('\n');
            }
        }
    }

    static void dumpRate(Rate curRate, StringBuilder buf) {
        buf.append(curRate.toString());
    }
}
