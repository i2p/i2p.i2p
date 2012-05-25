<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config networking</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<h1>I2P Network Configuration</h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
<div class="configure">
 <form action="config.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigNetHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigNetHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigNetHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigNetHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />
 <h3>Bandwidth limiter</h3>
 <p>
 <b>I2P will work best if you configure your rates to match the speed of your internet connection.</b>
 </p>
<p>
   <div class="wideload">
    <table>
    <tr><td><input style="text-align: right; width: 5em;" name="inboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="inboundRate" />" /> KBps
    In <td>(<jsp:getProperty name="nethelper" property="inboundRateBits" />)<br />
<!-- let's keep this simple...
 bursting up to 
    <input name="inboundburstrate" type="text" size="5" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />" /> KBps for
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br />
-->
    <tr><td><input style="text-align: right; width: 5em;" name="outboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="outboundRate" />" /> KBps
    Out <td>(<jsp:getProperty name="nethelper" property="outboundRateBits" />)<br />
<!-- let's keep this simple...
 bursting up to 
    <input name="outboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />" /> KBps for
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br />
 <i>KBps = kilobytes per second = 1024 bytes per second = 8192 bits per second.<br />
    A negative rate sets the default.</i><br />
-->
    <tr><td><jsp:getProperty name="nethelper" property="sharePercentageBox" />
    Share <td>(<jsp:getProperty name="nethelper" property="shareRateBits" />)<br />
  </table></div>
 </p><p>
 <% int share = nethelper.getShareBandwidth();
    if (share < 12) {
        out.print("<b>NOTE</b>: You have configured I2P to share only " + share + "KBps. ");
        out.print("I2P requires at least 12KBps to enable sharing. ");
        out.print("Please enable sharing (participating in tunnels) by configuring more bandwidth. ");
        out.print("It improves your anonymity by creating cover traffic, and helps the network.<br />");
    } else {
        out.print("You have configured I2P to share<b> " + share + "KBps</b>. ");
        out.print("The higher the share bandwidth the more you improve your anonymity and help the network.<hr />");
    }
 %>
 </p><div class="formaction">
<input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /></div>
 <!--
 <b>Enable load testing: </b>
<input type="checkbox" class="optbox" name="enableloadtesting" value="true" <jsp:getProperty name="nethelper" property="enableLoadTesting" /> />
 <p>If enabled, your router will periodically anonymously probe some of your peers
 to see what sort of throughput they can handle.  This improves your router's ability
 to pick faster peers, but can cost substantial bandwidth.  Relevant data from the
 load testing is fed into the profiles as well as the 
 <a href="oldstats.jsp#test.rtt">test.rtt</a> and related stats.</p>
 <hr />
-->
 <h3>IP and Transport Configuration</h3>
 <p>
 <b>The default settings will work for most people. There is <a href="#chelp">help below</a>.</b>
 </p><p>
 <b>UPnP Configuration:</b><br />
    <input type="checkbox" class="optbox" name="upnp" value="true" <jsp:getProperty name="nethelper" property="upnpChecked" /> />
    Enable UPnP to open firewall ports - <a href="peers.jsp#upnp">UPnP status</a>
 </p><p>
 <b>IP Configuration:</b><br />
 Externally reachable hostname or IP address:<br />
    <input type="radio" class="optbox" name="udpAutoIP" value="local,upnp,ssu" <%=nethelper.getUdpAutoIPChecked(3) %> />
    Use all auto-detect methods<br />
    <input type="radio" class="optbox" name="udpAutoIP" value="local,ssu" <%=nethelper.getUdpAutoIPChecked(4) %> />
    Disable UPnP IP address detection<br />
    <input type="radio" class="optbox" name="udpAutoIP" value="upnp,ssu" <%=nethelper.getUdpAutoIPChecked(5) %> />
    Ignore local interface IP address<br />
    <input type="radio" class="optbox" name="udpAutoIP" value="ssu" <%=nethelper.getUdpAutoIPChecked(0) %> />
    Use SSU IP address detection only<br />
    <input type="radio" class="optbox" name="udpAutoIP" value="fixed" <%=nethelper.getUdpAutoIPChecked(1) %> />
    Specify hostname or IP:
    <input name ="udpHost1" type="text" size="16" value="<jsp:getProperty name="nethelper" property="udphostname" />" />
    <% String[] ips = nethelper.getAddresses();
       if (ips.length > 0) {
           out.print(" or <select name=\"udpHost2\"><option value=\"\" selected=\"true\">Select Interface</option>\n");
           for (int i = 0; i < ips.length; i++) {
               out.print("<option value=\"");
               out.print(ips[i]);
               out.print("\">");
               out.print(ips[i]);
               out.print("</option>\n");
           }
           out.print("</select>\n");
       }
    %>
    <br />
    <input type="radio" class="optbox" name="udpAutoIP" value="hidden" <%=nethelper.getUdpAutoIPChecked(2) %> />
    Hidden mode - do not publish IP <i>(prevents participating traffic)</i><br />
 </p><p>
 <b>UDP Configuration:</b><br />
 UDP port:
 <input name ="udpPort" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="configuredUdpPort" />" /><br />
