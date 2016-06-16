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

<jsp:useBean class="net.i2p.router.web.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1><%=intl._t("Plugin Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
<%
   if (clientshelper.showPlugins()) {
       if (clientshelper.isPluginUpdateEnabled()) {
%>
<h3><a name="pconfig"></a><%=intl._t("Plugin Configuration")%></h3><p>
 <%=intl._t("The plugins listed below are started by the webConsole client.")%>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="clientshelper" property="form3" />
<div class="formaction">
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
 <input type="submit" name="action" class="accept" value="<%=intl._t("Save Plugin Configuration")%>" />
</div></form></div>
<%
       } // pluginUpdateEnabled
       if (clientshelper.isPluginInstallEnabled()) {
%>
<h3><a name="plugin"></a><%=intl._t("Plugin Installation from URL")%></h3><p>
 <%=intl._t("Look for available plugins on {0}.", "<a href=\"http://i2pwiki.i2p/index.php?title=Plugins\">i2pwiki.i2p</a>")%>
 <%=intl._t("To install a plugin, enter the download URL:")%>
 </p>
<div class="wideload">
<form action="configplugins" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<p>
 <input type="text" size="60" name="pluginURL" >
 </p><hr><div class="formaction">
 <input type="submit" name="action" class="default" value="<%=intl._t("Install Plugin")%>" />
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
 <input type="submit" name="action" class="download" value="<%=intl._t("Install Plugin")%>" />
</div></form></div>


<div class="wideload">
<h3><a name="plugin"></a><%=intl._t("Plugin Installation from File")%></h3>
<form action="configplugins" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<p><%=intl._t("Install plugin from file.")%>
<br><%=intl._t("Select xpi2p or su3 file")%> :
<input type="file" name="pluginFile" >
</p><hr><div class="formaction">
<input type="submit" name="action" class="download" value="<%=intl._t("Install Plugin from File")%>" />
</div></form></div>
<%
       } // pluginInstallEnabled
       if (clientshelper.isPluginUpdateEnabled()) {
%>
<h3><a name="plugin"></a><%=intl._t("Update All Plugins")%></h3>
<div class="formaction">
<form action="configplugins" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="submit" name="action" class="reload" value="<%=intl._t("Update All Installed Plugins")%>" />
</form></div>
<%
       } // pluginUpdateEnabled
   } // showPlugins
%>
</div></div></body></html>
