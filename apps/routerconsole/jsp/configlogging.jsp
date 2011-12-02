<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config logging")%>
</head><body>
<jsp:useBean class="net.i2p.router.web.ConfigLoggingHelper" id="logginghelper" scope="request" />
<jsp:setProperty name="logginghelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Logging Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigLoggingHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
<div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <input type="hidden" name="action" value="blah" >
 <h3><%=intl._("Configure I2P Logging Options")%></h3>
 <div class="wideload">
      <table border="0" cellspacing="5">
        <tr><td class="mediumtags" align="right"><b><%=intl._("Log file")%>:</b></td>
          <td><input type="text" name="logfilename" size="40" value="<jsp:getProperty name="logginghelper" property="logFilePattern" />" >
            <br> <i><%=intl._("(the symbol '@' will be replaced during log rotation)")%></i></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Log record format")%>:</b></td>
          <td><input type="text" name="logformat" size="20" value="<jsp:getProperty name="logginghelper" property="recordPattern" />" >
            <br> <i><%=intl._("(use 'd' = date, 'c' = class, 't' = thread, 'p' = priority, 'm' = message)")%>
            </i></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Log date format")%>:</b></td>
          <td><input type="text" name="logdateformat" size="20" value="<jsp:getProperty name="logginghelper" property="datePattern" />" >
            <br> <i><%=intl._("('MM' = month, 'dd' = day, 'HH' = hour, 'mm' = minute, 'ss' = second, 'SSS' = millisecond)")%>
            </i></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Max log file size")%>:</b></td>
          <td><input type="text" name="logfilesize" size="10" value="<jsp:getProperty name="logginghelper" property="maxFileSize" />" ><br></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Default log level")%>:</b></td>
          <td><jsp:getProperty name="logginghelper" property="defaultLogLevelBox" /><br><i><%=intl._("(DEBUG and INFO are not recommended defaults, as they will drastically slow down your router)")%>
          </i></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("Log level overrides")%>:</b></td>
          <td><jsp:getProperty name="logginghelper" property="logLevelTable" /></td>
        </tr><tr><td class="mediumtags" align="right"><b><%=intl._("New override")%>:</b></td>
          <td><jsp:getProperty name="logginghelper" property="newClassBox" /></td>
        </tr><tr><td colspan="2"><hr></td>
        </tr><tr class="tablefooter"><td colspan="2"> <div class="formaction">
          <input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
          <input type="submit" name="shouldsave" class="accept" value="<%=intl._("Save changes")%>" >
</div></td></tr></table></div></form></div></div></body></html>
