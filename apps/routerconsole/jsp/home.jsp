<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
%>
<jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
<jsp:setProperty name="newshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
 <jsp:setProperty name="newshelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<div class="routersummaryouter">
 <div class="routersummary">
  <div style="height: 36px;">
   <a href="/console"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/i2plogo.png" alt="<%=intl._t("I2P Router Console")%>" title="<%=intl._t("I2P Router Console")%>"></a>
  </div>
<%
    if (!intl.allowIFrame(request.getHeader("User-Agent"))) {
%>
  <a href="/summaryframe"><%=intl._t("Summary Bar")%></a>
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
<jsp:setProperty name="homehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% if (homehelper.shouldShowWelcome()) { %>
<div class="welcome" title="<%=intl._t("Click a flag to select a language. Click 'Configure UI' below to change it later.")%>">
  <div class="langbox" id="langbox">
    <a href="/home?lang=en&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=us" title="English" alt="English"></a>
    <a href="/home?lang=ar&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=lang_ar" title="Arabic عربية" alt="Arabic عربية"></a>
    <a href="/home?lang=cs&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=cz" title="Čeština" alt="Čeština"></a>
    <a href="/home?lang=zh&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=cn" title="Chinese 中文" alt="Chinese 中文"></a>
    <a href="/home?lang=zh_TW&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=tw" title="Chinese 中文 (Taiwan)" alt="Chinese 中文 (Taiwan)"></a>
    <a href="/home?lang=da&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=dk" title="Dansk" alt="Dansk"></a>
    <a href="/home?lang=de&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=de" title="Deutsch" alt="Deutsch"></a>
    <a href="/home?lang=et&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ee" title="Eesti" alt="Eesti"></a>
    <a href="/home?lang=es&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=es" title="Español" alt="Español"></a>
    <a href="/home?lang=fr&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=fr" title="Français" alt="Français"></a>
    <a href="/home?lang=gl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=lang_gl" title="Galician" alt="Galician"></a>
    <a href="/home?lang=el&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=gr" title="Greek ελληνικά" alt="Greek ελληνικά"></a>
    <a href="/home?lang=in&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=id" title="bahasa Indonesia" alt="bahasa Indonesia"></a>
    <a href="/home?lang=it&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=it" title="Italiano" alt="Italiano"></a>
    <a href="/home?lang=ja&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=jp" title="Japanese 日本語" alt="Japanese 日本語"></a>
    <a href="/home?lang=ko&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=kr" title="Korean 한국어" alt="Korean 한국어"></a><br>
    <a href="/home?lang=mg&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=mg" title="Malagasy" alt="Malagasy"></a>
    <a href="/home?lang=hu&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=hu" title="Magyar" alt="Magyar"></a>
    <a href="/home?lang=nb&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=no" title="Norsk (bokmål)" alt="Norsk (bokmål)"></a>
    <a href="/home?lang=nl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=nl" title="Nederlands" alt="Nederlands"></a>
    <a href="/home?lang=pl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=pl" title="Polski" alt="Polski"></a>
    <a href="/home?lang=pt&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=pt" title="Português" alt="Português"></a>
    <a href="/home?lang=pt_BR&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=br" title="Português brasileiro" alt="Português brasileiro"></a>
    <a href="/home?lang=ro&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ro" title="Română" alt="Română"></a>
    <a href="/home?lang=ru&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ru" title="Russian Русский" alt="Russian Русский"></a>
    <a href="/home?lang=sk&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=sk" title="Slovenčina" alt="Slovenčina"></a>
    <a href="/home?lang=fi&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=fi" title="Suomi" alt="Suomi"></a>
    <a href="/home?lang=sv&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=se" title="Svenska" alt="Svenska"></a>
    <a href="/home?lang=tr&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=tr" title="Türkçe" alt="Türkçe"></a>
    <a href="/home?lang=uk&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ua" title="Ukrainian Українська" alt="Ukrainian Українська"></a>
    <a href="/home?lang=vi&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=vn" title="Vietnamese Tiếng Việt" alt="Vietnamese Tiếng Việt"></a>
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
        <input size="40" type="text" class="search" name="query" />
      </td><td align="left">
        <button type="submit" value="search" class="search"><%=intl._t("Search")%></button>
      </td><td align="left">
        <jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request" />
        <jsp:setProperty name="searchhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
        <jsp:getProperty name="searchhelper" property="selector" />
      </td></tr></table>
    </form>
  </div>
<%
   }  // shouldShowSearch()
%>
  <div class="ag2">
    <h4 class="app"><%=intl._t("Applications and Configuration")%></h4>
    <jsp:getProperty name="homehelper" property="services" /><br>
  </div>
  <div class="ag2">
    <h4 class="app2"><%=intl._t("Hidden Services of Interest")%></h4>
    <jsp:getProperty name="homehelper" property="favorites" /><br>
    <div class="clearer">&nbsp;</div>
  </div>
</div>
</div>
</body></html>
