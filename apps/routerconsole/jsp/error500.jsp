<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    // Let's make this easy...
    final Integer ERROR_CODE = (Integer) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_STATUS_CODE);
    final String ERROR_URI = (String) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_REQUEST_URI);
    final String ERROR_MESSAGE = (String) request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_MESSAGE);
    final Class ERROR_CLASS = (Class)request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_EXCEPTION_TYPE);
    final Throwable ERROR_THROWABLE = (Throwable)request.getAttribute(org.mortbay.jetty.servlet.ServletHandler.__J_S_ERROR_EXCEPTION);
    if (ERROR_CODE != null && ERROR_MESSAGE != null) {
        // this is deprecated but we don't want sendError()
        response.setStatus(ERROR_CODE.intValue(), ERROR_MESSAGE);
    }
%>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Internal Error")%>
</head><body>
<div class="routersummaryouter">
<div class="routersummary">
<a href="/" title="<%=intl._("Router Console")%>"><img src="/themes/console/images/i2plogo.png" alt="<%=intl._("I2P Router Console")%>" border="0"></a><hr>
<a href="/config"><%=intl._("Configuration")%></a> <a href="/help"><%=intl._("Help")%></a>
</div></div>
<h1><%=ERROR_CODE%> <%=ERROR_MESSAGE%></h1>
<div class="sorry" id="warning">
<%=intl._("Sorry! There has been an internal error.")%>
<hr>
<p>
<% /* note to translators - both parameters are URLs */
%><%=intl._("Please report bugs on {0} or {1}.",
          "<a href=\"http://trac.i2p2.i2p/newticket\">trac.i2p2.i2p</a>",
          "<a href=\"http://trac.i2p2.de/newticket\">trac.i2p2.de</a>")%>
<%=intl._("You may use the username \"guest\" and password \"guest\" if you do not wish to register.")%>
<p><%=intl._("Please include this information in bug reports")%>:
</p></div><div class="sorry" id="warning2">
<h3><%=intl._("Error Details")%></h3>
<p>
<%=intl._("Error {0}", ERROR_CODE)%>: <%=ERROR_URI%> <%=ERROR_MESSAGE%>
</p><p>
<%
    if (ERROR_THROWABLE != null) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(2048);
        java.io.PrintStream ps = new java.io.PrintStream(baos);
        ERROR_THROWABLE.printStackTrace(ps);
        ps.close();
        String trace = baos.toString();
        trace = trace.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        trace = trace.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
        out.print(trace);
    }
%>
</p>
<h3><%=intl._("I2P Version and Running Environment")%></h3>
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
</div></body></html>
