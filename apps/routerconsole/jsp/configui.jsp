<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config UI")%>
<style type='text/css'>
input.default {
    width: 1px;
    height: 1px;
    visibility: hidden;
}
</style>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h1><%=uihelper._t("I2P UI Configuration")%></h1>
<div class="main" id="config_ui">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<h3 id="themeheading"><%=uihelper._t("Router Console Theme")%></h3>
 <form action="" method="POST">
 <input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
<div id ="themesettings">
<%
 String userAgent = request.getHeader("User-Agent");
 if (userAgent == null || userAgent.contains("Trident/6") || !userAgent.contains("MSIE")) {
%>
 <jsp:getProperty name="uihelper" property="settings" />
<% } else { %>
<%=uihelper._t("Theme selection disabled for Internet Explorer, sorry.")%>
<hr>
<%=uihelper._t("If you're not using IE, it's likely that your browser is pretending to be IE; please configure your browser (or proxy) to use a different User Agent string if you'd like to access the console themes.")%>
<% } %>
 <jsp:getProperty name="uihelper" property="forceMobileConsole" />
<hr><div class="formaction" id="themeui">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Apply")%>" >
</div></div></form>
<h3 id="langheading"><%=uihelper._t("Router Console Language")%></h3>
 <form action="" method="POST">
 <input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
<div id="langsettings">
<jsp:getProperty name="uihelper" property="langSettings" />
<p id="helptranslate"><%=uihelper._t("Please contribute to the router console translation project! Contact the developers in #i2p-dev on IRC to help.")%>
</p><hr><div class="formaction" id="langui">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Apply")%>" >
</div></div></form>

<h3 id="passwordheading"><%=uihelper._t("Router Console Password")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="uihelper" property="passwordForm" />
 <div class="formaction" id="consolepass">
  <input type="submit" name="action" class="default" value="<%=intl._t("Add user")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._t("Add user")%>" >
 </div>
</form>
</div></body></html>
