<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config logging</title>
<%@include file="css.jsp" %>
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
 <jsp:getProperty name="formhandler" property="allMessages" />
  
 <form action="configlogging.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigLoggingHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigLoggingHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigLoggingHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigLoggingHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <table border="0" cellspacing="5">
 <tr><td valign="top"><b>Logging filename:</b> 
 <td><input type="text" name="logfilename" size="40" value="<jsp:getProperty name="logginghelper" property="logFilePattern" />" /><br />
 <i>(the symbol '@' will be replaced during log rotation)</i>
 <tr><td valign="top"><b>Log record format:</b>
 <td><input type="text" name="logformat" size="20" value="<jsp:getProperty name="logginghelper" property="recordPattern" />" /><br />
 <i>(use 'd' = date, 'c' = class, 't' = thread, 'p' = priority, 'm' = message)</i>
 <tr><td valign="top"><b>Log date format:</b>
 <td><input type="text" name="logdateformat" size="20" value="<jsp:getProperty name="logginghelper" property="datePattern" />" /><br />
 <i>('MM' = month, 'dd' = day, 'HH' = hour, 'mm' = minute, 'ss' = second, 'SSS' = millisecond)</i>
 <tr><td valign="top"><b>Max log file size:</b>
 <td><input type="text" name="logfilesize" size="4" value="<jsp:getProperty name="logginghelper" property="maxFileSize" />" /><br />
 <tr><td valign="top"><b>Default log level:</b>
 <td><jsp:getProperty name="logginghelper" property="defaultLogLevelBox" />
 <br /><i>(DEBUG and INFO are not recommended defaults, as they will drastically slow down your router)</i>
 <tr><td valign="top"><b>Log level overrides:</b>
 <td><jsp:getProperty name="logginghelper" property="logLevelTable" />
 <tr><td><td>
 <input type="submit" name="shouldsave" value="Save changes" /> 
 <input type="reset" value="Cancel" />
 </table>
 </form>
</div>

</body>
</html>
