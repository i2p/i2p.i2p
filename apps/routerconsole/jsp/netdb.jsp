<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("network database summary")%>
</head><body>
<%@include file="summary.jsi" %>
 <h1><%=intl._("I2P Network Database Summary")%></h1>
<div class="main" id="main">
 <div class="wideload">
 <jsp:useBean class="net.i2p.router.web.NetDbHelper" id="netdbHelper" scope="request" />
 <jsp:setProperty name="netdbHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <% netdbHelper.storeWriter(out); %>
 <jsp:setProperty name="netdbHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:setProperty name="netdbHelper" property="router" value="<%=request.getParameter(\"r\")%>" />
 <jsp:setProperty name="netdbHelper" property="lease" value="<%=request.getParameter(\"l\")%>" />
 <jsp:getProperty name="netdbHelper" property="netDbSummary" />
</div></div></body></html>
