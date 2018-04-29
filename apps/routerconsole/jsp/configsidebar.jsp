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
<h1><%=intl._t("I2P Summary Bar Configuration")%></h1>
<div class="main" id="config_summarybar">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigSummaryHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<%
    formhandler.setMovingAction();
%>
<jsp:useBean class="net.i2p.router.web.helpers.SummaryHelper" id="summaryhelper" scope="request" />
<jsp:setProperty name="summaryhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h3 class="tabletitle"><%=intl._t("Refresh Interval")%></h3>
<form action="" method="POST">
<table class="configtable">
 <tr>
  <td>
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="0">
 <input type="text" name="refreshInterval" value="<jsp:getProperty name="intl" property="refresh" />" >
 <%=intl._t("seconds")%>
  </td>
  <td class="optionsave">
 <input type="submit" name="action" class="accept" value="<%=intl._t("Save")%>" >
  </td>
 </tr>
</table>
</form>

<h3 class="tabletitle"><%=intl._t("Customize Summary Bar")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="2">
 <jsp:getProperty name="summaryhelper" property="configTable" />
 <div class="formaction" id="sidebardefaults">
  <input type="submit" class="reload" name="action" value="<%=intl._t("Restore full default")%>" >
  <input type="submit" class="reload" name="action" value="<%=intl._t("Restore minimal default")%>" >
 </div>
</form>
</div></body></html>
