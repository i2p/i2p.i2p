package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.Messages;
import net.i2p.stat.Frequency;
import net.i2p.stat.FrequencyStat;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Dump the stats to the web admin interface
 */
public class StatsGenerator {
    private RouterContext _context;

    public StatsGenerator(RouterContext context) {
        _context = context;
    }
    
    public void generateStatsPage(Writer out, boolean showAll) throws IOException {
        StringBuilder buf = new StringBuilder(16*1024);

        buf.append("<div class=\"joblog\">\n");
        buf.append("<p id=\"gatherstats\">");
        buf.append(_t("Statistics gathered during this router's uptime")).append(" (");
        long uptime = _context.router().getUptime();
        buf.append(DataHelper.formatDuration2(uptime));
        buf.append(").  ").append( _t("The data gathered is quantized over a 1 minute period, so should just be used as an estimate."));
        buf.append(' ').append( _t("These statistics are primarily used for development and debugging."));
        buf.append("</p>");

        buf.append("<form action=\"\"><b>");
        buf.append(_t("Jump to section")).append(":</b> <select name=\"go\" onChange='location.href=this.value'>");
        out.write(buf.toString());
        buf.setLength(0);

        Map<String, SortedSet<String>> unsorted = _context.statManager().getStatsByGroup();
        Map<String, Set<String>> groups = new TreeMap<String, Set<String>>(new AlphaComparator());
        groups.putAll(unsorted);
        for (String group : groups.keySet()) {
            buf.append("<option value=\"#").append(group).append("\">");
            buf.append(_t(group)).append("</option>\n");
            // let's just do the groups
            //Set stats = (Set)entry.getValue();
            //for (Iterator statIter = stats.iterator(); statIter.hasNext(); ) {
            //    String stat = (String)statIter.next();
            //    buf.append("<option value=\"/stats.jsp#");
            //    buf.append(stat);
            //    buf.append("\">...");
            //    buf.append(stat);
            //    buf.append("</option>\n");
            //}
            //out.write(buf.toString());
            //buf.setLength(0);
        }
        buf.append("</select> <input type=\"submit\" value=\"").append(_t("GO")).append("\" />");
        buf.append("</form>");

        out.write(buf.toString());
        buf.setLength(0);

        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            String group = entry.getKey();
            Set<String> stats = entry.getValue();
            buf.append("<h3 class=\"stats\"><a name=\"");
            buf.append(group);
            buf.append("\">");
            buf.append(_t(group));
            buf.append("</a></h3>");
            buf.append("<ul class=\"statlist\">");
            out.write(buf.toString());
            buf.setLength(0);
            for (String stat : stats) {
                buf.append("<li class=\"statsName\"><b><a name=\"");
                buf.append(stat);
                buf.append("\">");
                buf.append(stat);
                buf.append("</a>:</b>&nbsp;");
                if (_context.statManager().isFrequency(stat))
                    renderFrequency(stat, buf);
                else
                    renderRate(stat, buf, showAll);
                out.write(buf.toString());
                buf.setLength(0);
            }
            out.write("</ul><br>\n");
        }
        out.write("</div>");
        out.flush();
    }
    
    private void renderFrequency(String name, StringBuilder buf) {
        FrequencyStat freq = _context.statManager().getFrequency(name);
        buf.append("<i>");
        buf.append(freq.getDescription());
        buf.append("</i><br>");
        if (freq.getEventCount() <= 0) {
            buf.append("<ul><li class=\"noevents\">").append(_t("No lifetime events")).append("</li></ul>\n");
            return;
        }
        long uptime = _context.router().getUptime();
        long periods[] = freq.getPeriods();
        Arrays.sort(periods);
        buf.append("<ul>");
        for (int i = 0; i < periods.length; i++) {
            if (periods[i] > uptime)
                break;
            buf.append("<li>");
            renderPeriod(buf, periods[i], _t("frequency"));
            Frequency curFreq = freq.getFrequency(periods[i]);
            buf.append(DataHelper.formatDuration2(Math.round(curFreq.getAverageInterval())));
            buf.append("; ");
            buf.append(_t("Rolling average events per period"));
            buf.append(": ");
            buf.append(num(curFreq.getAverageEventsPerPeriod()));
            buf.append("; ");
            buf.append(_t("Highest events per period"));
            buf.append(": ");
            buf.append(num(curFreq.getMaxAverageEventsPerPeriod()));
            buf.append("; ");
            //if (showAll && (curFreq.getMaxAverageEventsPerPeriod() > 0) && (curFreq.getAverageEventsPerPeriod() > 0) ) {
            //    buf.append("(current is ");
            //    buf.append(pct(curFreq.getAverageEventsPerPeriod()/curFreq.getMaxAverageEventsPerPeriod()));
            //    buf.append(" of max)");
            //}
            //buf.append(" <i>avg interval between updates:</i> (").append(num(curFreq.getAverageInterval())).append("ms, min ");
            //buf.append(num(curFreq.getMinAverageInterval())).append("ms)");
            buf.append(_t("Lifetime average events per period")).append(": ");
            buf.append(num(curFreq.getStrictAverageEventsPerPeriod()));
            buf.append("</li>\n");
        }
        // Display the strict average
        buf.append("<li><b>").append(_t("Lifetime average frequency")).append(":</b> ");
        buf.append(DataHelper.formatDuration2(freq.getFrequency()));
        buf.append(" (");
        buf.append(ngettext("1 event", "{0} events", (int) freq.getEventCount()));
        buf.append(")</li></ul><br>\n");
    }
    
    private void renderRate(String name, StringBuilder buf, boolean showAll) {
        RateStat rate = _context.statManager().getRate(name);
        String d = rate.getDescription();
        if (! "".equals(d)) {
            buf.append("<span class=\"statsLongName\"><i>");
            buf.append(d);
            buf.append("</i></span><br>");
        }
        if (rate.getLifetimeEventCount() <= 0) {
            buf.append("<ul><li class=\"noevents\">").append(_t("No lifetime events")).append("</li></ul>\n");
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
            renderPeriod(buf, periods[i], _t("rate"));
            if (curRate.getLastEventCount() > 0) {
                buf.append(_t("Average")).append(": ");
                buf.append(num(curRate.getAverageValue()));
                buf.append("; ");
                buf.append(_t("Highest average"));
                buf.append(": ");
                buf.append(num(curRate.getExtremeAverageValue()));
                buf.append(". ");

                // This is rarely interesting
                // Don't bother to translate
                if (showAll) {
                    buf.append("Highest total in a period: ");
                    buf.append(num(curRate.getExtremeTotalValue()));
                    buf.append(". ");
                }

                // Saturation stats, which nobody understands, even when it isn't meaningless
                // Don't bother to translate
                if (showAll && curRate.getLifetimeTotalEventTime() > 0) {
                    buf.append("Saturation: ");
                    buf.append(pct(curRate.getLastEventSaturation()));
                    buf.append("; Saturated limit: ");
                    buf.append(num(curRate.getLastSaturationLimit()));
                    buf.append("; Peak saturation: ");
                    buf.append(pct(curRate.getExtremeEventSaturation()));
                    buf.append("; Peak saturated limit: ");
                    buf.append(num(curRate.getExtremeSaturationLimit()));
                    buf.append(". ");
                }

                buf.append("<span class=\"nowrap\">");
                buf.append(ngettext("There was 1 event in this period.", "There were {0} events in this period.", (int)curRate.getLastEventCount()));
                buf.append("</span> <span class=\"nowrap\">");
                buf.append(_t("The period ended {0} ago.", DataHelper.formatDuration2(now - curRate.getLastCoalesceDate())));
                buf.append("</span>");
            } else {
                buf.append(" <i>").append(_t("No events")).append(" </i>");
            }
            long numPeriods = curRate.getLifetimePeriods();
            if (numPeriods > 0) {
                double avgFrequency = curRate.getLifetimeEventCount() / (double)numPeriods;
                buf.append("&nbsp;<span class=\"nowrap\">(").append(_t("Average event count")).append(": ");
                buf.append(num(avgFrequency));
                buf.append("; ").append(_t("Events in peak period")).append(": ");
                // This isn't really the highest event count, but the event count during the period with the highest total value.
                buf.append(curRate.getExtremeEventCount());
                buf.append(")</span>");
            }
            if (curRate.getSummaryListener() != null) {
                buf.append("<br><span class=\"statsViewGraphs\"><a href=\"graph?stat=").append(name)
                   .append('.').append(periods[i]);
                buf.append("&amp;w=600&amp;h=200\">").append(_t("Graph Data")).append("</a> - ");
                buf.append(" <a href=\"graph?stat=").append(name)
                   .append('.').append(periods[i]);
                buf.append("&amp;w=600&amp;h=200&amp;showEvents=true\">").append(_t("Graph Event Count")).append("</a></span>");
                // This can really blow up your browser if you click on it
                //buf.append(" - <a href=\"viewstat.jsp?stat=").append(name);
                //buf.append("&amp;period=").append(periods[i]);
                //buf.append("&amp;format=xml\">").append(_t("Export Data as XML")).append("</a>");
            }
            buf.append("</li>\n");
        }
        // Display the strict average
        buf.append("<li><b>").append(_t("Lifetime average value")).append(":</b> ");
        buf.append(num(rate.getLifetimeAverageValue()));
        buf.append(" (");
        buf.append(ngettext("1 event", "{0} events", (int) rate.getLifetimeEventCount()));
        buf.append(")<br></li>" +
                   "</ul>" +
                   "<br>\n");
    }
    
    private static void renderPeriod(StringBuilder buf, long period, String name) {
        buf.append("<b>");
        buf.append(DataHelper.formatDuration2(period));
        buf.append(" ");
        buf.append(name);
        buf.append(":</b> ");
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.0##");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    
    private final static DecimalFormat _pct = new DecimalFormat("#0.00%");
    private final static String pct(double num) { synchronized (_pct) { return _pct.format(num); } }

    /**
     *  Translated sort
     *  Inner class, can't be Serializable
     *  @since 0.9.3
     */
    private class AlphaComparator implements Comparator<String> {
        public int compare(String lhs, String rhs) {
            String lname = _t(lhs);
            String rname = _t(rhs);
            return Collator.getInstance().compare(lname, rname);
        }
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** translate a string */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /** translate a string */
    private String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }
}
