<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - home</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <jsp:setProperty name="contenthelper" property="page" value="docs/readme.html" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:getProperty name="contenthelper" property="content" />
</div>

</body>
</html>
