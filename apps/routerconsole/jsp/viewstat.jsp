<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */

boolean rendered = false;
/****  unused
String templateFile = request.getParameter("template");
if (templateFile != null) {
  java.io.OutputStream cout = response.getOutputStream();
  response.setContentType("image/png");
  rendered = net.i2p.router.web.StatSummarizer.instance().renderPng(cout, templateFile);
}
****/
net.i2p.stat.Rate rate = null;
String stat = request.getParameter("stat");
String period = request.getParameter("period");
boolean fakeBw = (stat != null && ("bw.combined".equals(stat)));
net.i2p.stat.RateStat rs = null;
if (stat != null)
    rs = net.i2p.I2PAppContext.getGlobalContext().statManager().getRate(stat);
if ( !rendered && ((rs != null) || fakeBw) ) {
  long per = -1;
  try {
    if (fakeBw)
      per = 60*1000;
    else
      per = Long.parseLong(period);
    if (!fakeBw)
      rate = rs.getRate(per);
    if ( (rate != null) || (fakeBw) ) {
      java.io.OutputStream cout = response.getOutputStream();
      String format = request.getParameter("format");
      if ("xml".equals(format)) {
        if (!fakeBw) {
          response.setContentType("text/xml");
          rendered = net.i2p.router.web.StatSummarizer.instance().getXML(rate, cout);
        }
      } else {
        response.setContentType("image/png");
        int width = -1;
        int height = -1;
        int periodCount = -1;
        String str = request.getParameter("width");
        if (str != null) try { width = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
        str = request.getParameter("height");
        if (str != null) try { height = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
        str = request.getParameter("periodCount");
        if (str != null) try { periodCount = Integer.parseInt(str); } catch (NumberFormatException nfe) {}
        boolean hideLegend = Boolean.valueOf(""+request.getParameter("hideLegend")).booleanValue();
        boolean hideGrid = Boolean.valueOf(""+request.getParameter("hideGrid")).booleanValue();
        boolean hideTitle = Boolean.valueOf(""+request.getParameter("hideTitle")).booleanValue();
        boolean showEvents = Boolean.valueOf(""+request.getParameter("showEvents")).booleanValue();
        boolean showCredit = false;
        if (request.getParameter("showCredit") != null)
          showCredit = Boolean.valueOf(""+request.getParameter("showCredit")).booleanValue();
        if (fakeBw)
            rendered = net.i2p.router.web.StatSummarizer.instance().renderRatePng(cout, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit);
        else
            rendered = net.i2p.router.web.StatSummarizer.instance().renderPng(rate, cout, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit);
      }
      if (rendered)
        cout.close();
      //System.out.println("Rendered period " + per + " for the stat " + stat + "? " + rendered);
    }
  } catch (NumberFormatException nfe) {}
}
/*
 *  Send a 403 instead of a 404, because the server sends error.jsp
 *  for 404 errors, complete with the summary bar, which would be
 *  a huge load for a page full of graphs if there's a problem
 */
if (!rendered) {
    if (stat != null)
        response.sendError(403, "The stat " + stat + " is not available, it must be enabled for graphing on the stats configuration page.");
    else
        response.sendError(403, "No stat specified");
}
%>