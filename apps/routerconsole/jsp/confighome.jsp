<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config home")%>
<style type='text/css'>
input.default {
    width: 1px;
    height: 1px;
    visibility: hidden;
}
</style>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Home Page Configuration")%></h1>
<div class="main" id="config_homepage">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigHomeHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request" />
<jsp:setProperty name="homehelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<h3 class="tabletitle"><%=intl._t("Default Home Page")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="0">
<table id="oldhome" class="configtable">
 <tr>
  <td>
 <label><input type="checkbox" name="oldHome" <jsp:getProperty name="homehelper" property="configHome" /> >
 <%=intl._t("Use old home page")%></label>
  </td>
  <td class="optionsave">
 <input type="submit" name="action" class="accept" value="<%=intl._t("Save")%>" >
  </td>
 </tr>
</table>
</form>

<%
   if (homehelper.shouldShowSearch()) {
%>
<h3 class="tabletitle"><%=intl._t("Search Engines")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="3">
 <jsp:getProperty name="homehelper" property="configSearch" />
 <div class="formaction" id="homesearch">
  <input type="submit" name="action" class="default" value="<%=intl._t("Add item")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
  <input type="submit" name="action" class="reload" value="<%=intl._t("Restore defaults")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._t("Add item")%>" >
 </div>
</form>
<%
   }  // shouldShowSearch()
%>
<h3 class="tabletitle"><%=intl._t("Applications and Configuration")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="2">
 <jsp:getProperty name="homehelper" property="configServices" />
 <div class="formaction" id="homeapps">
  <input type="submit" name="action" class="default" value="<%=intl._t("Add item")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
  <input type="submit" name="action" class="reload" value="<%=intl._t("Restore defaults")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._t("Add item")%>" >
 </div>
</form>

<h3 class="tabletitle"><%=intl._t("Hidden Services of Interest")%></h3>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="group" value="1">
 <jsp:getProperty name="homehelper" property="configFavorites" />
 <div class="formaction" id="homesites">
  <input type="submit" name="action" class="default" value="<%=intl._t("Add item")%>" >
  <input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
  <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
  <input type="submit" name="action" class="reload" value="<%=intl._t("Restore defaults")%>" >
  <input type="submit" name="action" class="add" value="<%=intl._t("Add item")%>" >
 </div>
</form>
</div></body></html>
