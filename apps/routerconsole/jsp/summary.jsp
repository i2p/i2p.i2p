<% // put width here too to prevent bad layout at startup %>
<% // let's remove that for now since we're no longer using percentage width here %>
<div class="routersummaryouter">
<%
    // skip the iframe if refresh disabled
    String d = request.getParameter("refresh");
    String newDelay = "";
    if (d == null || "".equals(d))
        d = System.getProperty("routerconsole.summaryRefresh");
    else
        // pass the new delay parameter to the iframe
        newDelay = "?refresh=" + d;
    if (!"0".equals(d))
        out.print("<iframe src=\"summaryframe.jsp" + newDelay + "\" height=\"1500\" width=\"200\" scrolling=\"auto\" frameborder=\"0\" allowtransparency=\"true\">\n");
%>
<div class="routersummary">
<%@include file="summarynoframe.jsp" %>
<%
    // d defined above
    if (!"0".equals(d)) {
        out.print("</div></iframe>\n");
    } else {
        // since we don't have an iframe this will reload the base page, and
        // the new delay will be passed to the iframe above
        out.print("<form action=\"" + request.getRequestURI() + "\" method=\"GET\">\n");
        out.print("<b>Refresh (s):</b> <input size=\"3\" type=\"text\" name=\"refresh\" value=\"60\" />\n");
        out.print("<button type=\"submit\">Enable</button>\n");
        out.print("</form></div>\n");
    }
%>
</div></div>