<!-- let's keep this simple...
<input type="checkbox" class="optbox" name="requireIntroductions" value="true" <jsp:getProperty name="nethelper" property="requireIntroductionsChecked" /> />
 Require SSU introductions
 <i>(Enable if you cannot open your firewall)</i>
 </p><p>
 Current External UDP address: <i><jsp:getProperty name="nethelper" property="udpAddress" /></i><br />
-->
 </p><p>
 <b>TCP Configuration:</b><br />
 Externally reachable hostname or IP address:<br />
    <input type="radio" class="optbox" name="ntcpAutoIP" value="true" <%=nethelper.getTcpAutoIPChecked(2) %> />
    Use auto-detected IP address
    <i>(currently <jsp:getProperty name="nethelper" property="udpIP" />)</i>
    if we are not firewalled<br />
    <input type="radio" class="optbox" name="ntcpAutoIP" value="always" <%=nethelper.getTcpAutoIPChecked(3) %> />
    Always use auto-detected IP address (Not firewalled)<br />
    <input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(1) %> />
    Specify hostname or IP:
    <input name ="ntcphost" type="text" size="16" value="<jsp:getProperty name="nethelper" property="ntcphostname" />" /><br />
    <input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(0) %> />
    Disable inbound (Firewalled)<br />
    <input type="radio" class="optbox" name="ntcpAutoIP" value="disabled" <%=nethelper.getTcpAutoIPChecked(4) %> />
    Completely disable <i>(select only if behind a firewall that throttles or blocks outbound TCP)</i><br />
 </p><p>
 Externally reachable TCP port:<br />
    <input type="radio" class="optbox" name="ntcpAutoPort" value="2" <%=nethelper.getTcpAutoPortChecked(2) %> />
    Use the same port configured for UDP
    <i>(currently <jsp:getProperty name="nethelper" property="udpPort" />)</i><br />
    <input type="radio" class="optbox" name="ntcpAutoPort" value="1" <%=nethelper.getTcpAutoPortChecked(1) %> />
    Specify Port:
    <input name ="ntcpport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="ntcpport" />" /><br />
 </p><p><b>Note: Changing these settings will restart your router.</b>
 </p><hr><div class="formaction">
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" />
</div>
</div>
<h3><a name="chelp">Configuration Help:</a></h3>
 <div align="justify">
 <p>
 While I2P will work fine behind most firewalls, your speeds and network integration will generally improve
 if the I2P port (generally 8887) is forwarded for both UDP and TCP.
 </p><p>
 If you can, please poke a hole in your firewall to allow unsolicited UDP and TCP packets to reach
    you.  If you can't, I2P supports UPnP (Universal Plug and Play) and UDP hole punching
    with "SSU introductions" to relay traffic. Most of the options above are for special situations,
    for example where UPnP does not work correctly, or a firewall not under your control is doing
    harm. Certain firewalls such as symmetric NATs may not work well with I2P.
 </p>
<!-- let's keep this simple...
<input type="submit" name="recheckReachability" value="Check network reachability..." />
-->
 </p><p>
 UPnP is used to communicate with Internet Gateway Devices (IGDs) to detect the external IP address
 and forward ports.
 UPnP support is beta, and may not work for any number of reasons:
 <ul>
<li class="tidylist">No UPnP-compatible device present
<li class="tidylist">UPnP disabled on the device
<li class="tidylist">Software firewall interference with UPnP
<li class="tidylist">Bugs in the device's UPnP implementation
<li class="tidylist">Multiple firewall/routers in the internet connection path
<li class="tidylist">UPnP device change, reset, or address change
 </ul><br>
 Reviewing the <a href="peers.jsp#upnp">UPnP status</a> may help.
 UPnP may be enabled or disabled above, but a change requires a router restart to take effect.
 </p><p>Hostnames entered above will be published in the network database.
    They are <b>not private</b>.
    Also, <b>do not enter a private IP address</b> like 127.0.0.1 or 192.168.1.1.
    If you specify the wrong IP address or
    hostname, or do not properly configure your NAT or firewall, your network performance will degrade
    substantially.  When in doubt, leave the settings at the defaults.</p>
 </p>
