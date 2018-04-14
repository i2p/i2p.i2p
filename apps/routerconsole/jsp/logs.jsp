<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("logs")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Router Logs")%></h1>
<div class="main" id="logs">

<table id="bugreports"><tbody>
  <tr><td class="infohelp">
<%=intl._t("Please include your I2P version and running environment information in bug reports")%>.
<%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%>
<% /* note to translators - both parameters are URLs */
%><%=intl._t("Please report bugs on {0} or {1}.",
          "<a href=\"http://trac.i2p2.i2p/\">trac.i2p2.i2p</a>",
          "<a href=\"https://trac.i2p2.de/\">trac.i2p2.de</a>")%>
  </td></tr>
</tbody></table>

<h3 class="tabletitle"><%=intl._t("I2P Version and Running Environment")%></h3><a name="version"> </a>
<table id="enviro"><tbody>
<tr><td><b>I2P version:</b></td><td><%=net.i2p.router.RouterVersion.FULL_VERSION%></td></tr>
<tr><td><b>Java version:</b></td><td><%=System.getProperty("java.vendor")%> <%=System.getProperty("java.version")%> (<%=System.getProperty("java.runtime.name")%> <%=System.getProperty("java.runtime.version")%>)</td></tr>
 <jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:getProperty name="logsHelper" property="unavailableCrypto" />
<tr><td><b>Wrapper version:</b></td><td><%=System.getProperty("wrapper.version", "none")%></td></tr>
<tr><td><b>Server version:</b></td><td><jsp:getProperty name="logsHelper" property="jettyVersion" /></td></tr>
<tr><td><b>Servlet version:</b></td><td><%=getServletInfo()%></td></tr>
<tr><td><b>JSTL version:</b></td><td><jsp:getProperty name="logsHelper" property="jstlVersion" /></td></tr>
<tr><td><b>Platform:</b></td><td><%=System.getProperty("os.name")%> <%=System.getProperty("os.arch")%> <%=System.getProperty("os.version")%></td></tr>
<%
   boolean isX86 = net.i2p.util.SystemVersion.isX86();
   if (isX86) {
%><tr><td><b>Jcpuid version:</b></td><td><%=freenet.support.CPUInformation.CPUID.getJcpuidVersion()%></td></tr>
<%
   }
%><tr><td><b>Processor:</b></td><td>
<%
   if (isX86) {
%> <%=net.i2p.util.NativeBigInteger.cpuModel()%>
<%
   }
%> (<%=net.i2p.util.NativeBigInteger.cpuType()%>)</td></tr>
<tr><td><b>Jbigi:</b></td><td><%=net.i2p.util.NativeBigInteger.loadStatus()%></td></tr>
<tr><td><b>Jbigi version:</b></td><td><%=net.i2p.util.NativeBigInteger.getJbigiVersion()%></td></tr>
<tr><td><b>GMP version:</b></td><td><%=net.i2p.util.NativeBigInteger.getLibGMPVersion()%></td></tr>
<tr><td><b>Encoding:</b></td><td><%=System.getProperty("file.encoding")%></td></tr>
<tr><td><b>Charset:</b></td><td><%=java.nio.charset.Charset.defaultCharset().name()%></td></tr>
<tr><td><b>Built By:</b></td><td><jsp:getProperty name="logsHelper" property="builtBy" /></tbody></table>

<h3 class="tabletitle"><%=intl._t("Critical Logs")%></h3>
<table id="criticallogs" class="logtable"><tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
</td></tr>
</tbody></table>

<h3 class="tabletitle"><%=intl._t("Router Logs")%>&nbsp;<a title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a></h3>
<table id="routerlogs" class="logtable"><tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="logs" />
</td></tr>
</tbody></table>

<h3 class="tabletitle"><%=intl._t("Event Logs")%></h3>
<table id="eventlogs" class="logtable"><tbody>
<tr><td>
 <!-- 90 days --><p><a href="events?from=7776000000"><%=intl._t("View event logs")%></a></p>
</td></tr>
</tbody></table>

<h3 class="tabletitle" id="servicelogs"><%=intl._t("Service (Wrapper) Logs")%></h3>
<table id="wrapperlogs" class="logtable"><tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="serviceLogs" />
</td></tr>
</tbody></table>
</div></body></html>
