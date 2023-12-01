<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config update")%>
<%@include file="summaryajax.jsi" %>
<%
    if ("POST".equals(request.getMethod())) {
        // refresh the sidebar after page loads
%>
<script src="/js/configupdate.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<%
    }
%>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Configuration")%></h1>
<div class="main" id="config_update">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>" />
<div class="messages">
 <jsp:getProperty name="updatehelper" property="newsStatus" /></div>
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <% /* set hidden default */ %>
 <input type="submit" name="action" value="" style="display:none" >
    <% if (updatehelper.canInstall()) { %>
      <h3 class="tabletitle"><%=intl._t("Check for I2P and News Updates")%></h3>
      <table id="i2pupdates" class="configtable" border="0" cellspacing="5">
      <tr><td align="right"><b><%=intl._t("News &amp; I2P Updates")%>:</b></td>
     <% } else { %>
      <h3><%=intl._t("Check for news updates")%></h3>
      <table id="i2pupdates" class="configtable" border="0" cellspacing="5">
        <tr><td colspan="2"></tr>
        <tr><td align="right"><b><%=intl._t("News Updates")%>:</b></td>
     <% }   // if canInstall %>
          <td> <% if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) { %> <i><%=intl._t("Update In Progress")%></i><br> <% } else { %> <input type="submit" name="action" class="check" value="<%=intl._t("Check for updates")%>" />
            <% } %></td></tr>
        <tr><td align="right"><b><%=intl._t("News URL")%>:</b></td>
          <td><input type="text" size="60" name="newsURL" <% if (!updatehelper.isAdvanced()) { %>readonly="readonly"<% } %> value="<jsp:getProperty name="updatehelper" property="newsURL" />"></td>
        </tr><tr><td align="right"><b><%=intl._t("Refresh frequency")%>:</b>
          <td><jsp:getProperty name="updatehelper" property="refreshFrequencySelectBox" /></td></tr>
    <% if (updatehelper.canInstall()) { %>
        <tr><td align="right"><b><%=formhandler._t("Update policy")%>:</b></td>
          <td><jsp:getProperty name="updatehelper" property="updatePolicySelectBox" /></td></tr>
    <% }   // if canInstall %>
    <% if (updatehelper.isAdvanced()) { %>
        <tr><td align="right"><label for="newsThroughProxy"><b><%=intl._t("Fetch news through the proxy?")%></b></label></td>
          <td><jsp:getProperty name="updatehelper" property="newsThroughProxy" /></td></tr>
      <% if (updatehelper.canInstall()) { %>
        <tr><td align="right"><label for="updateThroughProxy"><b><%=intl._t("Update through the proxy?")%></b></label></td>
          <td><jsp:getProperty name="updatehelper" property="updateThroughProxy" /></td></tr>
      <% }   // if canInstall %>
        <tr><td align="right"><b><%=intl._t("Proxy host")%>:</b></td>
          <td><input type="text" size="10" name="proxyHost" value="<jsp:getProperty name="updatehelper" property="proxyHost" />" /></td>
        </tr><tr><td align="right"><b><%=intl._t("Proxy port")%>:</b></td>
          <td><input type="text" size="10" name="proxyPort" value="<jsp:getProperty name="updatehelper" property="proxyPort" />" /></td></tr>
    <% }   // if isAdvanced %>
    <% if (updatehelper.canInstall()) { %>
      <% if (updatehelper.isAdvanced()) { %>
        <tr><td align="right"><b><%=intl._t("Update URLs")%>:</b></td>
          <td><textarea cols="60" rows="6" name="updateURL" wrap="off" spellcheck="false"><jsp:getProperty name="updatehelper" property="updateURL" /></textarea></td>
        </tr><tr><td align="right"><b><%=intl._t("Trusted keys")%>:</b></td>
          <td><textarea cols="60" rows="6" name="trustedKeys" wrap="off" spellcheck="false"><jsp:getProperty name="updatehelper" property="trustedKeys" /></textarea></td></tr>
        <tr><td id="devSU3build" align="right"><label for="updateDevSU3"><b><%=intl._t("Update with signed development builds?")%></b></label></td>
          <td><jsp:getProperty name="updatehelper" property="updateDevSU3" /></td>
        </tr><tr><td align="right"><b><%=intl._t("Signed Build URL")%>:</b></td>
          <td><input type="text" size="60" name="devSU3URL" value="<jsp:getProperty name="updatehelper" property="devSU3URL" />"></td></tr>
        <tr><td id="unsignedbuild" align="right"><label for="updateUnsigned"><b><%=intl._t("Update with unsigned development builds?")%></b></label></td>
          <td><jsp:getProperty name="updatehelper" property="updateUnsigned" /></td>
        </tr><tr><td align="right"><b><%=intl._t("Unsigned Build URL")%>:</b></td>
          <td><input type="text" size="60" name="zipURL" value="<jsp:getProperty name="updatehelper" property="zipURL" />"></td></tr>
      <% }   // if isAdvanced %>
    <% } else { %>
        <tr><td align="center" colspan="2"><b><%=intl._t("Updates will be dispatched via your package manager.")%></b></td></tr>
    <% }   // if canInstall %>
        <tr class="tablefooter"><td colspan="2" class="optionsave">
            <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
            <input type="submit" name="action" class="accept" value="<%=intl._t("Save")%>" >
        </td></tr></table></form></div></body></html>
