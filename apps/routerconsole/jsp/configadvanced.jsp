<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config advanced")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h1><%=intl._t("I2P Advanced Configuration")%></h1>
<div class="main" id="config_advanced">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
 <div class="wideload">

<h3 id="ffconf" class="tabletitle"><%=intl._t("Floodfill Configuration")%></h3>
<form action="" method="POST">
 <table id="floodfillconfig" class="configtable">
  <tr><td class="infohelp">
<%=intl._t("Floodfill participation helps the network, but may use more of your computer's resources.")%>
<%
    if (advancedhelper.isFloodfill()) {
%> (<%=intl._t("This router is currently a floodfill participant.")%>)<%
    } else {
%> (<%=intl._t("This router is not currently a floodfill participant.")%>)<%
    }
%>
  </td></tr>
  <tr><td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="ff" >
<b><%=intl._t("Enrollment")%>:</b>
<label><input type="radio" class="optbox" name="ff" value="auto" <%=advancedhelper.getFFChecked(2) %> >
<%=intl._t("Automatic")%></label>&nbsp;
<label><input type="radio" class="optbox" name="ff" value="true" <%=advancedhelper.getFFChecked(1) %> >
<%=intl._t("Force On")%></label>&nbsp;
<label><input type="radio" class="optbox" name="ff" value="false" <%=advancedhelper.getFFChecked(0) %> >
<%=intl._t("Disable")%></label>
  </td></tr>
  <tr><td class="optionsave" align="right">
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
  </td></tr>
 </table>
</form>

<h3 id="advancedconfig" class="tabletitle"><%=intl._t("Advanced I2P Configuration")%>&nbsp;<a title="Help with additional configuration settings" href="/help#advancedsettings">[Additional Options]</a></h3>
<%
  String advConfig = advancedhelper.getSettings();
  if (advancedhelper.isAdvanced()) {
%>
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
 <input type="hidden" name="nofilter_oldConfig" value="<%=advConfig%>" >
<% }  // isAdvanced %>
<table class="configtable" id="advconf">
<% if (advancedhelper.isAdvanced()) { %>
 <tr><td class="infohelp">
<b><%=intl._t("NOTE")%>:</b> <%=intl._t("Some changes may require a restart to take effect.")%>
 </td></tr>
<% } else { %>
 <tr><td>
<%=intl._t("To make changes, edit the file: {0}", "<tt>" + advancedhelper.getConfigFileName() + "</tt>")%>
 </td></tr>
<% }  // isAdvanced %>
 <tr><td class="tabletextarea">
 <textarea id="advancedsettings" rows="32" cols="60" name="nofilter_config" wrap="off" spellcheck="false" <% if (!advancedhelper.isAdvanced()) { %>readonly="readonly"<% } %>><%=advConfig%></textarea>
 </td></tr>
<% if (advancedhelper.isAdvanced()) { %>
 <tr><td class="optionsave" align="right">
        <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
        <input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
 </td></tr>
<% }  // isAdvanced %>
</table>
<% if (advancedhelper.isAdvanced()) { %>
</form>
<% }  // isAdvanced %>
</div></div></div></body></html>
