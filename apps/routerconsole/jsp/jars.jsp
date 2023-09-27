<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Jar File Dump")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %><h1>Jar File Dump</h1>
<div class="main" id="jardump">
<jsp:useBean class="net.i2p.router.web.helpers.FileDumpHelper" id="dumpHelper" scope="request" />
<jsp:setProperty name="dumpHelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:getProperty name="dumpHelper" property="fileSummary" />
</div></body></html>
