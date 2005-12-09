<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config tunnels</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigTunnelsHelper" id="tunnelshelper" scope="request" />
<jsp:setProperty name="tunnelshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigTunnelsHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="formhandler" property="shouldsave" value="<%=request.getParameter("shouldsave")%>" />
 <jsp:setProperty name="formhandler" property="action" value="<%=request.getParameter("action")%>" />
 <jsp:setProperty name="formhandler" property="nonce" value="<%=request.getParameter("nonce")%>" />
 <jsp:setProperty name="formhandler" property="settings" value="<%=request.getParameterMap()%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>
 
 <form action="configtunnels.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigTunnelsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigTunnelsHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigTunnelsHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigTunnelsHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <jsp:getProperty name="tunnelshelper" property="form" />
 <hr />
 <i>Note - Exploratory tunnel setting changes are stored in the router.config file.</i></br>
 <i>Client tunnel changes are temporary and are not saved.</i><br>
 <i>To make permanent client tunnel changes see the </i><a href="i2ptunnel/index.jsp">i2ptunnel page</a>.<br>
 <input type="submit" name="shouldsave" value="Save changes" /> <input type="reset" value="Cancel" />
 </form>
</div>

</body>
</html>
