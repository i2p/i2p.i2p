<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - logs</title>
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

 TCP port:
     <input name="port" type="text" size="4" value="<jsp:getProperty name="nethelper" property="port" />" /> <br />
 <b>You must poke a hole in your firewall or NAT (if applicable) so that you can receive inbound TCP
 connections on it.</b>  Nothing will work if you don't.  Sorry.  We know how to make it so
 this restriction won't be necessary, but its later on in the 
 <a href="http://www.i2p.net/roadmap">roadmap</a> and we only have so many coder-hours (but if you want
 to help, please <a href="http://www.i2p.net/getinvolved">get involved!</a>)
 <hr />
 
 <b>Bandwidth limiter</b><br />
 Inbound rate: 
    <input name="inboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundRate" />" /> KBytes per second
 bursting up to 
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br />
 Outbound rate:
    <input name="outboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundRate" />" /> KBytes per second
 bursting up to 
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br />
 <i>A negative rate means there is no limit</i><br />
 Bandwidth share percentage:
   <jsp:getProperty name="nethelper" property="sharePercentageBox" /><br />
 Sharing a higher percentage will improve your anonymity and help the network
 <hr />
 Enable internal time synchronization? <input type="checkbox" <jsp:getProperty name="nethelper" property="enableTimeSyncChecked" /> name="enabletimesync" /><br />
 <i>If disabled, your machine <b>must</b> be NTP synchronized - your clock must always
    be within a few seconds of "correct".  You will need to be able to send outbound UDP
    packets on port 123 to one of the pool.ntp.org machines (or some other SNTP server).</i>
 <hr />
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 <i>Changing the TCP port will force a 'soft restart' - dropping your connections and clients as 
    if the router was stopped and restarted.  <b>Please be patient</b> - it may take
    a few seconds to complete.</i>
 </form>
 <hr />
 <b>Advanced network config:</b>
 <p>
 One advanced network option has to do with reseeding - you should never need to 
 reseed your router as long as you can find at least one other peer on the network.  However,
 when you do need to reseed, a link will show up on the left hand side which will
 fetch all of the routerInfo-* files from http://dev.i2p.net/i2pdb/.  That URL is just an
 apache folder pointing at the netDb/ directory of a router - anyone can run one, and you can
 configure your router to seed off an alternate URL by adding the java environmental property
 "i2p.reseedURL=someURL" (e.g. java -Di2p.reseedURL=http://dev.i2p.net/i2pdb/ ...).  You can
 also do it manually by getting routerInfo-*.dat files from someone (a friend, someone on IRC,
 whatever) and saving them to your netDb/ directory.</p>
</div>

</body>
</html>
