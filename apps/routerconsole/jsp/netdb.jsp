<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("network database")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Network Database")%></h1>
<div class="main" id="netdb">
 <jsp:useBean class="net.i2p.router.web.helpers.NetDbHelper" id="netdbHelper" scope="request" />
 <jsp:setProperty name="netdbHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    netdbHelper.storeWriter(out);
    if (allowIFrame)
        netdbHelper.allowGraphical();
%>
 <jsp:setProperty name="netdbHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:setProperty name="netdbHelper" property="router" value="<%=request.getParameter(\"r\")%>" />
 <jsp:setProperty name="netdbHelper" property="lease" value="<%=request.getParameter(\"l\")%>" />
 <jsp:setProperty name="netdbHelper" property="version" value="<%=request.getParameter(\"v\")%>" />
 <jsp:setProperty name="netdbHelper" property="country" value="<%=request.getParameter(\"c\")%>" />
 <jsp:setProperty name="netdbHelper" property="family" value="<%=request.getParameter(\"fam\")%>" />
 <jsp:setProperty name="netdbHelper" property="caps" value="<%=request.getParameter(\"caps\")%>" />
 <jsp:setProperty name="netdbHelper" property="ip" value="<%=request.getParameter(\"ip\")%>" />
 <jsp:setProperty name="netdbHelper" property="sybil" value="<%=request.getParameter(\"sybil\")%>" />
 <jsp:setProperty name="netdbHelper" property="sybil2" value="<%=request.getParameter(\"sybil2\")%>" />
 <jsp:setProperty name="netdbHelper" property="port" value="<%=request.getParameter(\"port\")%>" />
 <jsp:setProperty name="netdbHelper" property="type" value="<%=request.getParameter(\"type\")%>" />
 <jsp:setProperty name="netdbHelper" property="ipv6" value="<%=request.getParameter(\"ipv6\")%>" />
 <jsp:setProperty name="netdbHelper" property="cost" value="<%=request.getParameter(\"cost\")%>" />
 <jsp:setProperty name="netdbHelper" property="mtu" value="<%=request.getParameter(\"mtu\")%>" />
 <jsp:setProperty name="netdbHelper" property="ssucaps" value="<%=request.getParameter(\"ssucaps\")%>" />
 <jsp:getProperty name="netdbHelper" property="netDbSummary" />
</div></body></html>
