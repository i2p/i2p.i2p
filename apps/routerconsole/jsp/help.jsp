<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("help")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Router Help and Support")%></h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="#sidebarhelp"><%=intl._t("Sidebar")%></a></span>
<span class="tab"><a href="#reachabilityhelp"><%=intl._t("Network")%></a></span>
<span class="tab"><a href="#faq"><%=intl._t("FAQ")%></a></span>
<span class="tab"><a href="/viewlicense"><%=intl._t("Licenses")%></a></span>
<span class="tab"><a href="/viewhistory"><%=intl._t("Change Log")%></a></span>
</div>
<div id="volunteer"><%@include file="help.jsi" %></div>
<div id="sidebarhelp"><%@include file="help-sidebar.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="faq"><%@include file="help-faq.jsi" %></div>
</div></body></html>
