<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config networking</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>

 <form action="config.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigNetHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigNetHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigNetHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigNetHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />

 <b>External UDP address:</b> <i><jsp:getProperty name="nethelper" property="udpAddress" /></i><br />
 <b>Require SSU introductions? </b>
<input type="checkbox" name="requireIntroductions" value="true" <jsp:getProperty name="nethelper" property="requireIntroductionsChecked" /> /><br />
 <p>If you can, please poke a hole in your NAT or firewall to allow unsolicited UDP packets to reach
    you on your external UDP address.  If you can't, I2P now includes supports UDP hole punching
    with "SSU introductions" - peers who will relay a request from someone you don't know to your
    router for your router so that you can make an outbound connection to them.  I2P will use these
    introductions automatically if it detects that the port is not forwarded (as shown by
    the <i>Status: OK (NAT)</i> line), or you can manually require them here.  
    Users behind symmetric NATs, such as OpenBSD's pf, are not currently supported.</p>
<input type="submit" name="recheckReachability" value="Check network reachability..." />
 <hr />
 
 <b>Bandwidth limiter</b><br />
 Inbound rate: 
    <input name="inboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundRate" />" /> KBps
 bursting up to 
    <input name="inboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />" /> KBps for
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br />
 Outbound rate:
    <input name="outboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundRate" />" /> KBps
 bursting up to 
    <input name="outboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />" /> KBps for
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br />
 <i>KBps = kilobytes per second = 1024 bytes per second.<br />
    A negative rate means a default limit of 16KBytes per second.</i><br />
 Bandwidth share percentage:
   <jsp:getProperty name="nethelper" property="sharePercentageBox" /><br />
 Sharing a higher percentage will improve your anonymity and help the network
 <hr />
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 </form>
</div>

</body>
</html>
