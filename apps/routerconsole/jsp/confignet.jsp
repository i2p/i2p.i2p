<%@page contentType="text/html" %>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config networking")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._t("I2P Network Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
 <h3><%=intl._t("IP and Transport Configuration")%></h3><p>
 <img src="/themes/console/images/itoopie_xsm.png" alt="">
 <b><%=intl._t("The default settings will work for most people.")%>
 <a href="#chelp"><%=intl._t("There is help below.")%></a></b>
 </p><p><b><%=intl._t("UPnP Configuration")%>:</b><br>
    <input type="checkbox" class="optbox" name="upnp" value="true" <jsp:getProperty name="nethelper" property="upnpChecked" /> >
    <%=intl._t("Enable UPnP to open firewall ports")%> - <a href="peers#upnp"><%=intl._t("UPnP status")%></a>
 </p><p><b><%=intl._t("IP Configuration")%>:</b><br>
 <%=intl._t("Externally reachable hostname or IP address")%>:<br>
    <input type="radio" class="optbox" name="udpAutoIP" value="local,upnp,ssu" <%=nethelper.getUdpAutoIPChecked(3) %> >
    <%=intl._t("Use all auto-detect methods")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="local,ssu" <%=nethelper.getUdpAutoIPChecked(4) %> >
    <%=intl._t("Disable UPnP IP address detection")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="upnp,ssu" <%=nethelper.getUdpAutoIPChecked(5) %> >
    <%=intl._t("Ignore local interface IP address")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="ssu" <%=nethelper.getUdpAutoIPChecked(0) %> >
    <%=intl._t("Use SSU IP address detection only")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="hidden" <%=nethelper.getUdpAutoIPChecked(2) %> >
    <%=intl._t("Hidden mode - do not publish IP")%> <i><%=intl._t("(prevents participating traffic)")%></i><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="fixed" <%=nethelper.getUdpAutoIPChecked(1) %> >
    <%=intl._t("Specify hostname or IP")%>:<br>
    <%=nethelper.getAddressSelector() %>
 </p><p>
 <%=intl._t("Action when IP changes")%>:<br>
    <input type="checkbox" class="optbox" name="laptop" value="true" <jsp:getProperty name="nethelper" property="laptopChecked" /> >
    <%=intl._t("Laptop mode - Change router identity and UDP port when IP changes for enhanced anonymity")%>
    (<i><%=intl._t("Experimental")%></i>)
 </p><p>
 <%=intl._t("IPv4 Configuration")%>:<br>
    <input type="checkbox" class="optbox" name="IPv4Firewalled" value="true" <jsp:getProperty name="nethelper" property="IPv4FirewalledChecked" /> >
    <%=intl._t("Disable inbound (Firewalled by Carrier-grade NAT or DS-Lite)")%>
 </p><p>
 <%=intl._t("IPv6 Configuration")%>:<br>
    <input type="radio" class="optbox" name="ipv6" value="false" <%=nethelper.getIPv6Checked("false") %> >
    <%=intl._t("Disable IPv6")%><br>
    <input type="radio" class="optbox" name="ipv6" value="enable" <%=nethelper.getIPv6Checked("enable") %> >
    <%=intl._t("Enable IPv6")%><br>
    <input type="radio" class="optbox" name="ipv6" value="preferIPv4" <%=nethelper.getIPv6Checked("preferIPv4") %> >
    <%=intl._t("Prefer IPv4 over IPv6")%><br>
    <input type="radio" class="optbox" name="ipv6" value="preferIPv6" <%=nethelper.getIPv6Checked("preferIPv6") %> >
    <%=intl._t("Prefer IPv6 over IPv4")%><br>
    <input type="radio" class="optbox" name="ipv6" value="only" <%=nethelper.getIPv6Checked("only") %> >
    <%=intl._t("Use IPv6 only (disable IPv4)")%>
    (<i><%=intl._t("Experimental")%></i>)<br>
 </p><p><b><%=intl._t("UDP Configuration:")%></b><br>
 <%=intl._t("UDP port:")%>
 <input name ="udpPort" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="configuredUdpPort" />" ><br>
 <input type="checkbox" class="optbox" name="disableUDP" value="disabled" <%=nethelper.getUdpDisabledChecked() %> >
 <%=intl._t("Completely disable")%> <i><%=intl._t("(select only if behind a firewall that blocks outbound UDP)")%></i><br>
