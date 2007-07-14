package net.i2p.router.web;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.router.RouterContext;

public class GraphHelper {
    private RouterContext _context;
    private Writer _out;
    private int _periodCount;
    private boolean _showEvents;
    private int _width;
    private int _height;
    private int _refreshDelaySeconds;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    public GraphHelper() {
        _periodCount = 60; // SummaryListener.PERIODS;
        _showEvents = false;
        _width = 250;
        _height = 100;
        _refreshDelaySeconds = 60;
    }
    
    public void setOut(Writer out) { _out = out; }
    public void setPeriodCount(String str) { 
        try { _periodCount = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
    }
    public void setShowEvents(boolean b) { _showEvents = b; }
    public void setHeight(String str) {
        try { _height = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
    }
    public void setWidth(String str) {
        try { _width = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
    }
    public void setRefreshDelay(String str) {
        try { _refreshDelaySeconds = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
    }
    
    public String getImages() { 
        try {
            if (!_showEvents)
                _out.write("<img width=\""
                           + (_width + 83) + "\" height=\"" + (_height + 92)
                           + "\" src=\"viewstat.jsp?stat=bw.combined"
                           + "&amp;periodCount=" + _periodCount 
                           + "&amp;width=" + _width
                           + "&amp;height=" + (_height - 14)
                           + "\" title=\"Combined bandwidth graph\" />\n");
            
            List listeners = StatSummarizer.instance().getListeners();
            TreeSet ordered = new TreeSet(new AlphaComparator());
            ordered.addAll(listeners);
            for (Iterator iter = ordered.iterator(); iter.hasNext(); ) {
                SummaryListener lsnr = (SummaryListener)iter.next();
                Rate r = lsnr.getRate();
                String title = r.getRateStat().getName() + " for " + DataHelper.formatDuration(_periodCount * r.getPeriod());
                _out.write("<img width=\""
                           + (_width + 83) + "\" height=\"" + (_height + 92)
                           + "\" src=\"viewstat.jsp?stat="
                           + r.getRateStat().getName() 
                           + "&amp;showEvents=" + _showEvents
                           + "&amp;period=" + r.getPeriod() 
                           + "&amp;periodCount=" + _periodCount 
                           + "&amp;width=" + _width
                           + "&amp;height=" + _height
                           + "\" title=\"" + title + "\" />\n");
            }
            if (_refreshDelaySeconds > 0)
                _out.write("<meta http-equiv=\"refresh\" content=\"" + _refreshDelaySeconds + "\" />\n");

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }
    public String getForm() { 
        try {
            _out.write("<form action=\"graphs.jsp\" method=\"GET\">");
            _out.write("Periods: <input size=\"3\" type=\"text\" name=\"periodCount\" value=\"" + _periodCount + "\" /><br />\n");
            _out.write("Plot averages: <input type=\"radio\" name=\"showEvents\" value=\"false\" " + (_showEvents ? "" : "checked=\"true\" ") + " /> ");
            _out.write("or plot events: <input type=\"radio\" name=\"showEvents\" value=\"true\" "+ (_showEvents ? "checked=\"true\" " : "") + " /><br />\n");
            _out.write("Image sizes: width: <input size=\"4\" type=\"text\" name=\"width\" value=\"" + _width 
                       + "\" /> pixels, height: <input size=\"4\" type=\"text\" name=\"height\" value=\"" + _height  
                       + "\" /><br />\n");
            _out.write("Refresh delay: <select name=\"refreshDelay\"><option value=\"60\">1 minute</option><option value=\"120\">2 minutes</option><option value=\"300\">5 minutes</option><option value=\"600\">10 minutes</option><option value=\"-1\">Never</option></select><br />\n");
            _out.write("<input type=\"submit\" value=\"Redraw\" />");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }
    public String getPeerSummary() {
        try {
            _context.commSystem().renderStatusHTML(_out);
            _context.bandwidthLimiter().renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }
}

class AlphaComparator implements Comparator {
    public int compare(Object lhs, Object rhs) {
        SummaryListener l = (SummaryListener)lhs;
        SummaryListener r = (SummaryListener)rhs;
        String lName = l.getRate().getRateStat().getName() + "." + l.getRate().getPeriod();
        String rName = r.getRate().getRateStat().getName() + "." + r.getRate().getPeriod();
        return lName.compareTo(rName);
    }
}
