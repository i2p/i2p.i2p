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
 Sharing a higher percentage will improve your anonymity and help the network<br />
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 <hr />
 <b>Enable load testing: </b>
<input type="checkbox" name="enableloadtesting" value="true" <jsp:getProperty name="nethelper" property="enableLoadTesting" /> />
 <p>If enabled, your router will periodically anonymously probe some of your peers
 to see what sort of throughput they can handle.  This improves your router's ability
 to pick faster peers, but can cost substantial bandwidth.  Relevant data from the
 load testing is fed into the profiles as well as the 
 <a href="oldstats.jsp#test.rtt">test.rtt</a> and related stats.</p>
 <hr />
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
 <b>Inbound TCP connection configuration:</b><br />
 Externally reachable hostname or IP address:
    <input name ="ntcphost" type="text" size="16" value="<jsp:getProperty name="nethelper" property="ntcphostname" />" />
    (dyndns and the like are fine)<br />
 Externally reachable TCP port:
    <input name ="ntcpport" type="text" size="6" value="<jsp:getProperty name="nethelper" property="ntcpport" />" /><br />
 <p>You do <i>not</i> need to allow inbound TCP connections - outbound connections work with no
    configuration.  However, if you want to receive inbound TCP connections, you <b>must</b> poke a hole
    in your NAT or firewall for unsolicited TCP connections.  If you specify the wrong IP address or
    hostname, or do not properly configure your NAT or firewall, your network performance will degrade
    substantially.  When in doubt, leave the hostname and port number blank.</p>
 <p><b>Note: changing this setting will terminate all of your connections and effectively
    restart your router.</b>
 <hr />
<!--
 <b>Dynamic Router Keys: </b>
 <input type="checkbox" name="dynamicKeys" value="true" <jsp:getProperty name="nethelper" property="dynamicKeysChecked" /> /><br />
 <p>
 This setting causes your router identity to be regenerated every time your IP address
 changes. If you have a dynamic IP this option can speed up your reintegration into
 the network (since people will have shitlisted your old router identity), and, for
 very weak adversaries, help frustrate trivial
 <a href="http://www.i2p.net/how_threatmodel#intersection">intersection
 attacks</a> against the NetDB.  Your different router identities would only be 
 'hidden' among other I2P users at your ISP, and further analysis would link
 the router identities further.</p>
 <p>Note that when I2P detects an IP address change, it will automatically
 initiate a restart in order to rekey and to disconnect from peers before they
 update their profiles - any long lasting client connections will be disconnected,
 though such would likely already be the case anyway, since the IP address changed.
 </p>
 <hr />
-->
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 </form>
</div>

</body>
</html>
