package net.i2p.router.admin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.stat.Frequency;
import net.i2p.stat.FrequencyStat;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

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
    
    public String generateStatsPage() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
        try {
            generateStatsPage(baos);
        } catch (IOException ioe) {
            _log.error("Error generating stats", ioe);
        }
        return new String(baos.toByteArray());
    }
    
    public void generateStatsPage(OutputStream out) throws IOException {
        PrintWriter pw = new PrintWriter(out);
        pw.println("<html><head><title>I2P Router Stats</title></head><body>");
        pw.println("<h1>Router statistics</h1>");
        pw.println("<i><a href=\"/routerConsole.html\">console</a> | <a href=\"/routerStats.html\">stats</a></i><hr />");
        Map groups = _context.statManager().getStatsByGroup();
        
        pw.println("<form action=\"/routerStats.html\">");
        pw.println("<select name=\"go\" onChange='location.href=this.value'>");
        for (Iterator iter = groups.keySet().iterator(); iter.hasNext(); ) {
            String group = (String)iter.next();
            Set stats = (Set)groups.get(group);
            pw.print("<option value=\"/routerStats.html#");
            pw.print(group);
            pw.print("\">");
            pw.print(group);
            pw.println("</option>\n");
            for (Iterator statIter = stats.iterator(); statIter.hasNext(); ) {
                String stat = (String)statIter.next();
                pw.print("<option value=\"/routerStats.html#");
                pw.print(stat);
                pw.print("\">...");
                pw.print(stat);
                pw.println("</option>\n");
            }
        }
        pw.println("</select>");
        pw.println("</form>");
        
        pw.print("Statistics gathered during this router's uptime (");
        long uptime = _context.router().getUptime();
        pw.print(DataHelper.formatDuration(uptime));
        pw.println(").  The data gathered is quantized over a 1 minute period, so should just be used as an estimate<p />");
        
        for (Iterator iter = groups.keySet().iterator(); iter.hasNext(); ) {
            String group = (String)iter.next();
            Set stats = (Set)groups.get(group);
            pw.print("<h2><a name=\"");
            pw.print(group);
            pw.print("\">");
            pw.print(group);
            pw.println("</a></h2>");
            pw.println("<ul>");
            for (Iterator statIter = stats.iterator(); statIter.hasNext(); ) {
                String stat = (String)statIter.next();
                pw.print("<li><b><a name=\"");
                pw.print(stat);
                pw.print("\">");
                pw.print(stat);
                pw.println("</a></b><br />");
                if (_context.statManager().isFrequency(stat))
                    renderFrequency(stat, pw);
                else
                    renderRate(stat, pw);
            }
            pw.println("</ul><hr />");
        }
        pw.println("</body></html>");
        pw.flush();
    }
    
    private void renderFrequency(String name, PrintWriter pw) throws IOException {
        FrequencyStat freq = _context.statManager().getFrequency(name);
        pw.print("<i>");
        pw.print(freq.getDescription());
        pw.println("</i><br />");
        long periods[] = freq.getPeriods();
        Arrays.sort(periods);
        for (int i = 0; i < periods.length; i++) {
            renderPeriod(pw, periods[i], "frequency");
            Frequency curFreq = freq.getFrequency(periods[i]);
            pw.print(" <i>avg per period:</i> (");
            pw.print(num(curFreq.getAverageEventsPerPeriod()));
            pw.print(", max ");
            pw.print(num(curFreq.getMaxAverageEventsPerPeriod()));
            if ( (curFreq.getMaxAverageEventsPerPeriod() > 0) && (curFreq.getAverageEventsPerPeriod() > 0) ) {
                pw.print(", current is ");
                pw.print(pct(curFreq.getAverageEventsPerPeriod()/curFreq.getMaxAverageEventsPerPeriod()));
                pw.print(" of max");
            }
            pw.print(")");
            //buf.append(" <i>avg interval between updates:</i> (").append(num(curFreq.getAverageInterval())).append("ms, min ");
            //buf.append(num(curFreq.getMinAverageInterval())).append("ms)");
            pw.print(" <i>strict average per period:</i> ");
            pw.print(num(curFreq.getStrictAverageEventsPerPeriod()));
            pw.print(" events (averaged ");
            pw.print(" using the lifetime of ");
            pw.print(num(curFreq.getEventCount()));
            pw.print(" events)");
            pw.println("<br />");
        }
        pw.println("<br />");
    }
    
    private void renderRate(String name, PrintWriter pw) throws IOException {
        RateStat rate = _context.statManager().getRate(name);
        pw.print("<i>");
        pw.print(rate.getDescription());
        pw.println("</i><br />");
        long periods[] = rate.getPeriods();
        Arrays.sort(periods);
        pw.println("<ul>");
        for (int i = 0; i < periods.length; i++) {
            pw.println("<li>");
            renderPeriod(pw, periods[i], "rate");
            Rate curRate = rate.getRate(periods[i]);
            pw.print( "<i>avg value:</i> (");
            pw.print(num(curRate.getAverageValue()));
            pw.print(" peak ");
            pw.print(num(curRate.getExtremeAverageValue()));
            pw.print(", [");
            pw.print(pct(curRate.getPercentageOfExtremeValue()));
            pw.print(" of max");
            pw.print(", and ");
            pw.print(pct(curRate.getPercentageOfLifetimeValue()));
            pw.print(" of lifetime average]");
            
            pw.print(")");
            pw.print(" <i>highest total period value:</i> (");
            pw.print(num(curRate.getExtremeTotalValue()));
            pw.print(")");
            if (curRate.getLifetimeTotalEventTime() > 0) {
                pw.print(" <i>saturation:</i> (");
                pw.print(pct(curRate.getLastEventSaturation()));
                pw.print(")");
                pw.print(" <i>saturated limit:</i> (");
                pw.print(num(curRate.getLastSaturationLimit()));
                pw.print(")");
                pw.print(" <i>peak saturation:</i> (");
                pw.print(pct(curRate.getExtremeEventSaturation()));
                pw.print(")");
                pw.print(" <i>peak saturated limit:</i> (");
                pw.print(num(curRate.getExtremeSaturationLimit()));
                pw.print(")");
            }
            pw.print(" <i>events per period:</i> ");
            pw.print(num(curRate.getLastEventCount()));
            long numPeriods = curRate.getLifetimePeriods();
            if (numPeriods > 0) {
                double avgFrequency = curRate.getLifetimeEventCount() / (double)numPeriods;
                double peakFrequency = curRate.getExtremeEventCount();
                pw.print(" (lifetime average: ");
                pw.print(num(avgFrequency));
                pw.print(", peak average: ");
                pw.print(num(curRate.getExtremeEventCount()));
                pw.println(")");
            }
            pw.print("</li>");
            if (i + 1 == periods.length) {
                // last one, so lets display the strict average
                pw.print("<li><b>lifetime average value:</b> ");
                pw.print(num(curRate.getLifetimeAverageValue()));
                pw.print(" over ");
                pw.print(num(curRate.getLifetimeEventCount()));
                pw.println(" events<br /></li>");
            }
        }
        pw.print("</ul>");
        pw.println("<br />");
    }
    
    private static void renderPeriod(PrintWriter pw, long period, String name) throws IOException {
        pw.print("<b>");
        pw.print(DataHelper.formatDuration(period));
        pw.print(" ");
        pw.print(name);
        pw.print(":</b> ");
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    
    private final static DecimalFormat _pct = new DecimalFormat("#0.00%");
    private final static String pct(double num) { synchronized (_pct) { return _pct.format(num); } }
}
