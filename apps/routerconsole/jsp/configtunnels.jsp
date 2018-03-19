<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config tunnels")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigTunnelsHelper" id="tunnelshelper" scope="request" />
<jsp:setProperty name="tunnelshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._t("I2P Tunnel Configuration")%></h1>
<div class="main" id="config_tunnels">
 <%@include file="confignav.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigTunnelsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <p id="tunnelconfig" class="infowarn">
 <%=intl._t("The default settings work for most people.")%> 
 <%=intl._t("There is a fundamental tradeoff between anonymity and performance.")%>
 <%=intl._t("Tunnels longer than 3 hops (for example 2 hops + 0-2 hops, 3 hops + 0-1 hops, 3 hops + 0-2 hops), or a high quantity + backup quantity, may severely reduce performance or reliability.")%>
 <%=intl._t("High CPU and/or high outbound bandwidth usage may result.")%>
 <%=intl._t("Change these settings with care, and adjust them if you have problems.")%>
 </p>
 <p class="infohelp">
 <%=intl._t("Exploratory tunnel setting changes are stored in the router.config file.")%>
 <%=intl._t("Client tunnel changes are temporary and are not saved.")%>
<%
    net.i2p.util.PortMapper pm = net.i2p.I2PAppContext.getGlobalContext().portMapper();
    if (pm.isRegistered(net.i2p.util.PortMapper.SVC_I2PTUNNEL)) {
%>
 <%=intl._t("To make permanent client tunnel changes see the")%>&nbsp;<a href="/i2ptunnelmgr"><%=intl._t("i2ptunnel page")%></a>.
<%  }  %>
 </p>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
 <jsp:getProperty name="tunnelshelper" property="form" />
 <hr><div class="formaction" id="tunnelconfigsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</div></form></div></body></html>