<% /********
<!-- let's keep this simple...
<input type="checkbox" class="optbox" name="requireIntroductions" value="true" <jsp:getProperty name="nethelper" property="requireIntroductionsChecked" /> />
 Require SSU introductions
 <i>(Enable if you cannot open your firewall)</i>
 </p><p>
 Current External UDP address: <i><jsp:getProperty name="nethelper" property="udpAddress" /></i><br>
-->
*********/ %>
 </p><p>
 <b><%=intl._t("TCP Configuration")%>:</b><br>
 <%=intl._t("Externally reachable hostname or IP address")%>:<br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="true" <%=nethelper.getTcpAutoIPChecked(2) %> >
    <%=intl._t("Use auto-detected IP address")%>
    <i>(<%=intl._t("currently")%> <jsp:getProperty name="nethelper" property="udpIP" />)</i>
    <%=intl._t("if we are not firewalled")%><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="always" <%=nethelper.getTcpAutoIPChecked(3) %> >
    <%=intl._t("Always use auto-detected IP address (Not firewalled)")%><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(1) %> >
    <%=intl._t("Specify hostname or IP")%>:
    <input name ="ntcphost" type="text" size="16" value="<jsp:getProperty name="nethelper" property="ntcphostname" />" ><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(0) %> >
    <%=intl._t("Disable inbound (Firewalled)")%><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="disabled" <%=nethelper.getTcpAutoIPChecked(4) %> >
    <%=intl._t("Completely disable")%> <i><%=intl._t("(select only if behind a firewall that throttles or blocks outbound TCP)")%></i><br>
 </p><p>
 <%=intl._t("Externally reachable TCP port")%>:<br>
    <input type="radio" class="optbox" name="ntcpAutoPort" value="2" <%=nethelper.getTcpAutoPortChecked(2) %> >
    <%=intl._t("Use the same port configured for UDP")%>
    <i>(<%=intl._t("currently")%> <jsp:getProperty name="nethelper" property="udpPort" />)</i><br>
    <input type="radio" class="optbox" name="ntcpAutoPort" value="1" <%=nethelper.getTcpAutoPortChecked(1) %> >
    <%=intl._t("Specify Port")%>:
    <input name ="ntcpport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="ntcpport" />" ><br>
 </p><p><b><%=intl._t("Notes")%>: <%=intl._t("a) Do not reveal your port numbers to anyone!   b) Changing these settings will restart your router.")%></b></p>
<hr><div class="formaction">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" class="accept" name="save" value="<%=intl._t("Save changes")%>" >
</div><h3><a name="chelp"><%=intl._t("Configuration Help")%>:</a></h3><div align="justify"><p>
 <%=intl._t("While I2P will work fine behind most firewalls, your speeds and network integration will generally improve if the I2P port is forwarded for both UDP and TCP.")%>
 </p><p>
 <%=intl._t("If you can, please poke a hole in your firewall to allow unsolicited UDP and TCP packets to reach you.")%>
   <%=intl._t("If you can't, I2P supports UPnP (Universal Plug and Play) and UDP hole punching with \"SSU introductions\" to relay traffic.")%>
   <%=intl._t("Most of the options above are for special situations, for example where UPnP does not work correctly, or a firewall not under your control is doing harm.")%> 
   <%=intl._t("Certain firewalls such as symmetric NATs may not work well with I2P.")%>
 </p>
