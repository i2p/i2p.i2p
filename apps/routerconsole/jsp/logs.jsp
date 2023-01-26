<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("logs")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Router Logs")%></h1>
<div class="main" id="logs">

<table id="bugreports"><tbody>
  <tr><td class="infohelp">
<%=intl._t("Please include your I2P version and running environment information in bug reports")%>.
<%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%>
<% /* note to translators - both parameters are URLs */
%><%=intl._t("Please report bugs on {0} or {1}.",
          "<a href=\"http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues/new\">git.idk.i2p</a>",
          "<a href=\"https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues/new\">i2pgit.org</a>")%>
  </td></tr>
</tbody></table>

<h3 class="tabletitle" id="version"><%=intl._t("I2P Version and Running Environment")%></h3>
<table id="enviro"><tbody>
<tr><td colspan="2"><!-- fix for first row not being selected --></td></tr>
<tr><td><b>I2P version:</b></td><td><%=net.i2p.router.RouterVersion.FULL_VERSION%></td></tr>
<tr><td><b>API version:</b></td><td><%=net.i2p.CoreVersion.PUBLISHED_VERSION%></td></tr>
<tr><td><b>Java version:</b></td><td><%=System.getProperty("java.vendor")%> <%=System.getProperty("java.version")%> (<%=System.getProperty("java.runtime.name")%> <%=System.getProperty("java.runtime.version")%>)</td></tr>
 <jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:getProperty name="logsHelper" property="unavailableCrypto" />
<tr><td><b>Wrapper version:</b></td><td><%=System.getProperty("wrapper.version", "none")%></td></tr>
<tr><td><b>Server version:</b></td><td><jsp:getProperty name="logsHelper" property="jettyVersion" /></td></tr>
<tr><td><b>Servlet version:</b></td><td><%=getServletInfo()%> (<%=getServletConfig().getServletContext().getMajorVersion()%>.<%=getServletConfig().getServletContext().getMinorVersion()%>)</td></tr>
<tr><td><b>JSTL version:</b></td><td><jsp:getProperty name="logsHelper" property="jstlVersion" /></td></tr>
<tr><td><b>Platform:</b></td><td><%=System.getProperty("os.name")%> <%=System.getProperty("os.arch")%> <%=System.getProperty("os.version")%></td></tr>
<tr><td><b>Processor:</b></td><td>
<%
   boolean isX86 = net.i2p.util.SystemVersion.isX86();
   if (isX86) {
%> <%=net.i2p.util.NativeBigInteger.cpuModel()%>
<%
   }
%> (<%=net.i2p.util.NativeBigInteger.cpuType()%>)</td></tr>
<tr><td><b>JBigI status:</b></td><td><%=net.i2p.util.NativeBigInteger.loadStatus()%></td></tr>
<tr><td><b>GMP version:</b></td><td><%=net.i2p.util.NativeBigInteger.getLibGMPVersion()%></td></tr>
<tr><td><b>JBigI version:</b></td><td><%=net.i2p.util.NativeBigInteger.getJbigiVersion()%></td></tr>
<%
   if (isX86) {
%><tr><td><b>JCpuId version:</b></td><td><%=freenet.support.CPUInformation.CPUID.getJcpuidVersion()%></td></tr>
<%
   }
%><tr><td><b>Encoding:</b></td><td><%=System.getProperty("file.encoding")%></td></tr>
<tr><td><b>Charset:</b></td><td><%=java.nio.charset.Charset.defaultCharset().name()%></td></tr>
<tr><td><b>Service:</b></td><td><%=net.i2p.util.SystemVersion.isService()%></td></tr>
<%
   String rev = logsHelper.getRevision();
   if (rev.length() == 40) {
%><tr><td><b>Revision:</b></td><td><%=rev%></td></tr>
<%
   }
%><tr><td><b>Built:</b></td><td><jsp:getProperty name="logsHelper" property="buildDate" /></td></tr>
<tr><td><b>Built By:</b></td><td><jsp:getProperty name="logsHelper" property="builtBy" /></td></tr></tbody></table>

<h3 class="tabletitle"><%=intl._t("Critical Logs")%><%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
    String ct1 = request.getParameter("clear");
    String ct2 = request.getParameter("crit");
    String ct3 = request.getParameter("svc");
    String ct4 = request.getParameter("svct");
    String ct5 = request.getParameter("svcf");
    String ctn = request.getParameter("consoleNonce");
    if ((ct1 != null || ct2 != null || (ct3 != null && ct4 != null && ct5 != null)) && ctn != null) {
        int ict1 = -1, ict2 = -1;
        long ict3 = -1, ict4 = -1;
        try { ict1 = Integer.parseInt(ct1); } catch (NumberFormatException nfe) {}
        try { ict2 = Integer.parseInt(ct2); } catch (NumberFormatException nfe) {}
        try { ict3 = Long.parseLong(ct3); } catch (NumberFormatException nfe) {}
        try { ict4 = Long.parseLong(ct4); } catch (NumberFormatException nfe) {}
        logsHelper.clearThrough(ict1, ict2, ict3, ict4, ct5, ctn);
    }
    int last = logsHelper.getLastCriticalMessageNumber();
    if (last >= 0) {
%>&nbsp;<a class="delete" title="<%=intl._t("Clear logs")%>" href="logs?crit=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a><%
    }
%></h3>
<table id="criticallogs" class="logtable"><tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
</td></tr>
</tbody></table>

<h3 class="tabletitle"><%=intl._t("Router Logs")%><%
    // both links float right, so first one goes last
    last = logsHelper.getLastMessageNumber();
    if (last >= 0) {
%>&nbsp;<a class="delete" title="<%=intl._t("Clear logs")%>" href="logs?clear=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a><%
    }
%>&nbsp;<a class="configure" title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a>
</h3>
<table id="routerlogs" class="logtable"><tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="logs" />
</td></tr>
</tbody></table>

<h3 class="tabletitle"><%=intl._t("Event Logs")%></h3>
<table id="eventlogs" class="logtable"><tbody>
<tr><td>
 <!-- 90 days --><p><a href="events?from=7776000"><%=intl._t("View event logs")%></a></p>
</td></tr>
</tbody></table>

<h3 class="tabletitle" id="servicelogs"><%=intl._t("Service (Wrapper) Logs")%><%
    StringBuilder buf = new StringBuilder(24*1024);
    // timestamp, last line number, escaped filename
    Object[] vals = logsHelper.getServiceLogs(buf);
    String lts = vals[0].toString();
    long llast = ((Long) vals[1]).longValue();
    String filename = vals[2].toString();
    if (llast >= 0) {
%>&nbsp;<a class="delete" title="<%=intl._t("Clear logs")%>" href="logs?svc=<%=llast%>&amp;svct=<%=lts%>&amp;svcf=<%=filename%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a><%
    }
%></h3>
<table id="wrapperlogs" class="logtable"><tbody>
<tr><td>
<%
    out.append(buf);
%>
</td></tr>
</tbody></table>
</div></body></html>
