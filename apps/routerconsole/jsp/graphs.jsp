<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("graphs")%>
 <jsp:useBean class="net.i2p.router.web.GraphHelper" id="graphHelper" scope="request" />
 <% graphHelper.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="graphHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
 <jsp:setProperty name="graphHelper" property="*" />
<%
    graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
    // meta must be inside the head
    boolean allowRefresh = intl.allowIFrame(request.getHeader("User-Agent"));
    if (allowRefresh) {
        out.print(graphHelper.getRefreshMeta());
    }
%>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Performance Graphs")%></h1>
<div class="main" id="main">
 <div class="graphspanel">
 <div class="widepanel">
 <jsp:getProperty name="graphHelper" property="allMessages" />
 <jsp:getProperty name="graphHelper" property="images" />
 <jsp:getProperty name="graphHelper" property="form" />
</div></div></div></body></html>
