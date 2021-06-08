<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config advanced")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=i2pcontextId%>" />

<h1><%=intl._t("Configuration")%></h1>
<div class="main" id="config_advanced">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
 <div class="wideload">
<p class="infohelp"><%=intl._t("Most of the router configuration options listed below are not available in the user interface, because they are rarely used or provide access to advanced settings that most users will not need.")%>
<%=intl._t("You must edit your <code>router.config</code> file to add options, or, if you have added <code>routerconsole.advanced=true</code> to the router.config file, you may edit settings within the console on this page.")%></p>

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

<h3 id="advancedconfig" class="tabletitle"><%=intl._t("Advanced I2P Configuration")%></h3>
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

<h3 id="ffconf" class="tabletitle"><%=intl._t("Advanced Configuration Help")%></h3>
<table id="configinfo">
<tr><th>routerconsole.advanced={true|false}</th></tr>
<tr><td><%=intl._t("Only set this to true if you know what you are doing!")%></td></tr>
<tr><td><%=intl._t("When set to true, additional functionality will be enabled in the console and the user will be able to edit settings directly on this page")%>. 
<%=intl._t("Extra display options are provided in the <a href=\"/netdb\">Network Database section</a>.")%> 
<%=intl._t("This will also enable the installation of unsigned updates.")%>
<%=intl._t("You may also wish to enable the advanced sidebar section on the <a href=\"/configsidebar\">Sidebar Configuration page</a>.")%></td></tr>

<tr><th>routerconsole.browser={/path/to/browser}</th></tr>
<tr><td><%=intl._t("This setting allows the manual selection of the browser which I2P will launch on startup.")%></td></tr>

<tr><th>routerconsole.showSearch={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, a configurable search bar will appear on the <a href=\"/home\">console home page</a>")%>. 
<%=intl._t("Additional searches may then be added on the <a href=\"/confighome\">home configuration page</a>.")%></td></tr>

<tr><th>router.maxParticipatingTunnels={n}</th></tr>
<tr><td><%=intl._t("Determines the maximum number of participating tunnels the router will accept.")%>
<%=intl._t("To disable participation completely, set to 0.")%></td></tr>

</table>
</div></div></div></body></html>
