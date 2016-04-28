<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    response.setStatus(404, "Not Found");
%>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("WebApp Not Found")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._t("Web Application Not Running")%></h1>
<div class="sorry" id="warning">
<%=intl._t("The requested web application is not running.")%>
<%=intl._t("Please visit the {0}config clients page{1} to start it.", "<a href=\"/configclients.jsp#webapp\" target=\"_top\">", "</a>")%>
</div></body></html>
