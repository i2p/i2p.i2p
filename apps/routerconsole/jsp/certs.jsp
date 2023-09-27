<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Certificates")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %><h1><%=intl._t("Certificates")%></h1>
<div class="main" id="certs">
<jsp:useBean class="net.i2p.router.web.helpers.CertHelper" id="certhelper" scope="request" />
<jsp:setProperty name="certhelper" property="contextId" value="<%=i2pcontextId%>" />
<% certhelper.storeWriter(out); %>
<jsp:getProperty name="certhelper" property="summary" />
</div></body></html>
