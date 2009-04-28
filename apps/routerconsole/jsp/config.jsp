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
 <i>KBps = kilobytes per second = 1024 bytes per second = 8192 bits per second.<br />
    A negative rate sets the default.</i><br />
 Bandwidth share percentage:
   <jsp:getProperty name="nethelper" property="sharePercentageBox" /><br />
 <% int share = nethelper.getShareBandwidth();
    if (share < 12) {
        out.print("<b>NOTE</b>: You have configured I2P to share only " + share + "KBps. ");
        out.print("I2P requires at least 12KBps to enable sharing. ");
        out.print("Please enable sharing (participating in tunnels) by configuring more bandwidth. ");
        out.print("It improves your anonymity by creating cover traffic, and helps the network.<br />");
    } else {
        out.print("You have configured I2P to share " + share + "KBps. ");
        out.print("The higher the share bandwidth the more you improve your anonymity and help the network.<br />");
    }
 %>
 <p>
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 <hr />
<!--
 <b>Enable load testing: </b>
<input type="checkbox" name="enableloadtesting" value="true" <jsp:getProperty name="nethelper" property="enableLoadTesting" /> />
 <p>If enabled, your router will periodically anonymously probe some of your peers
 to see what sort of throughput they can handle.  This improves your router's ability
 to pick faster peers, but can cost substantial bandwidth.  Relevant data from the
 load testing is fed into the profiles as well as the 
 <a href="oldstats.jsp#test.rtt">test.rtt</a> and related stats.</p>
 <hr />
-->
 <b>UDP Configuration:</b><br />
 Internal UDP port:
 <input name ="udpPort" type="text" size="6" value="<jsp:getProperty name="nethelper" property="configuredUdpPort" />" /><br />
 External UDP address: <i><jsp:getProperty name="nethelper" property="udpAddress" /></i><br />
 Require SSU introductions?
<input type="checkbox" name="requireIntroductions" value="true" <jsp:getProperty name="nethelper" property="requireIntroductionsChecked" /> /><br />
 <p>If you can, please poke a hole in your NAT or firewall to allow unsolicited UDP packets to reach
    you on your external UDP address.  If you can't, I2P now includes supports UDP hole punching
    with "SSU introductions" - peers who will relay a request from someone you don't know to your
    router for your router so that you can make an outbound connection to them.  I2P will use these
    introductions automatically if it detects that the port is not forwarded (as shown by
    the <i>Status: Firewalled</i> line), or you can manually require them here.  
    Users behind symmetric NATs, such as OpenBSD's pf, are not currently supported.</p>
