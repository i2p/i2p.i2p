<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - peer profiles</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="profilesHelper" property="profileSummary" />
 <hr />
 <a name="shitlist"> </a>
 <jsp:getProperty name="profilesHelper" property="shitlistSummary" />
</div>

</body>
</html>
