<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
</head><body>
<%
    String consoleNonce = System.getProperty("router.consoleNonce");
    if (consoleNonce == null) {
        consoleNonce = Long.toString(new java.util.Random().nextLong());
        System.setProperty("router.consoleNonce", consoleNonce);
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
 <div class="welcome">
  <div class="langbox">
    <a href="/?lang=en&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=us" title="English" alt="English"></a> 
    <a href="/?lang=ar&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=lang_ar" title="عربية" alt="عربية"></a>
    <a href="/?lang=zh&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=cn" title="中文" alt="中文"></a> 
    <a href="/?lang=da&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=dk" title="Danish" alt="Danish"></a> 
    <a href="/?lang=de&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=de" title="Deutsch" alt="Deutsch"></a> 
    <a href="/?lang=es&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=es" title="Español" alt="Español"></a> 
    <a href="/?lang=fi&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=fi" title="Suomi" alt="Suomi"></a> 
    <a href="/?lang=fr&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=fr" title="Français" alt="Français"></a><br/>
    <a href="/?lang=it&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=it" title="Italiano" alt="Italiano"></a> 
    <a href="/?lang=nl&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=nl" title="Nederlands" alt="Nederlands"></a> 
    <a href="/?lang=pl&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=pl" title="Polski" alt="Polski"></a> 
    <a href="/?lang=pt&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=pt" title="Português" alt="Português"></a> 
    <a href="/?lang=ru&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=ru" title="Русский" alt="Русский"></a> 
    <a href="/?lang=sv&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=se" title="Svenska" alt="Svenska"></a>
    <a href="/?lang=uk&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=ua" title="Ukranian" alt="Ukranian"></a>
    <a href="/?lang=vi&amp;consoleNonce=<%=consoleNonce%>"><img src="/flags.jsp?c=vn" title="Tiếng Việt" alt="Tiếng Việt"></a>
  </div>
  <a name="top"></a>
  <h2><%=intl._("Welcome to I2P")%></h2>
 </div>
 <% fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "docs/readme.html"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="contenthelper" property="content" />
</div></body></html>
