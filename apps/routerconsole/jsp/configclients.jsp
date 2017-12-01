<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config clients")%>
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
<h1><%=intl._t("I2P Client Configuration")%></h1>
<div class="main" id="config_clients">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
 <h3 id="i2pclientconfig"><%=intl._t("Client Configuration")%>&nbsp;
  <span class="h3navlinks">
   <a href="configi2cp" title="<%=intl._t("Advanced Client Interface Configuration")%>">[I2CP]</a>&nbsp;
   <a href="configplugins" title="<%=intl._t("Plugin Configuration")%>">[Plugins]</a>&nbsp;
   <a href="configwebapps" title="<%=intl._t("WebApp Configuration")%>">[WebApps]</a>
  </span>
 </h3>
 <p class="infohelp" id="clientconf">
 <%=intl._t("The Java clients listed below are started by the router and run in the same JVM.")%>&nbsp;
 <%=intl._t("To change other client options, edit the file")%>
 <tt><%=net.i2p.router.startup.ClientAppConfig.configFile(net.i2p.I2PAppContext.getGlobalContext()).getAbsolutePath()%>.</tt>
 <%=intl._t("All changes require restart to take effect.")%>
 </p>
 <p class="infowarn" id="clientconf">
  <b><%=intl._t("Be careful changing any settings here. The 'router console' and 'application tunnels' are required for most uses of I2P. Only advanced users should change these.")%></b>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="clientshelper" property="form1" />
<div class="formaction" id="clientsconfig">
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<% if (clientshelper.isClientChangeEnabled() && request.getParameter("edit") == null) { %>
 <input type="submit" name="edit" class="add" value="<%=intl._t("Add Client")%>" />
<% } %>
 <input type="submit" class="accept" name="action" value="<%=intl._t("Save Client Configuration")%>" />
</div></form></div>
</div>
</div></body></html>
