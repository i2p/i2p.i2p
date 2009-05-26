<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config advanced</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigAdvancedHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 
 <form action="configadvanced.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigAdvancedHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigAdvancedHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigAdvancedHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigAdvancedHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <textarea rows="20" cols="100" name="config" wrap="off"><jsp:getProperty name="advancedhelper" property="settings" /></textarea><br />
 <input type="submit" name="shouldsave" value="Apply" /> <input type="reset" value="Cancel" /><!-- <br />
 <b>Force restart:</b> <input type="checkbox" name="restart" value="force" /> <i>(specify this
 if the changes made above require the router to reset itself - e.g. you are updating TCP ports 
 or hostnames, etc)</i>-->
 If you are changing any of the I2NP settings, you should go to the 
 <a href="configservice.jsp">service config</a> page and do a graceful restart after saving.
 </form>
</div>

</body>
</html>
