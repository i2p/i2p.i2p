<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("statistics")%>
</head><body>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.OldConsoleHelper" id="oldhelper" scope="request" />
<jsp:setProperty name="oldhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% oldhelper.storeWriter(out); %>
<jsp:setProperty name="oldhelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <h1><%=intl._("I2P Router Statistics")%></h1>
<div class="main" id="main">
 <jsp:getProperty name="oldhelper" property="stats" />
<hr></div></body></html>
