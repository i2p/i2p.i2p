<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp", "xhr", 15000); }
  function initAjax() { setTimeout(requestAjax1, 15000);  }
</script>
</head><body onload="initAjax()">
<%
    String consoleNonce = System.getProperty("router.consoleNonce");
    if (consoleNonce == null) {
        consoleNonce = Long.toString(new java.util.Random().nextLong());
        System.setProperty("router.consoleNonce", consoleNonce);
    }
%>
<div class="routersummaryouter" id="appsummary">
 <div class="routersummary">
  <div style="height: 36px;">
   <!-- fixme theme, translation -->
   <a href="/console"><img src="/themes/console/light/images/i2plogo.png" alt="I2P Router Console" title="I2P Router Console"></a>
  </div>
  <hr>
  <div id="xhr">
<!-- for non-script -->
<%@include file="xhr1.jsi" %>
  </div>
 </div>
</div>

<div class="welcome">
  <div class="langbox" id="langbox">
    <a href="/home?lang=en&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=us" title="English" alt="English"></a> 
    <a href="/home?lang=ar&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=lang_ar" title="عربية" alt="عربية"></a>
    <a href="/home?lang=zh&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=cn" title="中文" alt="中文"></a> 
    <a href="/home?lang=cs&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=cz" title="Czech" alt="Czech"></a> 
    <a href="/home?lang=da&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=dk" title="Danish" alt="Danish"></a> 
    <a href="/home?lang=de&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=de" title="Deutsch" alt="Deutsch"></a> 
    <a href="/home?lang=ee&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ee" title="Eesti" alt="Eesti"></a> 
    <a href="/home?lang=es&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=es" title="Español" alt="Español"></a> 
    <a href="/home?lang=fi&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=fi" title="Suomi" alt="Suomi"></a><br> 
    <a href="/home?lang=fr&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=fr" title="Français" alt="Français"></a>
    <a href="/home?lang=it&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=it" title="Italiano" alt="Italiano"></a> 
    <a href="/home?lang=nl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=nl" title="Nederlands" alt="Nederlands"></a> 
    <a href="/home?lang=pl&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=pl" title="Polski" alt="Polski"></a> 
    <a href="/home?lang=pt&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=pt" title="Português" alt="Português"></a> 
    <a href="/home?lang=ru&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ru" title="Русский" alt="Русский"></a> 
    <a href="/home?lang=sv&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=se" title="Svenska" alt="Svenska"></a>
    <a href="/home?lang=uk&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=ua" title="Ukrainian" alt="Ukrainian"></a>
    <a href="/home?lang=vi&amp;consoleNonce=<%=consoleNonce%>"><img height="11" width="16" style="padding: 0 2px;" src="/flags.jsp?c=vn" title="Tiếng Việt" alt="Tiếng Việt"></a>
  </div>
  <h2 class="app"><%=intl._("Welcome to I2P")%></h2>
</div>

<div class="news" id="news">
 <jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
 <jsp:setProperty name="newshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
   if (newshelper.shouldShowNews()) {
       java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
 <h3 class="app"><%=intl._("Latest I2P News")%></h3>
 <jsp:setProperty name="newshelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:getProperty name="newshelper" property="content" />
 <hr>
<%
   }  // shouldShowNews()
%>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div>

<div class="home" id="home">
  <div class="search">
    <form action="/search.jsp" method="POST">
      <table class="search"><tr><td align="right">
        <input size="50" type="text" class="search" name="query" />
      </td><td align="left">
        <button type="submit" value="search" class="search"><%=intl._("Search I2P")%></button>
      </td></tr><tr><td align="right">
        <b>Using search engine:</b>
      </td><td align="left">
        <jsp:useBean class="net.i2p.router.web.SearchHelper" id="searchhelper" scope="request" />
        <jsp:setProperty name="searchhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
        <jsp:getProperty name="searchhelper" property="selector" />
      </td></tr></table>
    </form>
  </div>
  <jsp:useBean class="net.i2p.router.web.HomeHelper" id="homehelper" scope="request" />
  <jsp:setProperty name="homehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
  <div class="ag2">
    <h4 class="app"><%=intl._("Eepsites of Interest")%></h4>
    <jsp:getProperty name="homehelper" property="favorites" /><br>
  </div>
  <div class="ag2">
    <h4 class="app"><%=intl._("Local Services")%></h4>
    <jsp:getProperty name="homehelper" property="services" /><br>
  </div>
</div>
</body></html>
