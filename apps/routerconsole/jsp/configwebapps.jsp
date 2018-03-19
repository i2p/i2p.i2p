<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config webapps")%>
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
<h1><%=intl._t("WebApp Configuration")%></h1>
<div class="main" id="config_webapps">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
<h3 id="webappconfig"><a name="webapp"></a><%=intl._t("WebApp Configuration")%></h3><p>
<p class="infohelp" id="webappconfigtext">
 <%=intl._t("The Java web applications listed below are started by the webConsole client and run in the same JVM as the router. They are usually web applications accessible through the router console. They may be complete applications (e.g. i2psnark), front-ends to another client or application which must be separately enabled (e.g. susidns, i2ptunnel), or have no web interface at all (e.g. addressbook).")%>&nbsp;
 <%=intl._t("A web app may also be disabled by removing the .war file from the webapps directory; however the .war file and web app will reappear when you update your router to a newer version, so disabling the web app here is the preferred method.")%>
 </p><div class="wideload">
<form action="configwebapps" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="clientshelper" property="form2" />
 <div class="formaction" id="webappconfigactions">
 <input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
 <input type="submit" name="action" class="accept" value="<%=intl._t("Save WebApp Configuration")%>" />
</div></form></div>
</div></div></body></html>
