<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Certificates")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %><h1><%=intl._t("Certificates")%></h1>
<div class="main" id="certs">
<jsp:useBean class="net.i2p.router.web.helpers.CertHelper" id="certhelper" scope="request" />
<jsp:setProperty name="certhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% certhelper.storeWriter(out); %>
<jsp:getProperty name="certhelper" property="summary" />
</div></body></html>
