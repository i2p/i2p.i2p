<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config plugins")%>
<style type='text/css'>
button span.hide{
    display:none;
}
input.default { width: 1px; height: 1px; visibility: hidden; }
</style>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var deleteMessage = "<%=intl._t("Are you sure you want to delete {0}?")%>";
</script>
<script src="/js/configclients.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head><body>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1><%=intl._t("Configuration")%></h1>
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
<h3 id="pluginmanage"><%=intl._t("Plugin Installation")%></h3><p>
<form action="configplugins" method="POST">
<table id="plugininstall" class="configtable">
<tr id="url"><td class="infohelp" colspan="2">
 <%=intl._t("Look for available plugins on {0}.", "<a href=\"http://wiki.i2p-projekt.i2p/wiki/index.php/Plugins\" target=\"_blank\">i2pwiki.i2p</a>")%>
</td></tr>
<tr><th colspan="2">
 <%=intl._t("Installation from URL")%>
</th></tr>
<tr>
<td>
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <b>URL:</b>
<%
   String url = request.getParameter("pluginURL");
   String value = url != null ? "value=\"" + net.i2p.data.DataHelper.escapeHTML(url) + '"' : "";
%>
 <input type="text" size="60" name="pluginURL" title="<%=intl._t("To install a plugin, enter the download URL:")%>" <%=value%>>
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
<tr id="file"><th colspan="2">
<%=intl._t("Installation from File")%>
</th></tr>
<tr>
<td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<b><%=intl._t("Select xpi2p or su3 file")%>:</b>
<%
   String file = request.getParameter("pluginFile");
   if (file != null && file.length() > 0) {
%>
<input type="text" size="60" name="pluginFile" value="<%=net.i2p.data.DataHelper.escapeHTML(file)%>">
<%
   } else {
%>
<input type="file" name="pluginFile" accept=".xpi2p,.su3" >
<%
   }
%>
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
<h4 id="updateplugins" class="embeddedtitle"><%=intl._t("Update All Plugins")%></h4>
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
