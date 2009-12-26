<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("logs")%>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Router Logs")%></h1>
<div class="main" id="main">
 <div class="joblog"><h3><%=intl._("I2P Version & Running Environment")%></h3><a name="version"> </a>
 <i><%=intl._("Please include this information in bug reports")%>:</i>
 <p>
<b>I2P version:</b> <jsp:getProperty name="helper" property="version" /><br>
<b>Java version:</b> <%=System.getProperty("java.vendor")%> <%=System.getProperty("java.version")%><br>
<b>Platform:</b> <%=System.getProperty("os.name")%> <%=System.getProperty("os.arch")%> <%=System.getProperty("os.version")%><br>
<b>Processor:</b> <%=net.i2p.util.NativeBigInteger.cpuModel()%> (<%=net.i2p.util.NativeBigInteger.cpuType()%>)<br>
<b>Jbigi:</b> <%=net.i2p.util.NativeBigInteger.loadStatus()%><br>
<b>Encoding:</b> <%=System.getProperty("file.encoding")%></p>
 <jsp:useBean class="net.i2p.router.web.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<h3><%=intl._("Critical Logs")%></h3><a name="criticallogs"> </a>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
<h3><%=intl._("Router Logs")%> (<a href="configlogging.jsp"><%=intl._("configure")%></a>)</h3>
 <jsp:getProperty name="logsHelper" property="logs" />
<h3><%=intl._("Service (Wrapper) Logs")%></h3><a name="servicelogs"> </a>
 <jsp:getProperty name="logsHelper" property="serviceLogs" />
</div><hr></div></body></html>
