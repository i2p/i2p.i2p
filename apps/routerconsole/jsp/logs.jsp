<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - logs</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <h4>Version:</h4><a name="version"> </a>
 Please include this information in bug reports.
 <p>
I2P <jsp:getProperty name="helper" property="version" /><br />
<%=System.getProperty("java.vendor")%> <%=System.getProperty("java.version")%><br />
<%=System.getProperty("os.name")%> <%=System.getProperty("os.arch")%> <%=System.getProperty("os.version")%><br />
CPU <%=net.i2p.util.NativeBigInteger.cpuModel()%> (<%=net.i2p.util.NativeBigInteger.cpuType()%>)<br />
jbigi <%=net.i2p.util.NativeBigInteger.loadStatus()%><br />
 </p>
 <hr />
 <jsp:useBean class="net.i2p.router.web.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <h4>Critical logs:</h4><a name="criticallogs"> </a>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
 <hr />
 <h4>Router logs:</h4>
 <jsp:getProperty name="logsHelper" property="logs" />
 <hr />
 <h4>Service (Wrapper) logs:</h4><a name="servicelogs"> </a>
 <jsp:getProperty name="logsHelper" property="serviceLogs" />
</div>

</body>
</html>