<% /********
<!-- let's keep this simple...
<input type="submit" name="recheckReachability" value="Check network reachability..." />
</p>
-->
*********/ %>
<p>
 <%=intl._t("UPnP is used to communicate with Internet Gateway Devices (IGDs) to detect the external IP address and forward ports.")%>
   <%=intl._t("UPnP support is beta, and may not work for any number of reasons")%>:
</p>
<ul>
<li class="tidylist"><%=intl._t("No UPnP-compatible device present")%>
<li class="tidylist"><%=intl._t("UPnP disabled on the device")%>
<li class="tidylist"><%=intl._t("Software firewall interference with UPnP")%>
<li class="tidylist"><%=intl._t("Bugs in the device's UPnP implementation")%>
<li class="tidylist"><%=intl._t("Multiple firewall/routers in the internet connection path")%>
<li class="tidylist"><%=intl._t("UPnP device change, reset, or address change")%>
</ul>
<p><a href="peers#upnp"><%=intl._t("Review the UPnP status here.")%></a>
<%=intl._t("UPnP may be enabled or disabled above, but a change requires a router restart to take effect.")%></p>
<p><%=intl._t("Hostnames entered above will be published in the network database.")%>
    <%=intl._t("They are <b>not private</b>.")%>
    <%=intl._t("Also, <b>do not enter a private IP address</b> like 127.0.0.1 or 192.168.1.1.")%>
    <%=intl._t("If you specify the wrong IP address or hostname, or do not properly configure your NAT or firewall, your network performance will degrade substantially.")%>
    <%=intl._t("When in doubt, leave the settings at the defaults.")%>
</p>
<h3><a name="help"><%=intl._t("Reachability Help")%>:</a></h3><p>
 <%=intl._t("While I2P will work fine behind most firewalls, your speeds and network integration will generally improve if the I2P port is forwarded for both UDP and TCP.")%>
 <%=intl._t("If you think you have opened up your firewall and I2P still thinks you are firewalled, remember that you may have multiple firewalls, for example both software packages and external hardware routers.")%>
 <%=intl._t("If there is an error, the <a href=\"logs.jsp\">logs</a> may also help diagnose the problem.")%>
</p>
 <ul>
<li class="tidylist"><b><%=intl._t("OK")%></b> - 
     <%=intl._t("Your UDP port does not appear to be firewalled.")%>
<li class="tidylist"><b><%=intl._t("Firewalled")%></b> - 
     <%=intl._t("Your UDP port appears to be firewalled.")%>
     <%=intl._t("As the firewall detection methods are not 100% reliable, this may occasionally be displayed in error.")%>
     <%=intl._t("However, if it appears consistently, you should check whether both your external and internal firewalls are open for your port.")%> 
     <%=intl._t("I2P will work fine when firewalled, there is no reason for concern. When firewalled, the router uses \"introducers\" to relay inbound connections.")%>
     <%=intl._t("However, you will get more participating traffic and help the network more if you can open your firewall(s).")%>
     <%=intl._t("If you think you have already done so, remember that you may have both a hardware and a software firewall, or be behind an additional, institutional firewall you cannot control.")%>
     <%=intl._t("Also, some routers cannot correctly forward both TCP and UDP on a single port, or may have other limitations or bugs that prevent them from passing traffic through to I2P.")%>
<li class="tidylist"><b><%=intl._t("Testing")%></b> - 
     <%=intl._t("The router is currently testing whether your UDP port is firewalled.")%>
<li class="tidylist"><b><%=intl._t("Hidden")%></b> - 
     <%=intl._t("The router is not configured to publish its address, therefore it does not expect incoming connections.")%>
     <%=intl._t("Hidden mode is automatically enabled for added protection in certain countries.")%>
<li class="tidylist"><b><%=intl._t("WARN - Firewalled and Fast")%></b> - 
     <%=intl._t("You have configured I2P to share more than 128KBps of bandwidth, but you are firewalled.")%>
     <%=intl._t("While I2P will work fine in this configuration, if you really have over 128KBps of bandwidth to share, it will be much more helpful to the network if you open your firewall.")%>
