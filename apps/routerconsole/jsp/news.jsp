<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("News")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._t("Latest News")%></h1>
<div class="main" id="main">
<jsp:useBean class="net.i2p.router.web.NewsFeedHelper" id="feedHelper" scope="request" />
<jsp:setProperty name="feedHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% feedHelper.setLimit(0); %>
<div class="fixme" id="fixme">
<jsp:getProperty name="feedHelper" property="entries" />
</div></div></body></html>
