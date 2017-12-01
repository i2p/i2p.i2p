<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config logging")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<jsp:useBean class="net.i2p.router.web.helpers.ConfigLoggingHelper" id="logginghelper" scope="request" />
<jsp:setProperty name="logginghelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Logging Configuration")%></h1>
<div class="main" id="config_logging">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigLoggingHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
 <h3 class="tabletitle"><%=intl._t("Configure I2P Logging Options")%>&nbsp;<a title="<%=intl._t("View Router Logs")%>" href="/logs">[<%=intl._t("View Logs")%>]</a></h3>
      <table id="loggingoptions" border="0" cellspacing="5">
        <tr><td align="right"><b><%=intl._t("Log file")%>:</b></td>
          <td><input type="text" name="logfilename" size="40" disabled="disabled" title="<%=intl._t("Edit {0} to change", "logger.config")%>" value="<jsp:getProperty name="logginghelper" property="logFilePattern" />" >
            </td>
          <td><%=intl._t("(the symbol '@' will be replaced during log rotation)")%></td>
        </tr><tr><td align="right"><b><%=intl._t("Log record format")%>:</b></td>
          <td><input type="text" name="logformat" size="20" value="<jsp:getProperty name="logginghelper" property="recordPattern" />" >
            </td>
          <td><%=intl._t("(use 'd' = date, 'c' = class, 't' = thread, 'p' = priority, 'm' = message)")%></td>
        </tr><tr><td align="right"><b><%=intl._t("Log date format")%>:</b></td>
          <td><input type="text" name="logdateformat" size="20" value="<jsp:getProperty name="logginghelper" property="datePattern" />" >
            </td>
          <td><%=intl._t("('MM' = month, 'dd' = day, 'HH' = hour, 'mm' = minute, 'ss' = second, 'SSS' = millisecond)")%></td>
        </tr><tr><td align="right"><b><%=intl._t("Max log file size")%>:</b></td>
          <td><input type="text" name="logfilesize" size="10" value="<jsp:getProperty name="logginghelper" property="maxFileSize" />" ></td>
          <td></td>
        </tr><tr><td align="right"><b><%=intl._t("Default log level")%>:</b></td>
          <td><jsp:getProperty name="logginghelper" property="defaultLogLevelBox" /></td>
          <td><%=intl._t("(DEBUG and INFO are not recommended defaults, as they will drastically slow down your router)")%></td>
        </tr><tr><td align="right"><b><%=intl._t("Log level overrides")%>:</b></td>
          <td colspan="2"><jsp:getProperty name="logginghelper" property="logLevelTable" /></td>
        </tr><tr><td align="right"><b><%=intl._t("New override")%>:</b></td>
          <td colspan="2"><jsp:getProperty name="logginghelper" property="newClassBox" /></td>
        </tr>
        <tr><td class="optionsave" colspan="3">
          <input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
          <input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</td></tr></table></form></div></body></html>
