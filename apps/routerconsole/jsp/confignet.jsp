<%@page contentType="text/html" %>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config networking")%>
</head><body>

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._("I2P Network Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
<div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <input type="hidden" name="action" value="blah" >
 <h3><%=intl._("IP and Transport Configuration")%></h3><p>
 <img src="/themes/console/images/itoopie_xsm.png" alt="">
 <b><%=intl._("The default settings will work for most people.")%>
 <a href="#chelp"><%=intl._("There is help below.")%></a></b>
 </p><p><b><%=intl._("UPnP Configuration")%>:</b><br>
    <input type="checkbox" class="optbox" name="upnp" value="true" <jsp:getProperty name="nethelper" property="upnpChecked" /> >
    <%=intl._("Enable UPnP to open firewall ports")%> - <a href="peers#upnp"><%=intl._("UPnP status")%></a>
 </p><p><b><%=intl._("IP Configuration")%>:</b><br>
 <%=intl._("Externally reachable hostname or IP address")%>:<br>
    <input type="radio" class="optbox" name="udpAutoIP" value="local,upnp,ssu" <%=nethelper.getUdpAutoIPChecked(3) %> >
    <%=intl._("Use all auto-detect methods")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="local,ssu" <%=nethelper.getUdpAutoIPChecked(4) %> >
    <%=intl._("Disable UPnP IP address detection")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="upnp,ssu" <%=nethelper.getUdpAutoIPChecked(5) %> >
    <%=intl._("Ignore local interface IP address")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="ssu" <%=nethelper.getUdpAutoIPChecked(0) %> >
    <%=intl._("Use SSU IP address detection only")%><br>
    <input type="radio" class="optbox" name="udpAutoIP" value="fixed" <%=nethelper.getUdpAutoIPChecked(1) %> >
    <%=intl._("Specify hostname or IP")%>:
    <input name ="udpHost1" type="text" size="16" value="<jsp:getProperty name="nethelper" property="udphostname" />" >
    <% String[] ips = nethelper.getAddresses();
       if (ips.length > 0) {
           out.print(intl._("or") + " <select name=\"udpHost2\"><option value=\"\" selected=\"true\">"+intl._("Select Interface")+"</option>\n");
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
    <br>
    <input type="radio" class="optbox" name="udpAutoIP" value="hidden" <%=nethelper.getUdpAutoIPChecked(2) %> >
    <%=intl._("Hidden mode - do not publish IP")%> <i><%=intl._("(prevents participating traffic)")%></i><br>
 </p><p>
 <%=intl._("Action when IP changes")%>:<br>
    <input type="checkbox" class="optbox" name="laptop" value="true" <jsp:getProperty name="nethelper" property="laptopChecked" /> >
    <%=intl._("Laptop mode - Change router identity and UDP port when IP changes for enhanced anonymity")%>
    (<i><%=intl._("Experimental")%></i>)
 </p><p><b><%=intl._("UDP Configuration:")%></b><br>
 <%=intl._("UDP port:")%>
 <input name ="udpPort" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="configuredUdpPort" />" ><br>
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
 <b><%=intl._("TCP Configuration")%>:</b><br>
 <%=intl._("Externally reachable hostname or IP address")%>:<br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="true" <%=nethelper.getTcpAutoIPChecked(2) %> >
    <%=intl._("Use auto-detected IP address")%>
    <i>(<%=intl._("currently")%> <jsp:getProperty name="nethelper" property="udpIP" />)</i>
    <%=intl._("if we are not firewalled")%><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="always" <%=nethelper.getTcpAutoIPChecked(3) %> >
    <%=intl._("Always use auto-detected IP address (Not firewalled)")%><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(1) %> >
    <%=intl._("Specify hostname or IP")%>:
    <input name ="ntcphost" type="text" size="16" value="<jsp:getProperty name="nethelper" property="ntcphostname" />" ><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(0) %> >
    <%=intl._("Disable inbound (Firewalled)")%><br>
    <input type="radio" class="optbox" name="ntcpAutoIP" value="disabled" <%=nethelper.getTcpAutoIPChecked(4) %> >
    <%=intl._("Completely disable")%> <i><%=intl._("(select only if behind a firewall that throttles or blocks outbound TCP)")%></i><br>
 </p><p>
 <%=intl._("Externally reachable TCP port")%>:<br>
    <input type="radio" class="optbox" name="ntcpAutoPort" value="2" <%=nethelper.getTcpAutoPortChecked(2) %> >
    <%=intl._("Use the same port configured for UDP")%>
    <i>(<%=intl._("currently")%> <jsp:getProperty name="nethelper" property="udpPort" />)</i><br>
    <input type="radio" class="optbox" name="ntcpAutoPort" value="1" <%=nethelper.getTcpAutoPortChecked(1) %> >
    <%=intl._("Specify Port")%>:
    <input name ="ntcpport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="ntcpport" />" ><br>
 </p><p><b><%=intl._("Notes")%>: <%=intl._("a) Do not reveal your port numbers to anyone!   b) Changing these settings will restart your router.")%></b></p>
<hr><div class="formaction">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" class="accept" name="save" value="<%=intl._("Save changes")%>" >
</div><h3><a name="chelp"><%=intl._("Configuration Help")%>:</a></h3><div align="justify"><p>
 <%=intl._("While I2P will work fine behind most firewalls, your speeds and network integration will generally improve if the I2P port is forwarded for both UDP and TCP.")%>
 </p><p>
 <%=intl._("If you can, please poke a hole in your firewall to allow unsolicited UDP and TCP packets to reach you.")%>
   <%=intl._("If you can't, I2P supports UPnP (Universal Plug and Play) and UDP hole punching with \"SSU introductions\" to relay traffic.")%>
   <%=intl._("Most of the options above are for special situations, for example where UPnP does not work correctly, or a firewall not under your control is doing harm.")%> 
   <%=intl._("Certain firewalls such as symmetric NATs may not work well with I2P.")%>
 </p>
<% /********
<!-- let's keep this simple...
<input type="submit" name="recheckReachability" value="Check network reachability..." />
</p>
-->
*********/ %>
<p>
 <%=intl._("UPnP is used to communicate with Internet Gateway Devices (IGDs) to detect the external IP address and forward ports.")%>
   <%=intl._("UPnP support is beta, and may not work for any number of reasons")%>:
<ul>
<li class="tidylist"><%=intl._("No UPnP-compatible device present")%>
<li class="tidylist"><%=intl._("UPnP disabled on the device")%>
<li class="tidylist"><%=intl._("Software firewall interference with UPnP")%>
<li class="tidylist"><%=intl._("Bugs in the device's UPnP implementation")%>
<li class="tidylist"><%=intl._("Multiple firewall/routers in the internet connection path")%>
<li class="tidylist"><%=intl._("UPnP device change, reset, or address change")%>
</ul></p><p>
<a href="peers#upnp"><%=intl._("Review the UPnP status here.")%></a>
<%=intl._("UPnP may be enabled or disabled above, but a change requires a router restart to take effect.")%></p>
<p><%=intl._("Hostnames entered above will be published in the network database.")%>
    <%=intl._("They are <b>not private</b>.")%>
    <%=intl._("Also, <b>do not enter a private IP address</b> like 127.0.0.1 or 192.168.1.1.")%>
    <%=intl._("If you specify the wrong IP address or hostname, or do not properly configure your NAT or firewall, your network performance will degrade substantially.")%>
    <%=intl._("When in doubt, leave the settings at the defaults.")%>
</p>
<h3><a name="help"><%=intl._("Reachability Help")%>:</a></h3><p>
 <%=intl._("While I2P will work fine behind most firewalls, your speeds and network integration will generally improve if the I2P port is forwarded for both UDP and TCP.")%>
 <%=intl._("If you think you have opened up your firewall and I2P still thinks you are firewalled, remember that you may have multiple firewalls, for example both software packages and external hardware routers.")%>
 <%=intl._("If there is an error, the <a href=\"logs.jsp\">logs</a> may also help diagnose the problem.")%>
 <ul>
<li class="tidylist"><b><%=intl._("OK")%></b> - 
     <%=intl._("Your UDP port does not appear to be firewalled.")%>
<li class="tidylist"><b><%=intl._("Firewalled")%></b> - 
     <%=intl._("Your UDP port appears to be firewalled.")%>
     <%=intl._("As the firewall detection methods are not 100% reliable, this may occasionally be displayed in error.")%>
     <%=intl._("However, if it appears consistently, you should check whether both your external and internal firewalls are open for your port.")%> 
     <%=intl._("I2P will work fine when firewalled, there is no reason for concern. When firewalled, the router uses \"introducers\" to relay inbound connections.")%>
     <%=intl._("However, you will get more participating traffic and help the network more if you can open your firewall(s).")%>
     <%=intl._("If you think you have already done so, remember that you may have both a hardware and a software firewall, or be behind an additional, institutional firewall you cannot control.")%>
     <%=intl._("Also, some routers cannot correctly forward both TCP and UDP on a single port, or may have other limitations or bugs that prevent them from passing traffic through to I2P.")%>
<li class="tidylist"><b><%=intl._("Testing")%></b> - 
     <%=intl._("The router is currently testing whether your UDP port is firewalled.")%>
<li class="tidylist"><b><%=intl._("Hidden")%></b> - 
     <%=intl._("The router is not configured to publish its address, therefore it does not expect incoming connections.")%>
     <%=intl._("Hidden mode is automatically enabled for added protection in certain countries.")%>
<li class="tidylist"><b><%=intl._("WARN - Firewalled and Fast")%></b> - 
     <%=intl._("You have configured I2P to share more than 128KBps of bandwidth, but you are firewalled.")%>
     <%=intl._("While I2P will work fine in this configuration, if you really have over 128KBps of bandwidth to share, it will be much more helpful to the network if you open your firewall.")%>
<li class="tidylist"><b><%=intl._("WARN - Firewalled and Floodfill")%></b> - 
     <%=intl._("You have configured I2P to be a floodfill router, but you are firewalled.")%> 
     <%=intl._("For best participation as a floodfill router, you should open your firewall.")%>
<li class="tidylist"><b><%=intl._("WARN - Firewalled with Inbound TCP Enabled")%></b> - 
     <%=intl._("You have configured inbound TCP, however your UDP port is firewalled, and therefore it is likely that your TCP port is firewalled as well.")%>
     <%=intl._("If your TCP port is firewalled with inbound TCP enabled, routers will not be able to contact you via TCP, which will hurt the network.")%> 
     <%=intl._("Please open your firewall or disable inbound TCP above.")%>
<li class="tidylist"><b><%=intl._("WARN - Firewalled with UDP Disabled")%></b> -
     <%=intl._("You have configured inbound TCP, however you have disabled UDP.")%> 
     <%=intl._("You appear to be firewalled on TCP, therefore your router cannot accept inbound connections.")%>
     <%=intl._("Please open your firewall or enable UDP.")%>
<li class="tidylist"><b><%=intl._("ERR - Clock Skew")%></b> - 
     <%=intl._("Your system's clock is skewed, which will make it difficult to participate in the network.")%> 
     <%=intl._("Correct your clock setting if this error persists.")%>
<li class="tidylist"><b><%=intl._("ERR - Private TCP Address")%></b> - 
     <%=intl._("You must never advertise an unroutable IP address such as 127.0.0.1 or 192.168.1.1 as your external address.")%> 
     <%=intl._("Correct the address or disable inbound TCP above.")%>
<li class="tidylist"><b><%=intl._("ERR - SymmetricNAT")%></b> - 
     <%=intl._("I2P detected that you are firewalled by a Symmetric NAT.")%>
     <%=intl._("I2P does not work well behind this type of firewall. You will probably not be able to accept inbound connections, which will limit your participation in the network.")%>
<li class="tidylist"><b><%=intl._("ERR - UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart")%></b> -
     <%=intl._("I2P was unable to bind to port 8887 or other configured port.")%>
     <%=intl._("Check to see if another program is using the configured port. If so, stop that program or configure I2P to use a different port.")%> 
     <%=intl._("This may be a transient error, if the other program is no longer using the port.")%> 
     <%=intl._("However, a restart is always required after this error.")%>
<li class="tidylist"><b><%=intl._("ERR - UDP Disabled and Inbound TCP host/port not set")%></b> -
     <%=intl._("You have not configured inbound TCP with a hostname and port above, however you have disabled UDP.")%> 
     <%=intl._("Therefore your router cannot accept inbound connections.")%>
     <%=intl._("Please configure a TCP host and port above or enable UDP.")%>
<li class="tidylist"><b><%=intl._("ERR - Client Manager I2CP Error - check logs")%></b> -
     <%=intl._("This is usually due to a port 7654 conflict. Check the logs to verify.")%> 
     <%=intl._("Do you have another I2P instance running? Stop the conflicting program and restart I2P.")%>
 </ul></p><hr>
<% /********
      <!--
 <b>Dynamic Router Keys: </b>
 <input type="checkbox" class="optbox" name="dynamicKeys" value="true" <jsp:getProperty name="nethelper" property="dynamicKeysChecked" /> /><br>
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
 <br>
-->
*********/ %>
</div></form></div></div></body></html>
