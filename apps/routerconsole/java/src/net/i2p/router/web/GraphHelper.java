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
    private String _stat;
    private int _end;

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
    private static final int MIN_X = 200;
    private static final int MIN_Y = 60;
    private static final int MIN_C = 20;
    private static final int MAX_C = SummaryListener.MAX_ROWS;
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
        setC(str);
    }

    /** @since 0.9 */
    public void setE(String str) { 
        try {
            _end = Math.max(0, Integer.parseInt(str));
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9 shorter parameter */
    public void setC(String str) { 
        try {
            _periodCount = Math.max(MIN_C, Math.min(Integer.parseInt(str), MAX_C));
        } catch (NumberFormatException nfe) {}
    }

    public void setShowEvents(String b) { _showEvents = !"false".equals(b); }

    public void setHeight(String str) {
        setH(str);
    }

    /** @since 0.9 shorter parameter */
    public void setH(String str) { 
        try {
            _height = Math.max(MIN_Y, Math.min(Integer.parseInt(str), MAX_Y));
        } catch (NumberFormatException nfe) {}
    }

    public void setWidth(String str) {
        setW(str);
    }

    /** @since 0.9 shorter parameter */
    public void setW(String str) { 
        try {
            _width = Math.max(MIN_X, Math.min(Integer.parseInt(str), MAX_X));
        } catch (NumberFormatException nfe) {}
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

    /**
     *  For single stat page
     *  @since 0.9
     */
    public void setStat(String stat) {
        _stat = stat;
    }
    
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
                _out.write("<a href=\"viewstat?stat=bw.combined"
                           + "&amp;periodCount=" + (3 * _periodCount )
                           + "&amp;width=" + (3 * _width)
                           + "&amp;height=" + (3 * _height)
                           + "\">");
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
                _out.write("<a href=\"graph?stat="
                           + r.getRateStat().getName() 
                           + '.' + r.getPeriod() 
                           + "&amp;c=" + (3 * _periodCount)
                           + "&amp;w=" + (3 * _width)
                           + "&amp;h=" + (3 * _height)
                           + (_showEvents ? "&amp;showEvents=1" : "")
                           + "\">");
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
            // 0.9.1 - all graphs currently state UTC on them, so this text blurb is unnecessary,
            //_out.write("<p><i>" + _("All times are UTC.") + "</i></p>\n");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }

    /**
     *  For single stat page
     *  @since 0.9
     */
    public String getSingleStat() {
        try {
            if (StatSummarizer.isDisabled())
                return "";
            if (_stat == null) {
                _out.write("No stat");
                return "";
            }
            List<Rate> rates = StatSummarizer.instance().parseSpecs(_stat);
            if (rates.size() != 1) {
                _out.write("Graphs not enabled for " + _stat);
                return "";
            }
            Rate r = rates.get(0);
            _out.write("<h3>");
            _out.write(_("{0} for {1}", r.getRateStat().getName(), DataHelper.formatDuration2(_periodCount * r.getPeriod())));
            if (_end > 0)
                _out.write(' ' + _("ending {0} ago", DataHelper.formatDuration2(_end * r.getPeriod())));

            _out.write("</h3><img class=\"statimage\" border=\"0\""
                       + " src=\"viewstat.jsp?stat="
                       + r.getRateStat().getName() 
                       + "&amp;showEvents=" + _showEvents
                       + "&amp;period=" + r.getPeriod() 
                       + "&amp;periodCount=" + _periodCount 
                       + "&amp;end=" + _end 
                       + "&amp;width=" + _width
                       + "&amp;height=" + _height
                       + "\"><p>\n");

            if (_width < MAX_X && _height < MAX_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height * 3 / 2));
                _out.write(_("Larger"));
                _out.write("</a> - ");
            }

            if (_width > MIN_X && _height > MIN_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height * 2 / 3));
                _out.write(_("Smaller"));
                _out.write("</a> - ");
            }

            if (_height < MAX_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width, _height * 3 / 2));
                _out.write(_("Taller"));
                _out.write("</a> - ");
            }

            if (_height > MIN_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width, _height * 2 / 3));
                _out.write(_("Shorter"));
                _out.write("</a> - ");
            }

            if (_width < MAX_X) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height));
                _out.write(_("Wider"));
                _out.write("</a> - ");
            }

            if (_width > MIN_X) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height));
                _out.write(_("Narrower"));
                _out.write("</a>");
            }

            _out.write("<br>");
            if (_periodCount < MAX_C) {
                _out.write(link(_stat, _showEvents, _periodCount * 2, _end, _width, _height));
                _out.write(_("Larger interval"));
                _out.write("</a> - ");
            }

            if (_periodCount > MIN_C) {
                _out.write(link(_stat, _showEvents, _periodCount / 2, _end, _width, _height));
                _out.write(_("Smaller interval"));
                _out.write("</a>");
            }

            _out.write("<br>");
            if (_periodCount < MAX_C) {
                _out.write(link(_stat, _showEvents, _periodCount, _end + _periodCount, _width, _height));
                _out.write(_("Previous interval"));
                _out.write("</a>");
            }

            if (_end > 0) {
                int end = _end - _periodCount;
                if (end <= 0)
                    end = 0;
                if (_periodCount < MAX_C)
                    _out.write(" - ");
                _out.write(link(_stat, _showEvents, _periodCount, end, _width, _height));
                _out.write(_("Next interval"));
                _out.write("</a> ");
            }

            _out.write("<br>");
            _out.write(link(_stat, !_showEvents, _periodCount, _end, _width, _height));
            _out.write(_showEvents ? _("Plot averages") : _("plot events"));
            _out.write("</a>");

            _out.write("</p><p><i>" + _("All times are UTC.") + "</i></p>\n");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }

    /** @since 0.9 */
    private static String link(String stat, boolean showEvents,
                               int periodCount, int end,
                               int width, int height) {
        return
               "<a href=\"graph?stat="
               + stat
               + "&amp;c=" + periodCount
               + "&amp;w=" + width
               + "&amp;h=" + height
               + (end > 0 ? "&amp;e=" + end : "")
               + (showEvents ? "&amp;showEvents=1" : "")
               + "\">";
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
            _out.write(_("Plot averages") + ": <input type=\"radio\" class=\"optbox\" name=\"showEvents\" value=\"false\" " + (_showEvents ? "" : "checked=\"checked\" ") + "> ");
            _out.write(_("or")+ " " +_("plot events") + ": <input type=\"radio\" class=\"optbox\" name=\"showEvents\" value=\"true\" "+ (_showEvents ? "checked=\"checked\" " : "") + "><br>\n");
            _out.write(_("Image sizes") + ": " + _("width") + ": <input size=\"4\" style=\"text-align: right;\" type=\"text\" name=\"width\" value=\"" + _width 
                       + "\"> " + _("pixels") + ", " + _("height") + ": <input size=\"4\" style=\"text-align: right;\" type=\"text\" name=\"height\" value=\"" + _height  
                       + "\"> " + _("pixels") + "<br>\n");
            _out.write(_("Refresh delay") + ": <select name=\"refreshDelay\">");
            for (int i = 0; i < times.length; i++) {
                _out.write("<option value=\"");
                _out.write(Integer.toString(times[i]));
                _out.write("\"");
                if (times[i] == _refreshDelaySeconds)
                    _out.write(" selected=\"selected\"");
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
                _out.write(" checked=\"checked\"");
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
