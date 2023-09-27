<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<%
  /*
   *   Do not tag this file for translation.
   */
%>
<html><head><title>I2P Router Console - internals</title>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.OldConsoleHelper" id="conhelper" scope="request" />
<jsp:setProperty name="conhelper" property="contextId" value="<%=i2pcontextId%>" />
<% conhelper.storeWriter(out); %>
 <h1>I2P Router &raquo; Old Console</h1>
<div class="main" id="oldconsole"><p>
 <jsp:getProperty name="conhelper" property="console" />
</p></div></body></html>
