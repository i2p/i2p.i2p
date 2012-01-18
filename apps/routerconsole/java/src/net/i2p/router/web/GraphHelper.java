package net.i2p.router.web;

import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;

public class GraphHelper extends FormHandler {
    protected Writer _out;
    private int _periodCount;
    private boolean _showEvents;
    private int _width;
    private int _height;
    private int _refreshDelaySeconds;
    private boolean _persistent;

    private static final String PROP_X = "routerconsole.graphX";
    private static final String PROP_Y = "routerconsole.graphY";
    private static final String PROP_REFRESH = "routerconsole.graphRefresh";
    private static final String PROP_PERIODS = "routerconsole.graphPeriods";
    private static final String PROP_EVENTS = "routerconsole.graphEvents";
    public static final int DEFAULT_X = 250;
    public static final int DEFAULT_Y = 100;
    private static final int DEFAULT_REFRESH = 60;
    private static final int DEFAULT_PERIODS = 60;
    static final int MAX_X = 2048;
    static final int MAX_Y = 1024;
    private static final int MIN_REFRESH = 15;
    
    /** set the defaults after we have a context */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _width = _context.getProperty(PROP_X, DEFAULT_X);
        _height = _context.getProperty(PROP_Y, DEFAULT_Y);
        _periodCount = _context.getProperty(PROP_PERIODS, DEFAULT_PERIODS);
        _refreshDelaySeconds = _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
        _showEvents = _context.getBooleanProperty(PROP_EVENTS);
    }
    
    /**
     *  This must be output in the jsp since <meta> must be in the <head>
     *  @since 0.8.7
     */
    public String getRefreshMeta() {
        if (_refreshDelaySeconds <= 8 ||
            ConfigRestartBean.getRestartTimeRemaining() < (1000 * (_refreshDelaySeconds + 30)))
            return "";
        // shorten the refresh by 3 seconds so we beat the iframe
        return "<meta http-equiv=\"refresh\" content=\"" + (_refreshDelaySeconds - 3) + "\">";
    }

    /**
     *  This was a HelperBase but now it's a FormHandler
     *  @since 0.8.2
     */
    public void storeWriter(Writer out) { _out = out; }

    public void setPeriodCount(String str) { 
        try { _periodCount = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
    }

    public void setShowEvents(boolean b) { _showEvents = b; }

    public void setHeight(String str) {
        try { _height = Math.min(Integer.parseInt(str), MAX_Y); } catch (NumberFormatException nfe) {}
    }

    public void setWidth(String str) {
        try { _width = Math.min(Integer.parseInt(str), MAX_X); } catch (NumberFormatException nfe) {}
    }

    public void setRefreshDelay(String str) {
        try {
            int rds = Integer.parseInt(str);
            if (rds > 0)
                _refreshDelaySeconds = Math.max(rds, MIN_REFRESH);
            else
                _refreshDelaySeconds = -1;
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.8.7 */
    public void setPersistent(String foo) { _persistent = true; }
    
    public String getImages() { 
        if (StatSummarizer.isDisabled())
            return "";
        try {
            List listeners = StatSummarizer.instance().getListeners();
            TreeSet ordered = new TreeSet(new AlphaComparator());
            ordered.addAll(listeners);

            // go to some trouble to see if we have the data for the combined bw graph
            boolean hasTx = false;
            boolean hasRx = false;
            for (Iterator iter = ordered.iterator(); iter.hasNext(); ) {
                SummaryListener lsnr = (SummaryListener)iter.next();
                String title = lsnr.getRate().getRateStat().getName();
                if (title.equals("bw.sendRate")) hasTx = true;
                else if (title.equals("bw.recvRate")) hasRx = true;
            }

            if (hasTx && hasRx && !_showEvents) {
                _out.write("<a href=\"viewstat.jsp?stat=bw.combined"
                           + "&amp;periodCount=" + (3 * _periodCount )
                           + "&amp;width=" + (3 * _width)
                           + "&amp;height=" + (3 * _height)
                           + "\" target=\"_blank\">");
                String title = _("Combined bandwidth graph");
                _out.write("<img class=\"statimage\""
                           + " src=\"viewstat.jsp?stat=bw.combined"
                           + "&amp;periodCount=" + _periodCount 
                           + "&amp;width=" + _width
                           + "&amp;height=" + (_height - 13)
                           + "\" alt=\"" + title + "\" title=\"" + title + "\"></a>\n");
            }
            
            for (Iterator iter = ordered.iterator(); iter.hasNext(); ) {
                SummaryListener lsnr = (SummaryListener)iter.next();
                Rate r = lsnr.getRate();
                // e.g. "statname for 60m"
                String title = _("{0} for {1}", r.getRateStat().getName(), DataHelper.formatDuration2(_periodCount * r.getPeriod()));
                _out.write("<a href=\"viewstat.jsp?stat="
                           + r.getRateStat().getName() 
                           + "&amp;showEvents=" + _showEvents
                           + "&amp;period=" + r.getPeriod() 
                           + "&amp;periodCount=" + (3 * _periodCount)
                           + "&amp;width=" + (3 * _width)
                           + "&amp;height=" + (3 * _height)
                           + "\" target=\"_blank\">");
                _out.write("<img class=\"statimage\" border=\"0\""
                           + " src=\"viewstat.jsp?stat="
                           + r.getRateStat().getName() 
                           + "&amp;showEvents=" + _showEvents
                           + "&amp;period=" + r.getPeriod() 
                           + "&amp;periodCount=" + _periodCount 
                           + "&amp;width=" + _width
                           + "&amp;height=" + _height
                           + "\" alt=\"" + title 
                           + "\" title=\"" + title + "\"></a>\n");
            }

            // FIXME jrobin doesn't support setting the timezone, will have to mod TimeAxis.java
            _out.write("<p><i>" + _("All times are UTC.") + "</i></p>\n");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }

    private static final int[] times = { 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    public String getForm() { 
        if (StatSummarizer.isDisabled())
            return "";
        String prev = System.getProperty("net.i2p.router.web.GraphHelper.nonce");
        if (prev != null) System.setProperty("net.i2p.router.web.GraphHelper.noncePrev", prev);
        String nonce = "" + _context.random().nextLong();
        System.setProperty("net.i2p.router.web.GraphHelper.nonce", nonce);
        try {
            _out.write("<br><h3>" + _("Configure Graph Display") + " [<a href=\"configstats\">" + _("Select Stats") + "</a>]</h3>");
            _out.write("<form action=\"graphs\" method=\"POST\">\n" +
                       "<input type=\"hidden\" name=\"action\" value=\"foo\">\n" +
                       "<input type=\"hidden\" name=\"nonce\" value=\"" + nonce + "\" >\n");
            _out.write(_("Periods") + ": <input size=\"5\" style=\"text-align: right;\" type=\"text\" name=\"periodCount\" value=\"" + _periodCount + "\"><br>\n");
            _out.write(_("Plot averages") + ": <input type=\"radio\" class=\"optbox\" name=\"showEvents\" value=\"false\" " + (_showEvents ? "" : "checked=\"true\" ") + "> ");
            _out.write(_("or")+ " " +_("plot events") + ": <input type=\"radio\" class=\"optbox\" name=\"showEvents\" value=\"true\" "+ (_showEvents ? "checked=\"true\" " : "") + "><br>\n");
            _out.write(_("Image sizes") + ": " + _("width") + ": <input size=\"4\" style=\"text-align: right;\" type=\"text\" name=\"width\" value=\"" + _width 
                       + "\"> " + _("pixels") + ", " + _("height") + ": <input size=\"4\" style=\"text-align: right;\" type=\"text\" name=\"height\" value=\"" + _height  
                       + "\"> " + _("pixels") + "<br>\n");
            _out.write(_("Refresh delay") + ": <select name=\"refreshDelay\">");
            for (int i = 0; i < times.length; i++) {
                _out.write("<option value=\"");
                _out.write(Integer.toString(times[i]));
                _out.write("\"");
                if (times[i] == _refreshDelaySeconds)
                    _out.write(" selected=\"true\"");
                _out.write(">");
                if (times[i] > 0)
                    _out.write(DataHelper.formatDuration2(times[i] * 1000));
                else
                    _out.write(_("Never"));
                _out.write("</option>\n");
            }
            _out.write("</select><br>\n" +
                       _("Store graph data on disk?") +
                       " <input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"persistent\"");
            boolean persistent = _context.getBooleanPropertyDefaultTrue(SummaryListener.PROP_PERSISTENT);
            if (persistent)
                _out.write(" checked=\"true\"");
            _out.write(">" +
                       "<hr><div class=\"formaction\"><input type=\"submit\" class=\"acceot\" value=\"" + _("Save settings and redraw graphs") + "\"></div></form>");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }

    /**
     *  We have to do this here because processForm() isn't called unless the nonces are good
     *  @since 0.8.7
     */
    @Override
    public String getAllMessages() {
        if (StatSummarizer.isDisabled()) {
            addFormError("Graphing not supported with this JVM: " +
                         System.getProperty("java.vendor") + ' ' +
                         System.getProperty("java.version") + " (" +
                         System.getProperty("java.runtime.name") + ' ' +
                         System.getProperty("java.runtime.version") + ')');
            if (_context.getProperty(PROP_REFRESH, 0) >= 0) {
                // force no refresh, save silently
                _context.router().saveConfig(PROP_REFRESH, "-1");
            }
        }
        return super.getAllMessages();
    }

    /**
     *  This was a HelperBase but now it's a FormHandler
     *  @since 0.8.2
     */
    @Override
    protected void processForm() {
        saveSettings();
    }

    /**
     *  Silently save settings if changed, no indication of success or failure
     *  @since 0.7.10
     */
    private void saveSettings() {
        if (_width != _context.getProperty(PROP_X, DEFAULT_X) ||
            _height != _context.getProperty(PROP_Y, DEFAULT_Y) ||
            _periodCount != _context.getProperty(PROP_PERIODS, DEFAULT_PERIODS) ||
            _refreshDelaySeconds != _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH) ||
            _showEvents != _context.getBooleanProperty(PROP_EVENTS) ||
            _persistent != _context.getBooleanPropertyDefaultTrue(SummaryListener.PROP_PERSISTENT)) {
            Map<String, String> changes = new HashMap();
            changes.put(PROP_X, "" + _width);
            changes.put(PROP_Y, "" + _height);
            changes.put(PROP_PERIODS, "" + _periodCount);
            changes.put(PROP_REFRESH, "" + _refreshDelaySeconds);
            changes.put(PROP_EVENTS, "" + _showEvents);
            changes.put(SummaryListener.PROP_PERSISTENT, "" + _persistent);
            _context.router().saveConfig(changes, null);
            addFormNotice(_("Graph settings saved"));
        }
    }

    private static class AlphaComparator implements Comparator<SummaryListener> {
        public int compare(SummaryListener l, SummaryListener r) {
            String lName = l.getRate().getRateStat().getName();
            String rName = r.getRate().getRateStat().getName();
            int rv = lName.compareTo(rName);
            if (rv != 0)
                return rv;
            return (int) (l.getRate().getPeriod() - r.getRate().getPeriod());
        }
    }
}
