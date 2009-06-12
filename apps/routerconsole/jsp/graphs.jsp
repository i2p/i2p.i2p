<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - graphs</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.GraphHelper" id="graphHelper" scope="request" />
 <jsp:setProperty name="graphHelper" property="*" />
 <jsp:setProperty name="graphHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="graphHelper" property="writer" value="<%=out%>" />
 <jsp:getProperty name="graphHelper" property="images" />
 <jsp:getProperty name="graphHelper" property="form" />
</div>

</body>
</html>
