<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("graphs")%>
 <jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request" />
 <jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
 <jsp:setProperty name="graphHelper" property="*" />
<%
    graphHelper.storeWriter(out);
%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<%
    // needs to be after the summary bar is rendered, so
    // that the restart button is processed
    String stat = request.getParameter("stat");
    if (stat == null) {
        // probably because restart or shutdown was clicked
        response.setStatus(307);
        response.setHeader("Location", "/graphs");
        // force commitment
        response.getWriter().close();
        return;
    }
%>
<h1><%=intl._t("I2P Performance Graphs")%></h1>
<div class="main" id="graph_single">
 <jsp:getProperty name="graphHelper" property="singleStat" />
</div></body></html>
