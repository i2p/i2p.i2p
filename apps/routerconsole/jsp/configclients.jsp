<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config clients</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigClientsHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>
 
 <form action="configclients.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigClientsHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigClientsHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <b>Estimated number of clients/destinations:</b> 
    <jsp:getProperty name="clientshelper" property="clientCountSelectBox" /><br />
 <b>Default number of inbound tunnels per client:</b>
    <jsp:getProperty name="clientshelper" property="tunnelCountSelectBox" /><br />
 <b>Default number of hops per tunnel:</b>
    <jsp:getProperty name="clientshelper" property="tunnelDepthSelectBox" /><br />
 <hr />
 <input type="submit" name="shouldsave" value="Save changes" /> <input type="reset" value="Cancel" />
 </form>
</div>

</body>
</html>
