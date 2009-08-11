package net.i2p.router.web;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.stat.Frequency;
import net.i2p.stat.FrequencyStat;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Dump the stats to the web admin interface
 */
public class StatsGenerator {
    private Log _log;
    private RouterContext _context;
    public StatsGenerator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(StatsGenerator.class);
    }
    
    public void generateStatsPage(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<div class=\"joblog\"><form action=\"/oldstats.jsp\">");
        buf.append("<select name=\"go\" onChange='location.href=this.value'>");
        out.write(buf.toString());
        buf.setLength(0);
        
        Map groups = _context.statManager().getStatsByGroup();
        for (Iterator iter = groups.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            String group = (String)entry.getKey();
            Set stats = (Set)entry.getValue();
            buf.append("<option value=\"/oldstats.jsp#").append(group).append("\">");
            buf.append(group).append("</option>\n");
            for (Iterator statIter = stats.iterator(); statIter.hasNext(); ) {
                String stat = (String)statIter.next();
                buf.append("<option value=\"/oldstats.jsp#");
                buf.append(stat);
                buf.append("\">...");
                buf.append(stat);
                buf.append("</option>\n");
            }
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("</select> <input type=\"submit\" value=\"GO\" />");
        buf.append("</form>");
        
        buf.append("Statistics gathered during this router's uptime (");
        long uptime = _context.router().getUptime();
        buf.append(DataHelper.formatDuration(uptime));
        buf.append(").  The data gathered is quantized over a 1 minute period, so should just be used as an estimate<p />");

        out.write(buf.toString());
        buf.setLength(0);
        
        for (Iterator iter = groups.keySet().iterator(); iter.hasNext(); ) {
            String group = (String)iter.next();
            Set stats = (Set)groups.get(group);
            buf.append("<h3><a name=\"");
            buf.append(group);
            buf.append("\">");
            buf.append(group);
            buf.append("</a></h3>");
            buf.append("<ul>");
            out.write(buf.toString());
            buf.setLength(0);
            for (Iterator statIter = stats.iterator(); statIter.hasNext(); ) {
                String stat = (String)statIter.next();
                buf.append("<li><b><a name=\"");
                buf.append(stat);
                buf.append("\">");
                buf.append(stat);
                buf.append("</a></b><br />");
                if (_context.statManager().isFrequency(stat))
                    renderFrequency(stat, buf);
                else
                    renderRate(stat, buf);
                out.write(buf.toString());
                buf.setLength(0);
            }
            out.write("</ul><hr />");
        }
        out.flush();
    }
    
    private void renderFrequency(String name, StringBuilder buf) {
        FrequencyStat freq = _context.statManager().getFrequency(name);
        buf.append("<i>");
        buf.append(freq.getDescription());
        buf.append("</i><br />");
        long uptime = _context.router().getUptime();
        long periods[] = freq.getPeriods();
        Arrays.sort(periods);
        for (int i = 0; i < periods.length; i++) {
            if (periods[i] > uptime)
                break;
            renderPeriod(buf, periods[i], "frequency");
            Frequency curFreq = freq.getFrequency(periods[i]);
            buf.append(" <i>avg per period:</i> (");
            buf.append(num(curFreq.getAverageEventsPerPeriod()));
            buf.append(", max ");
            buf.append(num(curFreq.getMaxAverageEventsPerPeriod()));
            if ( (curFreq.getMaxAverageEventsPerPeriod() > 0) && (curFreq.getAverageEventsPerPeriod() > 0) ) {
                buf.append(", current is ");
                buf.append(pct(curFreq.getAverageEventsPerPeriod()/curFreq.getMaxAverageEventsPerPeriod()));
                buf.append(" of max");
            }
            buf.append(")");
            //buf.append(" <i>avg interval between updates:</i> (").append(num(curFreq.getAverageInterval())).append("ms, min ");
            //buf.append(num(curFreq.getMinAverageInterval())).append("ms)");
            buf.append(" <i>strict average per period:</i> ");
            buf.append(num(curFreq.getStrictAverageEventsPerPeriod()));
            buf.append(" events (averaged ");
            buf.append(" using the lifetime of ");
            buf.append(curFreq.getEventCount());
            buf.append(" events)");
            buf.append("<br />");
        }
        buf.append("<br />");
    }
    
    private void renderRate(String name, StringBuilder buf) {
        RateStat rate = _context.statManager().getRate(name);
        String d = rate.getDescription();
        if (! "".equals(d)) {
            buf.append("<i>");
            buf.append(d);
            buf.append("</i><br />");
        }
        if (rate.getLifetimeEventCount() <= 0) {
            buf.append("No lifetime events<br />&nbsp;<br />");
            return;
        }
        long now = _context.clock().now();
        long periods[] = rate.getPeriods();
        Arrays.sort(periods);
        buf.append("<ul>");
        for (int i = 0; i < periods.length; i++) {
            Rate curRate = rate.getRate(periods[i]);
            if (curRate.getLastCoalesceDate() <= curRate.getCreationDate())
                break;
            buf.append("<li>");
            renderPeriod(buf, periods[i], "rate");
            if (curRate.getLastEventCount() > 0) {
                buf.append( "<i>avg value:</i> (");
                buf.append(num(curRate.getAverageValue()));
                buf.append(" peak ");
                buf.append(num(curRate.getExtremeAverageValue()));
                buf.append(", [");
                buf.append(pct(curRate.getPercentageOfExtremeValue()));
                buf.append(" of max");
                buf.append(", and ");
                buf.append(pct(curRate.getPercentageOfLifetimeValue()));
                buf.append(" of lifetime average]");
                
                buf.append(")");
                buf.append(" <i>highest total period value:</i> (");
                buf.append(num(curRate.getExtremeTotalValue()));
                buf.append(")");
                if (curRate.getLifetimeTotalEventTime() > 0) {
                    buf.append(" <i>saturation:</i> (");
                    buf.append(pct(curRate.getLastEventSaturation()));
                    buf.append(")");
                    buf.append(" <i>saturated limit:</i> (");
                    buf.append(num(curRate.getLastSaturationLimit()));
                    buf.append(")");
                    buf.append(" <i>peak saturation:</i> (");
                    buf.append(pct(curRate.getExtremeEventSaturation()));
                    buf.append(")");
                    buf.append(" <i>peak saturated limit:</i> (");
                    buf.append(num(curRate.getExtremeSaturationLimit()));
                    buf.append(")");
                }
                buf.append(" <i>events:</i> ");
                buf.append(curRate.getLastEventCount());
                buf.append(" <i>in this period which ended:</i> ");
                buf.append(DataHelper.formatDuration(now - curRate.getLastCoalesceDate()));
                buf.append(" ago ");
            } else {
                buf.append(" <i>No events</i> ");
            }
            long numPeriods = curRate.getLifetimePeriods();
            if (numPeriods > 0) {
                double avgFrequency = curRate.getLifetimeEventCount() / (double)numPeriods;
                double peakFrequency = curRate.getExtremeEventCount();
                buf.append(" (lifetime average: ");
                buf.append(num(avgFrequency));
                buf.append(", peak average: ");
                buf.append(curRate.getExtremeEventCount());
                buf.append(")");
            }
            if (curRate.getSummaryListener() != null) {
                buf.append(" <a href=\"viewstat.jsp?stat=").append(name);
                buf.append("&amp;period=").append(periods[i]);
                buf.append("\" title=\"Render summarized data\">render</a>");
                buf.append(" <a href=\"viewstat.jsp?stat=").append(name);
                buf.append("&amp;period=").append(periods[i]).append("&amp;showEvents=true\" title=\"Render summarized event counts\">events</a>");
                buf.append(" (as <a href=\"viewstat.jsp?stat=").append(name);
                buf.append("&amp;period=").append(periods[i]);
                buf.append("&amp;format=xml\" title=\"Dump stat history as XML\">XML</a>");
                buf.append(" in a format <a href=\"http://people.ee.ethz.ch/~oetiker/webtools/rrdtool\">RRDTool</a> understands)");
            }
            buf.append("</li>");
        }
        // Display the strict average
        buf.append("<li><b>lifetime average value:</b> ");
        buf.append(num(rate.getLifetimeAverageValue()));
        buf.append(" over ");
        buf.append(rate.getLifetimeEventCount());
        buf.append(" events<br /></li>");
        buf.append("</ul>");
        buf.append("<br />");
    }
    
    private static void renderPeriod(StringBuilder buf, long period, String name) {
        buf.append("<b>");
        buf.append(DataHelper.formatDuration(period));
        buf.append(" ");
        buf.append(name);
        buf.append(":</b> ");
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    
    private final static DecimalFormat _pct = new DecimalFormat("#0.00%");
    private final static String pct(double num) { synchronized (_pct) { return _pct.format(num); } }
}
