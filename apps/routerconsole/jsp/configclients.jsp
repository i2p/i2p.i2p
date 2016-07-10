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

<jsp:useBean class="net.i2p.router.web.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1><%=intl._t("I2P Client Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
 <h3><%=intl._t("Client Configuration")%></h3><p>
 <%=intl._t("The Java clients listed below are started by the router and run in the same JVM.")%><br>
 <img src="/themes/console/images/itoopie_xsm.png" alt=""><b><%=intl._t("Be careful changing any settings here. The 'router console' and 'application tunnels' are required for most uses of I2P. Only advanced users should change these.")%></b>
 </p><div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="clientshelper" property="form1" />
<p><i><%=intl._t("To change other client options, edit the file")%>
 <%=net.i2p.router.startup.ClientAppConfig.configFile(net.i2p.I2PAppContext.getGlobalContext()).getAbsolutePath()%>.
 <%=intl._t("All changes require restart to take effect.")%></i>
 </p><hr><div class="formaction">
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<% if (clientshelper.isClientChangeEnabled() && request.getParameter("edit") == null) { %>
 <input type="submit" name="edit" class="add" value="<%=intl._t("Add Client")%>" />
<% } %>
 <input type="submit" class="accept" name="action" value="<%=intl._t("Save Client Configuration")%>" />
</div></form></div>
</div><hr>
<p><a href="configi2cp"><%=intl._t("Advanced Client Interface Configuration")%></a></p>
<p><a href="configwebapps"><%=intl._t("WebApp Configuration")%></a></p>
<p><a href="configplugins"><%=intl._t("Plugin Configuration")%></a></p>
</div></body></html>
