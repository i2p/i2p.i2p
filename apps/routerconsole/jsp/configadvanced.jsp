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
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>
 
 <form action="configadvanced.jsp" method="POST">
 <textarea rows="20" cols="100" name="config"><jsp:getProperty name="advancedhelper" property="settings" /></textarea><br />
 <input type="submit" name="shouldsave" value="Apply" /> <input type="reset" value="Cancel" /> <br />
 <b>Force restart:</b> <input type="checkbox" name="restart" value="force" /> <i>(specify this
 if the changes made above require the router to reset itself - e.g. you are updating TCP ports 
 or hostnames, etc)</i>
 </form>
</div>

</body>
</html>
