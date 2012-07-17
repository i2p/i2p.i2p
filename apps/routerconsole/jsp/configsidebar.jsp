<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config summary bar")%>
<style type='text/css'>
input.default {
    width: 1px;
    height: 1px;
    visibility: hidden;
}
</style>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Summary Bar Configuration")%></h1>
<div class="main" id="main">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigSummaryHandler" id="formhandler" scope="request" />
<% formhandler.storeMethod(request.getMethod()); %>
<jsp:setProperty name="formhandler" property="*" />
<jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:setProperty name="formhandler" property="settings" value="<%=request.getParameterMap()%>" />
<%
    formhandler.setMovingAction();
%>
<jsp:getProperty name="formhandler" property="allMessages" />
<%
    String pageNonce = formhandler.getNewNonce();
%>
<jsp:useBean class="net.i2p.router.web.SummaryHelper" id="summaryhelper" scope="request" />
<jsp:setProperty name="summaryhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h3><%=intl._("Refresh Interval")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="0">
 <input type="text" name="refreshInterval" value="<jsp:getProperty name="intl" property="refresh" />" >
 <%=intl._("seconds")%>
 <input type="submit" name="action" class="accept" value="<%=intl._("Save")%>" >
</form>

<h3><%=intl._("Customise Summary Bar")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="2">
 <jsp:getProperty name="summaryhelper" property="configTable" />
 <div class="formaction">
  <input type="submit" class="reload" name="action" value="<%=intl._("Restore full default")%>" >
  <input type="submit" class="reload" name="action" value="<%=intl._("Restore minimal default")%>" >
 </div>
</form>
</div></body></html>
