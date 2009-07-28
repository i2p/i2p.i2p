<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - statistics</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.OldConsoleHelper" id="oldhelper" scope="request" />
<jsp:setProperty name="oldhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<jsp:setProperty name="oldhelper" property="writer" value="<%=out%>" />
 <h1>I2P Router Statistics</h1>
<div class="main" id="main">
 <jsp:getProperty name="oldhelper" property="stats" />
</div>
</div>
</body>
</html>