<li class="tidylist"><b><%=intl._t("WARN - Firewalled and Floodfill")%></b> - 
     <%=intl._t("You have configured I2P to be a floodfill router, but you are firewalled.")%> 
     <%=intl._t("For best participation as a floodfill router, you should open your firewall.")%>
<li class="tidylist"><b><%=intl._t("WARN - Firewalled with Inbound TCP Enabled")%></b> - 
     <%=intl._t("You have configured inbound TCP, however your UDP port is firewalled, and therefore it is likely that your TCP port is firewalled as well.")%>
     <%=intl._t("If your TCP port is firewalled with inbound TCP enabled, routers will not be able to contact you via TCP, which will hurt the network.")%> 
     <%=intl._t("Please open your firewall or disable inbound TCP above.")%>
<li class="tidylist"><b><%=intl._t("WARN - Firewalled with UDP Disabled")%></b> -
     <%=intl._t("You have configured inbound TCP, however you have disabled UDP.")%> 
     <%=intl._t("You appear to be firewalled on TCP, therefore your router cannot accept inbound connections.")%>
     <%=intl._t("Please open your firewall or enable UDP.")%>
<li class="tidylist"><b><%=intl._t("ERR - Clock Skew")%></b> - 
     <%=intl._t("Your system's clock is skewed, which will make it difficult to participate in the network.")%> 
     <%=intl._t("Correct your clock setting if this error persists.")%>
<li class="tidylist"><b><%=intl._t("ERR - Private TCP Address")%></b> - 
     <%=intl._t("You must never advertise an unroutable IP address such as 127.0.0.1 or 192.168.1.1 as your external address.")%> 
     <%=intl._t("Correct the address or disable inbound TCP above.")%>
<li class="tidylist"><b><%=intl._t("ERR - SymmetricNAT")%></b> - 
     <%=intl._t("I2P detected that you are firewalled by a Symmetric NAT.")%>
     <%=intl._t("I2P does not work well behind this type of firewall. You will probably not be able to accept inbound connections, which will limit your participation in the network.")%>
<li class="tidylist"><b><%=intl._t("ERR - UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart")%></b> -
     <%=intl._t("I2P was unable to bind to the configured port noted on the advanced network configuration page .")%>
     <%=intl._t("Check to see if another program is using the configured port. If so, stop that program or configure I2P to use a different port.")%> 
     <%=intl._t("This may be a transient error, if the other program is no longer using the port.")%> 
     <%=intl._t("However, a restart is always required after this error.")%>
<li class="tidylist"><b><%=intl._t("ERR - UDP Disabled and Inbound TCP host/port not set")%></b> -
     <%=intl._t("You have not configured inbound TCP with a hostname and port above, however you have disabled UDP.")%> 
     <%=intl._t("Therefore your router cannot accept inbound connections.")%>
     <%=intl._t("Please configure a TCP host and port above or enable UDP.")%>
<li class="tidylist"><b><%=intl._t("ERR - Client Manager I2CP Error - check logs")%></b> -
     <%=intl._t("This is usually due to a port 7654 conflict. Check the logs to verify.")%> 
     <%=intl._t("Do you have another I2P instance running? Stop the conflicting program and restart I2P.")%>
 </ul><hr>
<% /********
      <!--
 <b>Dynamic Router Keys: </b>
 <input type="checkbox" class="optbox" name="dynamicKeys" value="true" <jsp:getProperty name="nethelper" property="dynamicKeysChecked" /> /><br>
 <p>
 This setting causes your router identity to be regenerated every time your IP address
 changes. If you have a dynamic IP this option can speed up your reintegration into
 the network (since people will have banned your old router identity), and, for
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
 <br>
-->
*********/ %>
</div></form></div></div></body></html>
