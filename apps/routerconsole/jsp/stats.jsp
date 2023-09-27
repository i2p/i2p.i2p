<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("statistics")%>
<%@include file="summaryajax.jsi" %>
<script src="/js/stats.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head><body>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.OldConsoleHelper" id="oldhelper" scope="request" />
<jsp:setProperty name="oldhelper" property="contextId" value="<%=i2pcontextId%>" />
<% oldhelper.storeWriter(out); %>
<jsp:setProperty name="oldhelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <h1><%=intl._t("I2P Router Statistics")%></h1>
<div class="main" id="stats">
 <jsp:getProperty name="oldhelper" property="stats" />
</div></body></html>
