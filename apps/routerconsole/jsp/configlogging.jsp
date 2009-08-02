<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config logging</title>
<%@include file="css.jsp" %>
</head><body>
<jsp:useBean class="net.i2p.router.web.ConfigLoggingHelper" id="logginghelper" scope="request" />
<jsp:setProperty name="logginghelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<%@include file="summary.jsp" %>
<h1>I2P Logging Configuration</h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>

 <jsp:useBean class="net.i2p.router.web.ConfigLoggingHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
<div class="configure">  
 <form action="configlogging.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigLoggingHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigLoggingHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigLoggingHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigLoggingHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <h3>Configure I2P Logging Options</h3>
 <div class="wideload">
      <table border="0" cellspacing="5">
        <tr> 
          <td class="mediumtags" align="right"><b>Logging filename:</b> 
          <td><input type="text" name="logfilename" size="40" value="<jsp:getProperty name="logginghelper" property="logFilePattern" />" /> 
            <br /> <i>(the symbol '@' will be replaced during log rotation)</i> 
        <tr> 
          <td class="mediumtags" align="right"><b>Log record format:</b> 
          <td><input type="text" name="logformat" size="20" value="<jsp:getProperty name="logginghelper" property="recordPattern" />" /> 
            <br /> <i>(use 'd' = date, 'c' = class, 't' = thread, 'p' = priority, 
            'm' = message)</i> 
        <tr> 
          <td class="mediumtags" align="right"><b>Log date format:</b> 
          <td><input type="text" name="logdateformat" size="20" value="<jsp:getProperty name="logginghelper" property="datePattern" />" /> 
            <br /> <i>('MM' = month, 'dd' = day, 'HH' = hour, 'mm' = minute, 'ss' 
            = second, 'SSS' = millisecond)</i> 
        <tr> 
          <td class="mediumtags" align="right"><b>Max log file size:</b> 
          <td><input type="text" name="logfilesize" size="4" value="<jsp:getProperty name="logginghelper" property="maxFileSize" />" /> 
            <br /> 
        <tr> 
          <td class="mediumtags" align="right"><b>Default log level:</b> 
          <td><jsp:getProperty name="logginghelper" property="defaultLogLevelBox" /> <br /> <i>(DEBUG and INFO are not recommended defaults, 
            as they will drastically slow down your router)</i> 
        <tr> 
          <td class="mediumtags" align="right"><b>Log level overrides:</b> 
          <td><jsp:getProperty name="logginghelper" property="logLevelTable" /> 
        <tr> 
          <td colspan="2"><hr> 
        <tr> 
          <td>
          <td> <div align="right"> 
              <input type="submit" name="shouldsave" value="Save changes" />
              <input type="reset" value="Cancel" />
            </div> 
      </table>
 </form>
</div>
</div>
</div>
</body>
</html>
