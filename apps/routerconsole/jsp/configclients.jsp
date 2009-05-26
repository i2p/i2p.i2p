<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config clients</title>
<link rel="stylesheet" href="default.css" type="text/css" />
<style type='text/css'>
button span.hide{
    display:none;
}
</style>
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigClientsHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="formhandler" property="action" value="<%=request.getParameter("action")%>" />
 <jsp:setProperty name="formhandler" property="nonce" value="<%=request.getParameter("nonce")%>" />
 <jsp:setProperty name="formhandler" property="settings" value="<%=request.getParameterMap()%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 
 <form action="configclients.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigClientsHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigClientsHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigClientsHandler.nonce")%>" />
 <h3>Client Configuration</h3>
 <p>
 The Java clients listed below are started by the router and run in the same JVM.
 </p><p>
 <jsp:getProperty name="clientshelper" property="form1" />
 </p><p>
 <input type="submit" name="action" value="Save Client Configuration" />
 </p><p>
 <i>All changes require restart to take effect. To change other client options, edit the clients.config file.</i>
 </p>
 <hr />
 <h3>WebApp Configuration</h3>
 <p>
 The Java web applications listed below are started by the webConsole client and run in the same JVM as the router.
 They are usually web applications accessible through the router console.
 They may be complete applications (e.g. i2psnark),
 front-ends to another client or application which must be separately enabled (e.g. susidns, i2ptunnel),
 or have no web interface at all (e.g. addressbook).
 </p><p>
 A web app may also be disabled by removing the .war file from the webapps directory;
 however the .war file and web app will reappear when you update your router to a newer version,
 so disabling the web app here is the preferred method.
 </p><p>
 <jsp:getProperty name="clientshelper" property="form2" />
 </p><p>
 <input type="submit" name="action" value="Save WebApp Configuration" />
 </p><p>
 <i>All changes require restart to take effect. To change other webapp options, edit the webapps.config file.</i>
 </p>
 </form>
</div>

</body>
</html>
