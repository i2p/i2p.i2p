<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - home</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>
<%
if (System.getProperty("router.consoleNonce") == null) {
    System.setProperty("router.consoleNonce", new java.util.Random().nextLong() + "");
}
%>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="news" id="news">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="newshelper" scope="request" />
 <jsp:setProperty name="newshelper" property="page" value="docs/news.xml" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:getProperty name="newshelper" property="content" />
</div>

<div class="main" id="main">

 <jsp:useBean class="net.i2p.router.web.ConfigServiceHandler" id="servicehandler" scope="request" />
 <jsp:setProperty name="servicehandler" property="*" />
 <jsp:setProperty name="servicehandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="servicehandler" property="errors" /></font>
 <i><jsp:getProperty name="servicehandler" property="notices" /></i>

 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="nethandler" scope="request" />
 <jsp:setProperty name="nethandler" property="*" />
 <jsp:setProperty name="nethandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="nethandler" property="errors" /></font>
 <i><jsp:getProperty name="nethandler" property="notices" /></i>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<form action="index.jsp" method="POST">
 <input type="hidden" name="nonce" value="<%=System.getProperty("router.consoleNonce")%>" />
 <input type="hidden" name="updateratesonly" value="true" />
 <input type="hidden" name="save" value="Save changes" />
 Inbound bandwidth:
    <input name="inboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundRate" />" /> KBps
 bursting up to 
    <input name="inboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />" /> KBps for
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br />
 Outbound bandwidth:
    <input name="outboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundRate" />" /> KBps
 bursting up to 
    <input name="outboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />" /> KBps for
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br />
 <i>KBps = kilobytes per second = 1024 bytes per second.</i>
 <input type="submit" value="Save changes" name="action" />
 <hr />
 <input type="submit" name="action" value="Graceful restart" />
 <input type="submit" name="action" value="Shutdown gracefully" />
 <a href="configservice.jsp">Other shutdown/restart options</a>
<hr />
</form>

 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <jsp:setProperty name="contenthelper" property="page" value="docs/readme.html" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:getProperty name="contenthelper" property="content" />
</div>

</body>
</html>
