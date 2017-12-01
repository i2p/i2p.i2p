<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
/*
 * All links in the summary bar must have target="_top"
 * so they don't load in the iframe
 */
%>
<html><head>
<%@include file="css.jsi" %>
<title>Summary Bar</title>
<%
    // try hard to avoid an error page in the iframe after shutdown
    String action = request.getParameter("action");
    String d = request.getParameter("refresh");
    // Normal browsers send value, IE sends button label
    boolean allowIFrame = intl.allowIFrame(request.getHeader("User-Agent"));
    boolean shutdownSoon = (!allowIFrame) ||
                           "shutdownImmediate".equals(action) || "restartImmediate".equals(action) ||
                           "Shutdown immediately".equals(action) || "Restart immediately".equals(action);
    if (!shutdownSoon) {
        if (d == null || "".equals(d)) {
            // set below
        } else if (net.i2p.router.web.CSSHelper.getNonce().equals(conNonceParam)) {
            d = net.i2p.data.DataHelper.stripHTML(d);  // XSS
            intl.setRefresh(d);
            intl.setDisableRefresh(d);
        }
        d = intl.getRefresh();
        // we probably don't get here if d == "0" since caught in summary.jsi, but just
        // to be sure...
        if (!intl.getDisableRefresh()) {
            // doesn't work for restart or shutdown with no expl. tunnels,
            // since the call to ConfigRestartBean.renderStatus() hasn't happened yet...
            // So we delay slightly
            if (action != null &&
                ("restart".equals(action.toLowerCase(java.util.Locale.US)) || "shutdown".equals(action.toLowerCase(java.util.Locale.US)))) {
                synchronized(this) {
                    try {
                        wait(1000);
                    } catch(InterruptedException ie) {}
                }
            }
            long timeleft = net.i2p.router.web.helpers.ConfigRestartBean.getRestartTimeRemaining();
            long delay = 60;
            try { delay = Long.parseLong(d); } catch (NumberFormatException nfe) {}
            if (delay*1000 < timeleft + 5000)
                out.print("<meta http-equiv=\"refresh\" content=\"" + delay + ";url=/summaryframe.jsp\" >\n");
            else
                shutdownSoon = true;
        }
    }
%>
</head><body style="margin: 0;"><div class="routersummary">
<jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
<jsp:setProperty name="newshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    java.io.File newspath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
<jsp:setProperty name="newshelper" property="page" value="<%=newspath.getAbsolutePath()%>" />
<jsp:setProperty name="newshelper" property="maxLines" value="300" />
<%@include file="summarynoframe.jsi" %>
<%
    // d and shutdownSoon defined above
    if (!shutdownSoon) {
        out.print("<hr>\n<div class=\"refresh\"><form action=\"summaryframe.jsp\" method=\"POST\">\n");
        if (intl.getDisableRefresh()) {
            out.print("<b>");
            out.print(intl._t("Refresh (s)"));
            out.print(":</b> <input size=\"3\" type=\"text\" name=\"refresh\" value=\"60\" >\n");
            out.print("<button type=\"submit\" value=\"Enable\" >");
            out.print(intl._t("Enable"));
        } else {
            // this will load in the iframe but subsequent pages will not have the iframe
            out.print("<input type=\"hidden\" name=\"refresh\" value=\"0\" >\n");
            out.print("<button type=\"submit\" value=\"Disable\" >");
            long refreshMS = 60*1000;
            try {
                refreshMS = 1000 * Long.parseLong(d);
            } catch (NumberFormatException nfe) {}
            String refreshTime = net.i2p.data.DataHelper.formatDuration2(refreshMS);
            out.print(intl._t("Disable {0} Refresh", refreshTime));
        }
        out.print("</button></form></div>\n");
    }
%>
</div></body></html>
