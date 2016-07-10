<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config service")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Service Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigServiceHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <h3><%=intl._t("Shutdown the router")%></h3>
 <p><%=intl._t("Graceful shutdown lets the router satisfy the agreements it has already made before shutting down, but may take a few minutes.")%> 
    <%=intl._t("If you need to kill the router immediately, that option is available as well.")%></p>
  <hr><div class="formaction">
 <input type="submit" class="stop" name="action" value="<%=intl._t("Shutdown gracefully")%>" >
 <input type="submit" class="stop" name="action" value="<%=intl._t("Shutdown immediately")%>" >
 <% if (formhandler.shouldShowCancelGraceful()) { %>
     <input type="submit" class="cancel" name="action" value="<%=intl._t("Cancel graceful shutdown")%>" >
 <% } %>
 </div>
 <% if (System.getProperty("wrapper.version") != null) { %>
 <p><%=intl._t("If you want the router to restart itself after shutting down, you can choose one of the following.")%> 
    <%=intl._t("This is useful in some situations - for example, if you changed some settings that client applications only read at startup, such as the routerconsole password or the interface it listens on.")%> 
    <%=intl._t("A graceful restart will take a few minutes (but your peers will appreciate your patience), while a hard restart does so immediately.")%> 
    <%=intl._t("After tearing down the router, it will wait 1 minute before starting back up again.")%></p>
 <hr><div class="formaction">
 <input type="submit" class="reload" name="action" value="<%=intl._t("Graceful restart")%>" >
 <input type="submit" class="reload" name="action" value="<%=intl._t("Hard restart")%>" >
 <% } %></div>

<% if (formhandler.shouldShowSystray()) { %>
 <h3><%=intl._t("Systray integration")%></h3>
 <p><%=intl._t("Control the system tray icon")%> 
 <hr><div class="formaction">
<% if (!formhandler.isSystrayEnabled()) { %>
 <input type="submit" name="action" class="accept" value="<%=intl._t("Show systray icon")%>" >
<% } else {%>
 <input type="submit" name="action" class="cancel" value="<%=intl._t("Hide systray icon")%>" >
<% } %>
 </div>
<%
   }  
   if ( (System.getProperty("os.name") != null) && (System.getProperty("os.name").startsWith("Win")) ) { %>
%>
 <h3><%=intl._t("Run on startup")%></h3>
 <p><%=intl._t("You can control whether I2P is run on startup or not by selecting one of the following options - I2P will install (or remove) a service accordingly.")%> 
    <%=intl._t("If you prefer the command line, you can also run the ")%> <code>install_i2p_service_winnt.bat</code> (<%=intl._t("or")%>
 <code>uninstall_i2p_service_winnt.bat</code>).</p>
 <hr><div class="formaction">
 <input type="submit" name="action" class="accept" value="<%=intl._t("Run I2P on startup")%>" >
<input type="submit" name="action" class="cancel" value="<%=intl._t("Don't run I2P on startup")%>" ></div>
 <p><b><%=intl._t("Note")%>:</b> <%=intl._t("If you are running I2P as service right now, removing it will shut down your router immediately.")%> 
    <%=intl._t("You may want to consider shutting down gracefully, as above, then running uninstall_i2p_service_winnt.bat.")%></p>
<% } %>

 <h3><%=intl._t("Debugging")%></h3>
 <p><a href="/jobs"><%=intl._t("View the job queue")%></a>
<% if (System.getProperty("wrapper.version") != null) { %>
 <p><%=intl._t("At times, it may be helpful to debug I2P by getting a thread dump. To do so, please select the following option and review the thread dumped to <a href=\"logs.jsp#servicelogs\">wrapper.log</a>.")%></p>
  <hr>
<% } %>
 <div class="formaction">
 <input type="submit" class="reload" name="action" value="<%=intl._t("Force GC")%>" >
<% if (System.getProperty("wrapper.version") != null) { %>
 <input type="submit" class="download" name="action" value="<%=intl._t("Dump threads")%>" >
<% } %>
 </div>

 <h3><%=intl._t("Launch browser on router startup?")%></h3>
 <p><%=intl._t("I2P's main configuration interface is this web console, so for your convenience I2P can launch a web browser on startup pointing at")%>
 <a href="http://127.0.0.1:7657/">http://127.0.0.1:7657/</a> .</p>
 <hr><div class="formaction">
 <input type="submit" class="check" name="action" value="<%=intl._t("View console on startup")%>" >
 <input type="submit" class="delete" name="action" value="<%=intl._t("Do not view console on startup")%>" >
</div></form></div></div></body></html>
