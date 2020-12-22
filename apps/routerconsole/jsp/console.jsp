<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
%>
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Router Console")%></h1>
<div class="news" id="news">
<%
   if (newshelper.shouldShowNews()) {
%>
 <jsp:getProperty name="newshelper" property="content" />
 <hr>
<%
   }  // shouldShowNews()
%>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>" />
 <jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div><div class="main" id="console">
 <jsp:useBean class="net.i2p.router.web.helpers.ResourceHelper" id="contenthelper" scope="request" />
 <div class="welcome">
  <div class="langbox" title="<%=intl._t("Configure Language")%>">
    <a href="/configui#langheading"><img src="/themes/console/images/info/control.png" alt="<%=intl._t("Configure Language")%>"></a>
  </div>
  <a name="top"></a>
  <h2><%=intl._t("Welcome to I2P")%></h2>
 </div>
 <jsp:setProperty name="contenthelper" property="page" value="docs/readme.html" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="contextId" value="<%=i2pcontextId%>" />
 <jsp:getProperty name="contenthelper" property="resource" />
</div></body></html>
