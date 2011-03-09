<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config service")%>
</head><body>

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Service Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigServiceHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <h3><%=intl._("Shutdown the router")%></h3>
 <p><%=intl._("Graceful shutdown lets the router satisfy the agreements it has already made before shutting down, but may take a few minutes.")%> 
    <%=intl._("If you need to kill the router immediately, that option is available as well.")%></p>
  <hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Shutdown gracefully")%>" >
 <input type="submit" name="action" value="<%=intl._("Shutdown immediately")%>" >
 <input type="submit" name="action" value="<%=intl._("Cancel graceful shutdown")%>" >
 </div>
 <% if (System.getProperty("wrapper.version") != null) { %>
 <p><%=intl._("If you want the router to restart itself after shutting down, you can choose one of the following.")%> 
    <%=intl._("This is useful in some situations - for example, if you changed some settings that client applications only read at startup, such as the routerconsole password or the interface it listens on.")%> 
    <%=intl._("A graceful restart will take a few minutes (but your peers will appreciate your patience), while a hard restart does so immediately.")%> 
    <%=intl._("After tearing down the router, it will wait 1 minute before starting back up again.")%></p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Graceful restart")%>" >
 <input type="submit" name="action" value="<%=intl._("Hard restart")%>" >
 <% } %></div>

 <% if ( (System.getProperty("os.name") != null) && (System.getProperty("os.name").startsWith("Win")) ) { %>
 <h3><%=intl._("Systray integration")%></h3>
 <p><%=intl._("On the windows platform, there is a small application to sit in the system tray, allowing you to view the router's status")%> 
    <%=intl._("(later on, I2P client applications will be able to integrate their own functionality into the system tray as well).")%> 
    <%=intl._("If you are on windows, you can either enable or disable that icon here.")%></p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Show systray icon")%>" >
 <input type="submit" name="action" value="<%=intl._("Hide systray icon")%>" >
 </div>
 <h3><%=intl._("Run on startup")%></h3>
 <p><%=intl._("You can control whether I2P is run on startup or not by selecting one of the following options - I2P will install (or remove) a service accordingly.")%> 
    <%=intl._("If you prefer the command line, you can also run the ")%> <code>install_i2p_service_winnt.bat</code> (<%=intl._("or")%>
 <code>uninstall_i2p_service_winnt.bat</code>).</p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Run I2P on startup")%>" >
<input type="submit" name="action" value="<%=intl._("Don't run I2P on startup")%>" ></div>
 <p><b><%=intl._("Note")%>:</b> <%=intl._("If you are running I2P as service right now, removing it will shut down your router immediately.")%> 
    <%=intl._("You may want to consider shutting down gracefully, as above, then running uninstall_i2p_service_winnt.bat.")%></p>
 <% } %>

 <h3><%=intl._("Debugging")%></h3>
 <p><a href="/jobs"><%=intl._("View the job queue")%></a>
 <% if (System.getProperty("wrapper.version") != null) { %>
 <p><%=intl._("At times, it may be helpful to debug I2P by getting a thread dump. To do so, please select the following option and review the thread dumped to <a href=\"logs.jsp#servicelogs\">wrapper.log</a>.")%></p>
  <hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("Dump threads")%>" >
 </div>
<% } %>

 <h3><%=intl._("Launch browser on router startup?")%></h3>
 <p><%=intl._("I2P's main configuration interface is this web console, so for your convenience I2P can launch a web browser on startup pointing at")%>
 <a href="http://127.0.0.1:7657/">http://127.0.0.1:7657/</a> .</p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="<%=intl._("View console on startup")%>" >
 <input type="submit" name="action" value="<%=intl._("Do not view console on startup")%>" >
</div></form></div></div></body></html>
