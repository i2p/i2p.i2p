<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - logs</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>
<%@include file="notice.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 <form action="config.jsp" method="POST">
 <b>External hostname/IP address:</b> 
    <input name="hostname" type="text" size="32" value="<jsp:getProperty name="nethelper" property="hostname" />" />
    <input type="submit" name="guesshost" value="Guess" /><br />
 <b>Externally reachable TCP port:</b>
     <input name="port" type="text" size="4" value="<jsp:getProperty name="nethelper" property="port" />" /> <br />
 <i>The hostname/IP address and TCP port must be reachable from the outside world.  If
 you are behind a firewall or NAT, this means you must poke a hole for this port.  If
 you are using DHCP and do not have a static IP address, you must use a service like
 <a href="http://dyndns.org/">dyndns</a>.  The "guess" functionality makes an HTTP request
 to <a href="http://www.whatismyip.com/">www.whatismyip.com</a>.</i>
 <hr />
 <b>Enable internal time synchronization?</b> <input type="checkbox" <jsp:getProperty name="nethelper" property="enableTimeSyncChecked" /> name="enabletimesync" /><br />
 <i>If disabled, your machine <b>must</b> be NTP synchronized</i>
 <hr />
 <b>Bandwidth limiter</b><br />
 <b>Inbound rate</b>: 
    <input name="inboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundRate" />" /> KBytes per second<br />
 <b>Inbound burst duration:</b>
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br />
 <b>Outbound rate:</b>
    <input name="outboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundRate" />" /> KBytes per second<br />
 <b>Outbound burst duration:</b> 
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br />
 <i>A negative rate means there is no limit</i><br />
 <hr />
 <b>Reseed</b> (from <input name="reseedfrom" type="text" size="40" value="http://dev.i2p.net/i2pdb/" />): 
               <input type="submit" name="reseed" value="now" /><br />
 <hr />
 <input type="submit" value="Save changes" /> <input type="reset" value="Cancel" />
 </form>
</div>

</body>
</html>
