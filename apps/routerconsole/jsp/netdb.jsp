<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("network database")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Network Database")%></h1>
<div class="main" id="netdb">
 <jsp:useBean class="net.i2p.router.web.helpers.NetDbHelper" id="formhandler" scope="request" />
<%
    formhandler.storeWriter(out);
    if (allowIFrame)
        formhandler.allowGraphical();
%>
 <jsp:setProperty name="formhandler" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:setProperty name="formhandler" property="router" value="<%=request.getParameter(\"r\")%>" />
 <jsp:setProperty name="formhandler" property="lease" value="<%=request.getParameter(\"l\")%>" />
 <jsp:setProperty name="formhandler" property="version" value="<%=request.getParameter(\"v\")%>" />
 <jsp:setProperty name="formhandler" property="country" value="<%=request.getParameter(\"c\")%>" />
 <jsp:setProperty name="formhandler" property="family" value="<%=request.getParameter(\"fam\")%>" />
 <jsp:setProperty name="formhandler" property="caps" value="<%=request.getParameter(\"caps\")%>" />
 <jsp:setProperty name="formhandler" property="ip" value="<%=request.getParameter(\"ip\")%>" />
 <jsp:setProperty name="formhandler" property="sybil" value="<%=request.getParameter(\"sybil\")%>" />
 <jsp:setProperty name="formhandler" property="sybil2" value="<%=request.getParameter(\"sybil2\")%>" />
 <jsp:setProperty name="formhandler" property="port" value="<%=request.getParameter(\"port\")%>" />
 <jsp:setProperty name="formhandler" property="type" value="<%=request.getParameter(\"type\")%>" />
 <jsp:setProperty name="formhandler" property="ipv6" value="<%=request.getParameter(\"ipv6\")%>" />
 <jsp:setProperty name="formhandler" property="cost" value="<%=request.getParameter(\"cost\")%>" />
 <jsp:setProperty name="formhandler" property="mtu" value="<%=request.getParameter(\"mtu\")%>" />
 <jsp:setProperty name="formhandler" property="ssucaps" value="<%=request.getParameter(\"ssucaps\")%>" />
 <jsp:setProperty name="formhandler" property="transport" value="<%=request.getParameter(\"tr\")%>" />
 <jsp:setProperty name="formhandler" property="limit" value="<%=request.getParameter(\"ps\")%>" />
 <jsp:setProperty name="formhandler" property="page" value="<%=request.getParameter(\"pg\")%>" />
 <jsp:setProperty name="formhandler" property="mode" value="<%=request.getParameter(\"m\")%>" />
 <jsp:setProperty name="formhandler" property="date" value="<%=request.getParameter(\"date\")%>" />
 <jsp:setProperty name="formhandler" property="leaseset" value="<%=request.getParameter(\"ls\")%>" />
 <jsp:setProperty name="formhandler" property="sort" value="<%=request.getParameter(\"s\")%>" />
 <jsp:setProperty name="formhandler" property="intros" value="<%=request.getParameter(\"i\")%>" />
<%@include file="formhandler.jsi" %>
 <jsp:getProperty name="formhandler" property="netDbSummary" />
</div></body></html>
