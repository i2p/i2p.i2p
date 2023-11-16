<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
%>
<jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
<jsp:setProperty name="newshelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
 <jsp:setProperty name="newshelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>" />

<div class="routersummaryouter">
 <div class="routersummary">
  <div>
   <a href="/console"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/i2plogo.png" alt="<%=intl._t("I2P Router Console")%>" title="<%=intl._t("I2P Router Console")%>"></a>
  </div>
<%
    if (!intl.allowIFrame(request.getHeader("User-Agent"))) {
%>
  <a href="/summaryframe"><%=intl._t("Sidebar")%></a>
<%
    }
%>
  <div id="xhr">
<!-- for non-script -->
<%@include file="xhr1.jsi" %>
  </div>
 </div>
</div>

<h1><%=intl._t("I2P Router Console")%></h1>

<%
   if (newshelper.shouldShowNews()) {
%>
<div class="news" id="news">
 <jsp:getProperty name="newshelper" property="content" />
 <hr>
 <jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div>
<%
   }  // shouldShowNews()
%>

<div class="main" id="home">
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request" />
<jsp:setProperty name="homehelper" property="contextId" value="<%=i2pcontextId%>" />
<% if (homehelper.shouldShowWelcome()) { %>
<div class="welcome" >
  <div class="langbox" title="<%=intl._t("Configure Language")%>">
    <a href="/configui#langheading"><img src="/themes/console/images/info/control.png" alt="<%=intl._t("Configure Language")%>"></a>
  </div>
  <h2><%=intl._t("Welcome to I2P")%></h2>
</div>
<% }  // shouldShowWelcome %>

<div id="homepanel">
<%
   if (homehelper.shouldShowSearch()) {
%>
  <div class="search">
    <form action="/search.jsp" target="_blank" method="POST">
      <table class="search"><tr><td align="right">
        <input size="40" type="text" class="search" name="query" required/>
      </td><td align="left">
        <button type="submit" value="search" class="search"><%=intl._t("Search")%></button>
      </td><td align="left">
        <jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request" />
        <jsp:setProperty name="searchhelper" property="contextId" value="<%=i2pcontextId%>" />
        <jsp:getProperty name="searchhelper" property="selector" />
      </td></tr></table>
    </form>
  </div>
<%
   }  // shouldShowSearch()
%>
  <jsp:getProperty name="homehelper" property="services" />

  <jsp:getProperty name="homehelper" property="plugins" />

  <jsp:getProperty name="homehelper" property="favorites" /><br>

  <jsp:getProperty name="homehelper" property="config" /><br>

  <jsp:getProperty name="homehelper" property="monitoring" /><br>

</div>
</body></html>
