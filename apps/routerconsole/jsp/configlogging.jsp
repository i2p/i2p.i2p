<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config clients</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>
<jsp:useBean class="net.i2p.router.web.ConfigLoggingHelper" id="logginghelper" scope="request" />
<jsp:setProperty name="logginghelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigLoggingHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>
  
 <form action="configlogging.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigLoggingHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigLoggingHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigLoggingHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigLoggingHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <b>Logging filename:</b> 
    <input type="text" name="logfilename" size="40" value="<jsp:getProperty name="logginghelper" property="logFilePattern" />" /><br />
    <i>(the symbol '@' will be replaced during log rotation)</i><br />
 <b>Log record format:</b>
    <input type="text" name="logformat" size="20" value="<jsp:getProperty name="logginghelper" property="recordPattern" />" /><br />
    <i>(use 'd' = date, 'c' = class, 't' = thread, 'p' = priority, 'm' = message)</i><br />
 <b>Log date format:</b>
    <input type="text" name="logdateformat" size="20" value="<jsp:getProperty name="logginghelper" property="datePattern" />" /><br />
    <i>('MM' = month, 'dd' = day, 'HH' = hour, 'mm' = minute, 'ss' = second, 'SSS' = millisecond)</i><br />
 <b>Max log file size:</b>
    <input type="text" name="logfilesize" size="4" value="<jsp:getProperty name="logginghelper" property="maxFileSize" />" /><br />
  <hr />
  <b>Log levels:</b> <br />
  <b>Default log level:</b>
   <jsp:getProperty name="logginghelper" property="defaultLogLevelBox" /><br />
   <jsp:getProperty name="logginghelper" property="logLevelTable" />
 <hr />
 <input type="submit" name="shouldsave" value="Save changes" /> 
 <input type="reset" value="Cancel" />
 </form>
</div>

</body>
</html>
