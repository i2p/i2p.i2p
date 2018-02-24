<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config plugins")%>
<style type='text/css'>
button span.hide{
    display:none;
}
input.default { width: 1px; height: 1px; visibility: hidden; }
</style>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1><%=intl._t("Plugin Configuration")%></h1>
<div class="main" id="config_plugins">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
<%
   if (clientshelper.showPlugins()) {
       if (clientshelper.isPluginUpdateEnabled()) {
%>
<h3 id="pconfig"><%=intl._t("Plugin Configuration")%></h3><p id="pluginconfigtext">
 <%=intl._t("The plugins listed below are started by the webConsole client.")%>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="clientshelper" property="form3" />
<div class="formaction" id="pluginconfigactions">
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
 <input type="submit" name="action" class="accept" value="<%=intl._t("Save Plugin Configuration")%>" />
</div></form></div>
<%
       } // pluginUpdateEnabled
       if (clientshelper.isPluginInstallEnabled()) {
%>
<h3 id="pluginmanage"><a name="plugin"></a><%=intl._t("Plugin Installation")%></h3><p>
<form action="configplugins" method="POST">
<table id="plugininstall" class="configtable">
<tr><td class="infohelp" colspan="2">
 <%=intl._t("Look for available plugins on {0}.", "<a href=\"http://i2pwiki.i2p/index.php?title=Plugins\" target=\"_blank\">i2pwiki.i2p</a>")%>
</td></tr>
<tr><th colspan="2">
 <%=intl._t("Installation from URL")%>
</th></tr>
<tr>
<td>
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <b>URL:</b>
 <input type="text" size="60" name="pluginURL" title="<%=intl._t("To install a plugin, enter the download URL:")%>" >
</td>
<td class="optionsave" align="right">
 <input type="submit" name="action" class="default hideme" value="<%=intl._t("Install Plugin")%>" />
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
 <input type="submit" name="action" class="download" value="<%=intl._t("Install Plugin")%>" />
</td>
</tr>
</table></form>
<form action="configplugins" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<table id="plugininstall2" class="configtable">
<tr><th colspan="2">
<a name="plugin"></a><%=intl._t("Installation from File")%>
</th></tr>
<tr>
<td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<b><%=intl._t("Select xpi2p or su3 file")%>:</b>
<input type="file" name="pluginFile" accept=".xpi2p,.su3" >
</td>
<td class="optionsave" align="right">
<input type="submit" name="action" class="download" value="<%=intl._t("Install Plugin from File")%>" />
</td>
</tr>
</table>
</form>
<%
       } // pluginInstallEnabled
       if (clientshelper.isPluginUpdateEnabled()) {
%>
<h4 id="updateplugins" class="embeddedtitle"><a name="plugin"></a><%=intl._t("Update All Plugins")%></h4>
<div class="formaction" id="pluginupdater">
<form action="configplugins" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="submit" name="action" class="reload" value="<%=intl._t("Update All Installed Plugins")%>" />
</form></div>
<%
       } // pluginUpdateEnabled
   } // showPlugins
%>
</div></div></body></html>
