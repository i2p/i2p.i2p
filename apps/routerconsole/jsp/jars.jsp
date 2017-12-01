<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Jar File Dump")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %><h1>Jar File Dump</h1>
<div class="main" id="jardump">
<jsp:useBean class="net.i2p.router.web.helpers.FileDumpHelper" id="dumpHelper" scope="request" />
<jsp:setProperty name="dumpHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<jsp:getProperty name="dumpHelper" property="fileSummary" />
</div></body></html>