<input type="submit" name="recheckReachability" value="Check network reachability..." />
 <p>
 <b>Inbound TCP connection configuration:</b><br />
 Externally reachable hostname or IP address:
    <input name ="ntcphost" type="text" size="16" value="<jsp:getProperty name="nethelper" property="ntcphostname" />" />
    (dyndns and the like are fine)<br />
    OR use IP address detected by SSU
    (currently <jsp:getProperty name="nethelper" property="udpIP" />)?
    <input type="checkbox" name="ntcpAutoIP" value="true" <jsp:getProperty name="nethelper" property="tcpAutoIPChecked" /> /><br />
 <p>
 Externally reachable TCP port:
    <input name ="ntcpport" type="text" size="6" value="<jsp:getProperty name="nethelper" property="ntcpport" />" /><br />
    OR use the same port configured for SSU
    (currently <jsp:getProperty name="nethelper" property="udpPort" />)?
    <input type="checkbox" name="ntcpAutoPort" value="true" <jsp:getProperty name="nethelper" property="tcpAutoPortChecked" /> /><br />
 <p>A hostname entered here will be published in the network database.
    It is <b>not private</b>.
    Also, <b>do not enter a private IP address</b> like 127.0.0.1 or 192.168.1.1.
 </p>
 <p>You do <i>not</i> need to allow inbound TCP connections - outbound connections work with no
    configuration.  However, if you want to receive inbound TCP connections, you <b>must</b> poke a hole
    in your NAT or firewall for unsolicited TCP connections.  If you specify the wrong IP address or
    hostname, or do not properly configure your NAT or firewall, your network performance will degrade
    substantially.  When in doubt, leave the hostname and port number blank.</p>
 <p>
 <b>UPnP Configuration:</b><br />
 Open firewall port using UPnP:
    <input type="checkbox" name="upnp" value="true" <jsp:getProperty name="nethelper" property="upnpChecked" /> /><br />
 </p>
 <p><b>Note: changing any of these settings will terminate all of your connections and effectively
    restart your router.</b>
 </p>
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 <hr />
 <b><a name="help">Reachability Help:</a></b>
 <p>
 While I2P will work adequately behind a firewall, your speeds and network integration will generally improve
 if you open up your port (generally 8887) to both UDP and TCP, and enable inbound TCP above.
 If you think you have opened up your firewall and I2P still thinks you are firewalled, remember
 that you may have multiple firewalls, for example both software packages and external hardware routers.
 If there is an error, the <a href="logs.jsp">logs</a> may also help diagnose the problem.
 <ul>
 <li><b>OK</b> - Your UDP port does not appear to be firewalled.
 <li><b>Firewalled</b> - Your UDP port appears to be firewalled.
     As the firewall detection methods are not 100% reliable, this may occasionally be displayed in error.
     However, if it appears consistently, you should check whether both your external and internal
     firewalls are open on port 8887. I2P will work fine when firewalled, there is no reason for concern.
     When firewalled, the router uses "introducers" to relay inbound connections.
     However, you will get more participating traffic and help the network more if you can open your
     firewall(s). If you think you have already done so, remember that you may have both a hardware
     and a software firewall, or be behind an additional, institutional firewall you cannot control.
     Also, some routers cannot correctly forward both TCP and UDP on a single port, or may have other
     limitations or bugs that prevent them from passing traffic through to I2P.
 <li><b>Testing</b> - The router is currently testing whether your UDP port is firewalled.
 <li><b>Hidden</b> - The router is not configured to publish its address,
     therefore it does not expect incoming connections.
 <li><b>WARN - Firewalled and Fast</b> - You have configured I2P to share more than 128KBps of bandwidth,
     but you are firewalled. While I2P will work fine in this configuration, if you really have
     over 128KBps of bandwidth to share, it will be much more helpful to the network if
     you open your firewall.
 <li><b>WARN - Firewalled and Floodfill</b> - You have configured I2P to be a floodfill router, but
     you are firewalled. For best participation as a floodfill router, you should open your firewall.
 <li><b>WARN - Firewalled with Inbound TCP Enabled</b> - You have configured inbound TCP, however
     your UDP port is firewalled, and therefore it is likely that your TCP port is firewalled as well.
     If your TCP port is firewalled with inbound TCP enabled, routers will not be able to contact
     you via TCP, which will hurt the network. Please open your firewall or disable inbound TCP above.
 <li><b>WARN - Firewalled with UDP Disabled</b> -
     You have configured inbound TCP, however
     you have disabled UDP. You appear to be firewalled on TCP, therefore your router cannot
     accept inbound connections.
     Please open your firewall or enable UDP.
 <li><b>ERR - Clock Skew</b> - Your system's clock is skewed, which will make it difficult
     to participate in the network. Correct your clock setting if this error persists.
 <li><b>ERR - Private TCP Address</b> - You must never advertise an unroutable IP address such as
     127.0.0.1 or 192.168.1.1 as your external address. Correct the address or disable inbound TCP above.
 <li><b>ERR - SymmetricNAT</b> - I2P detected that you are firewalled by a Symmetric NAT.
     I2P does not work well behind this type of firewall. You will probably not be able to
     accept inbound connections, which will limit your participation in the network.
 <li><b>ERR - UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart</b> -
     I2P was unable to bind to port 8887 or other configured port.
     Check to see if another program is using port 8887. If so, stop that program or configure
     I2P to use a different port. This may be a transient error, if the other program is no longer
     using the port. However, a restart is always required after this error.
 <li><b>ERR - UDP Disabled and Inbound TCP host/port not set</b> -
     You have not configured inbound TCP with a hostname and port above, however
     you have disabled UDP. Therefore your router cannot accept inbound connections.
     Please configure a TCP host and port above or enable UDP.
 <li><b>ERR - Client Manager I2CP Error - check logs</b> -
     This is usually due to a port 7654 conflict. Check the logs to verify. Do you have another I2P instance running?
     Stop the conflicting program and restart I2P.
 </ul>
 </p>
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
 </form>
</div>

</body>
</html>
