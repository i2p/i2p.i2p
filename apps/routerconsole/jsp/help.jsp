<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - logs</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
hmm.  we should probably have some help text here.<br />
This "routerconsole" application runs on top of a trimmed down <a href="jetty.mortbay.com/jetty/index.html">Jetty</a>
instance (trimmed down, as in, we do not include the demo apps or other add-ons), allowing you to deploy standard 
JSP/Servlet web applications into your router.  Jetty in turn makes use of Apache's javax.servlet (javax.servlet.jar)
implementation, as well as their xerces-j XML parser (xerces.jar).  Their XML parser requires the Sun XML
APIs (JAXP) which is included in binary form (xml-apis.jar) as required by their binary code license.
This product includes software developed by the Apache Software Foundation (http://www.apache.org/).  See the
<a href="http://www.i2p.net/">I2P</a> site or the source for more license details.
</div>

</body>
</html>
