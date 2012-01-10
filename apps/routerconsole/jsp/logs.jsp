<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("logs")%>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Router Logs")%></h1>
<div class="main" id="main">
<div class="joblog"><h3><%=intl._("I2P Version and Running Environment")%></h3><a name="version"> </a>
<p>
<% /* note to translators - both parameters are URLs */
%><%=intl._("Please report bugs on {0} or {1}.",
          "<a href=\"http://trac.i2p2.i2p/newticket\">trac.i2p2.i2p</a>",
          "<a href=\"http://trac.i2p2.de/newticket\">trac.i2p2.de</a>")%>
<%=intl._("You may use the username \"guest\" and password \"guest\" if you do not wish to register.")%>
<p><i><%=intl._("Please include this information in bug reports")%>:</i>
 <p>
<b>I2P version:</b> <%=net.i2p.router.RouterVersion.FULL_VERSION%><br>
<b>Java version:</b> <%=System.getProperty("java.vendor")%> <%=System.getProperty("java.version")%> (<%=System.getProperty("java.runtime.name")%> <%=System.getProperty("java.runtime.version")%>)<br>
<b>Wrapper version:</b> <%=System.getProperty("wrapper.version", "none")%><br>
 <jsp:useBean class="net.i2p.router.web.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<b>Server version:</b> <jsp:getProperty name="logsHelper" property="jettyVersion" /><br>
<b>Platform:</b> <%=System.getProperty("os.name")%> <%=System.getProperty("os.arch")%> <%=System.getProperty("os.version")%><br>
<b>Processor:</b> <%=net.i2p.util.NativeBigInteger.cpuModel()%> (<%=net.i2p.util.NativeBigInteger.cpuType()%>)<br>
<b>Jbigi:</b> <%=net.i2p.util.NativeBigInteger.loadStatus()%><br>
<b>Encoding:</b> <%=System.getProperty("file.encoding")%><br>
<b>Charset:</b> <%=java.nio.charset.Charset.defaultCharset().name()%></p>
<p><%=intl._("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%></p>
<h3><%=intl._("Critical Logs")%></h3><a name="criticallogs"> </a>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
<h3><%=intl._("Router Logs")%> (<a href="configlogging"><%=intl._("configure")%></a>)</h3>
 <jsp:getProperty name="logsHelper" property="logs" />
<h3><%=intl._("Service (Wrapper) Logs")%></h3><a name="servicelogs"> </a>
 <jsp:getProperty name="logsHelper" property="serviceLogs" />
</div><hr></div></body></html>
