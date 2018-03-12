<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    // Let's make this easy...
    // These are defined in Jetty 7 org.eclipse.jetty.server.Dispatcher,
    // and in Servlet 3.0 (Jetty 8) javax.servlet.RequestDispatcher,
    // just use the actual strings here to make it compatible with either
    Integer ERROR_CODE = (Integer) request.getAttribute("javax.servlet.error.status_code");
    String ERROR_URI = (String) request.getAttribute("javax.servlet.error.request_uri");
    String ERROR_MESSAGE = (String) request.getAttribute("javax.servlet.error.message");
    final Throwable ERROR_THROWABLE = (Throwable) request.getAttribute("javax.servlet.error.exception");
    if (ERROR_CODE != null)
        response.setStatus(ERROR_CODE.intValue());
    else
        ERROR_CODE = Integer.valueOf(0);
    if (ERROR_URI != null)
        ERROR_URI = net.i2p.data.DataHelper.escapeHTML(ERROR_URI);
    else
        ERROR_URI = "";
    if (ERROR_MESSAGE != null)
        ERROR_MESSAGE = net.i2p.data.DataHelper.escapeHTML(ERROR_MESSAGE);
    else
        ERROR_MESSAGE = "";
%>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Internal Error")%>
</head><body>
<div class="routersummaryouter">
<div class="routersummary">
<a href="/" title="<%=intl._t("Router Console")%>"><img src="/themes/console/images/i2plogo.png" alt="<%=intl._t("I2P Router Console")%>" border="0"></a><hr>
<a href="/config"><%=intl._t("Configuration")%></a> <a href="/help"><%=intl._t("Help")%></a>
</div></div>
<h1><%=ERROR_CODE%> <%=ERROR_MESSAGE%></h1>
<div class="sorry" id="warning">
<%=intl._t("Sorry! There has been an internal error.")%>
<hr>
<p>
<% /* note to translators - both parameters are URLs */
%><%=intl._t("Please report bugs on {0} or {1}.",
          "<a href=\"http://trac.i2p2.i2p/newticket\">trac.i2p2.i2p</a>",
          "<a href=\"https://trac.i2p2.de/newticket\">trac.i2p2.de</a>")%>
<p><%=intl._t("Please include this information in bug reports")%>:
</p></div><div class="sorry" id="warning2">
<h3><%=intl._t("Error Details")%></h3>
<p>
<%=intl._t("Error {0}", ERROR_CODE)%>: <%=ERROR_URI%> <%=ERROR_MESSAGE%>
</p><p>
<%
    if (ERROR_THROWABLE != null) {
        java.io.StringWriter sw = new java.io.StringWriter(2048);
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        ERROR_THROWABLE.printStackTrace(pw);
        pw.flush();
        String trace = sw.toString();
        trace = trace.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        trace = trace.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
        out.print(trace);
    }
%>
</p>
<h3><%=intl._t("I2P Version and Running Environment")%></h3>
<p>
<b>I2P version:</b> <%=net.i2p.router.RouterVersion.FULL_VERSION%><br>
<b>Java version:</b> <%=System.getProperty("java.vendor")%> <%=System.getProperty("java.version")%> (<%=System.getProperty("java.runtime.name")%> <%=System.getProperty("java.runtime.version")%>)<br>
 <jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:getProperty name="logsHelper" property="unavailableCrypto" />
<b>Wrapper version:</b> <%=System.getProperty("wrapper.version", "none")%><br>
<b>Server version:</b> <jsp:getProperty name="logsHelper" property="jettyVersion" /><br>
<b>Servlet version:</b> <%=getServletInfo()%><br>
<b>Platform:</b> <%=System.getProperty("os.name")%> <%=System.getProperty("os.arch")%> <%=System.getProperty("os.version")%><br>
<b>Processor:</b> <%=net.i2p.util.NativeBigInteger.cpuModel()%> (<%=net.i2p.util.NativeBigInteger.cpuType()%>)<br>
<b>Jbigi:</b> <%=net.i2p.util.NativeBigInteger.loadStatus()%><br>
<b>Encoding:</b> <%=System.getProperty("file.encoding")%><br>
<b>Charset:</b> <%=java.nio.charset.Charset.defaultCharset().name()%></p>
<p><%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%></p>
</div></body></html>
