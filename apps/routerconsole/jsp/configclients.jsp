<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config clients</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>
<%@include file="notice.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 <form action="configclients.jsp" method="POST">
 <b>Estimated number of clients/destinations:</b> 
    <jsp:getProperty name="clientshelper" property="clientCountSelectBox" /><br />
 <b>Default number of inbound tunnels per client:</b>
    <jsp:getProperty name="clientshelper" property="tunnelCountSelectBox" /><br />
 <b>Default number of hops per tunnel:</b>
    <jsp:getProperty name="clientshelper" property="tunnelDepthSelectBox" /><br />
 <hr />
 <input type="submit" value="Save changes" /> <input type="reset" value="Cancel" />
 </form>
</div>

</body>
</html>
