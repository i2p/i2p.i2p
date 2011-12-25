<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation.
   */
%>
<html><head><title>I2P Router Console - internals</title>
<%@include file="css.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.OldConsoleHelper" id="conhelper" scope="request" />
<jsp:setProperty name="conhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% conhelper.storeWriter(out); %>
 <h1>I2P Router &raquo; Old Console</h1>
<div class="main" id="main">
 <jsp:getProperty name="conhelper" property="console" />
</div></body></html>
