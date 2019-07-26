<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>I2P Router Console - help</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1>I2P Router Help &amp; Support</h1>
<div class="main" id="help">

<div class="confignav">
<span class="tab"><a href="#sidebarhelp">Sidebar</a></span>
<span class="tab"><a href="#reachabilityhelp">Reachability</a></span>
<span class="tab"><a href="#faq">FAQ</a></span>
<span class="tab"><a href="/viewlicense">Legal</a></span>
<span class="tab"><a href="/viewhistory">Change Log</a></span>
</div>

<div id="volunteer"><%@include file="help.jsi" %></div>
<div id="sidebarhelp"><%@include file="help-sidebar.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="faq"><%@include file="help-faq.jsi" %></div>
</div></body></html>
