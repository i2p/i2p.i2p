<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("News")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Latest News")%></h1>
<div class="main" id="news">
<jsp:useBean class="net.i2p.router.web.NewsFeedHelper" id="feedHelper" scope="request" />
<jsp:setProperty name="feedHelper" property="contextId" value="<%=i2pcontextId%>" />
<% feedHelper.setLimit(0); %>
<div id="newspage">
<jsp:getProperty name="feedHelper" property="entries" />
</div></div></body></html>
