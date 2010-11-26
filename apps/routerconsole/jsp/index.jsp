<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
</head><body>
<%
if (System.getProperty("router.consoleNonce") == null) {
    System.setProperty("router.consoleNonce", new java.util.Random().nextLong() + "");
}
%>

<%@include file="summary.jsi" %><h1><%=intl._("I2P Router Console")%></h1>
<div class="news" id="news">
 <jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
 <jsp:setProperty name="newshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml"); %>
 <jsp:setProperty name="newshelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:getProperty name="newshelper" property="content" />

 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <hr><i><jsp:getProperty name="updatehelper" property="newsStatus" /></i><br>
</div><div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "docs/readme.html"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="lang" value="<%=request.getParameter("lang")%>" />
 <jsp:setProperty name="contenthelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="contenthelper" property="content" />
</div></body></html>