<h3><a name="help">Reachability Help:</a></h3>
 <p>
 While I2P will work fine behind most firewalls, your speeds and network integration will generally improve
 if the I2P port (generally 8887) to both UDP and TCP.
 If you think you have opened up your firewall and I2P still thinks you are firewalled, remember
 that you may have multiple firewalls, for example both software packages and external hardware routers.
 If there is an error, the <a href="logs.jsp">logs</a> may also help diagnose the problem.
 <ul>
<li class="tidylist"><b>OK</b> - Your UDP port does not appear to be firewalled.
<li class="tidylist"><b>Firewalled</b> - Your UDP port appears to be firewalled.
     As the firewall detection methods are not 100% reliable, this may occasionally be displayed in error.
     However, if it appears consistently, you should check whether both your external and internal
     firewalls are open on port 8887. I2P will work fine when firewalled, there is no reason for concern.
     When firewalled, the router uses "introducers" to relay inbound connections.
     However, you will get more participating traffic and help the network more if you can open your
     firewall(s). If you think you have already done so, remember that you may have both a hardware
     and a software firewall, or be behind an additional, institutional firewall you cannot control.
     Also, some routers cannot correctly forward both TCP and UDP on a single port, or may have other
     limitations or bugs that prevent them from passing traffic through to I2P.
<li class="tidylist"><b>Testing</b> - The router is currently testing whether your UDP port is firewalled.
<li class="tidylist"><b>Hidden</b> - The router is not configured to publish its address,
     therefore it does not expect incoming connections.
<li class="tidylist"><b>WARN - Firewalled and Fast</b> - You have configured I2P to share more than 128KBps of bandwidth,
     but you are firewalled. While I2P will work fine in this configuration, if you really have
     over 128KBps of bandwidth to share, it will be much more helpful to the network if
     you open your firewall.
<li class="tidylist"><b>WARN - Firewalled and Floodfill</b> - You have configured I2P to be a floodfill router, but
     you are firewalled. For best participation as a floodfill router, you should open your firewall.
<li class="tidylist"><b>WARN - Firewalled with Inbound TCP Enabled</b> - You have configured inbound TCP, however
     your UDP port is firewalled, and therefore it is likely that your TCP port is firewalled as well.
     If your TCP port is firewalled with inbound TCP enabled, routers will not be able to contact
     you via TCP, which will hurt the network. Please open your firewall or disable inbound TCP above.
<li class="tidylist"><b>WARN - Firewalled with UDP Disabled</b> -
     You have configured inbound TCP, however
     you have disabled UDP. You appear to be firewalled on TCP, therefore your router cannot
     accept inbound connections.
     Please open your firewall or enable UDP.
<li class="tidylist"><b>ERR - Clock Skew</b> - Your system's clock is skewed, which will make it difficult
     to participate in the network. Correct your clock setting if this error persists.
<li class="tidylist"><b>ERR - Private TCP Address</b> - You must never advertise an unroutable IP address such as
     127.0.0.1 or 192.168.1.1 as your external address. Correct the address or disable inbound TCP above.
<li class="tidylist"><b>ERR - SymmetricNAT</b> - I2P detected that you are firewalled by a Symmetric NAT.
     I2P does not work well behind this type of firewall. You will probably not be able to
     accept inbound connections, which will limit your participation in the network.
<li class="tidylist"><b>ERR - UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart</b> -
     I2P was unable to bind to port 8887 or other configured port.
     Check to see if another program is using port 8887. If so, stop that program or configure
     I2P to use a different port. This may be a transient error, if the other program is no longer
     using the port. However, a restart is always required after this error.
<li class="tidylist"><b>ERR - UDP Disabled and Inbound TCP host/port not set</b> -
     You have not configured inbound TCP with a hostname and port above, however
     you have disabled UDP. Therefore your router cannot accept inbound connections.
     Please configure a TCP host and port above or enable UDP.
<li class="tidylist"><b>ERR - Client Manager I2CP Error - check logs</b> -
     This is usually due to a port 7654 conflict. Check the logs to verify. Do you have another I2P instance running?
     Stop the conflicting program and restart I2P.
 </ul>
 </p>
 <hr />
      <!--
 <b>Dynamic Router Keys: </b>
 <input type="checkbox" class="optbox" name="dynamicKeys" value="true" <jsp:getProperty name="nethelper" property="dynamicKeysChecked" /> /><br />
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
</div>
</body>
</html>
