<%@page contentType="text/html" %>
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<% String tun = request.getParameter("tunnel");
   int curTunnel = -1;
   if (tun != null) {
     try {
       curTunnel = Integer.parseInt(tun);
     } catch (NumberFormatException nfe) {
       curTunnel = -1;
     }
   }
%>
<html>
<head>
<title>I2PTunnel Webmanager</title>
</head>
<body>
<form action="index.jsp">
<input type="hidden" name="tunnel" value="<%=request.getParameter("tunnel")%>" />
<input type="hidden" name="nonce" value="<%=editBean.getNextNonce()%>" />
<table width="80%" align="center" border="0" cellpadding="1" cellspacing="1">
<tr>
<td style="background-color:#000">
<div style="background-color:#ffffed">
<table width="100%" align="center" border="0" cellpadding="4" cellspacing="1">
<tr>
<td colspan="2" align="center">
<% if (curTunnel >= 0) { %>
<b>Edit proxy settings</b>
<% } else { %>
<b>New proxy settings</b>
<% } %>
</td>
</tr>
<tr>
<td><b>Name: </b>
</td>
<td>
<input type="text" size="30" maxlength="50" name="name" value="<%=editBean.getTunnelName(curTunnel)%>" />
</td>
</tr>
<tr>
<td><b>Type: </b>
<td><%
if (curTunnel >= 0) {
  %><%=editBean.getTunnelType(curTunnel)%>
<input type="hidden" name="type" value="<%=editBean.getInternalType(curTunnel)%>" />
<%
} else {
  %><%=editBean.getTypeName(request.getParameter("type"))%>
<input type="hidden" name="type" value="<%=request.getParameter("type")%>" />
<%
}
%></td>
</tr>
<tr>
<td><b>Description: </b>
</td>
<td>
<input type="text" size="60" maxlength="80" name="description" value="<%=editBean.getTunnelDescription(curTunnel)%>" />
</td>
</tr>
<tr>
<td><b>Start automatically?:</b>
</td>
<td>
<% if (editBean.startAutomatically(curTunnel)) { %>
<input value="1" type="checkbox" name="startOnLoad" checked="true" />
<% } else { %>
<input value="1" type="checkbox" name="startOnLoad" />
<% } %>
<i>(Check the Box for 'YES')</i>
</td>
</tr>
<tr>
<td> <b>Listening Port:</b>
</td>
<td>
<input type="text" size="6" maxlength="5" name="port" value="<%=editBean.getClientPort(curTunnel)%>" />
</td>
</tr>
<tr>
<td><b> Accessable by:</b>
</td>
<td>
<select name="reachableBy">
<% String clientInterface = editBean.getClientInterface(curTunnel); %>
<% if (("127.0.0.1".equals(clientInterface)) || (clientInterface == null) || (clientInterface.trim().length() <= 0)) { %>
<option value="127.0.0.1" selected="true">Locally (127.0.0.1)</option>
<option value="0.0.0.0">Everyone (0.0.0.0)</option>
<option value="other">LAN Hosts (Please specify your LAN address)</option>

</select>
&nbsp;&nbsp;
<b>others:</b>
<input type="text" name="reachableByOther" size="20" />
<% } else if ("0.0.0.0".equals(clientInterface)) { %>
<option value="127.0.0.1">Locally (127.0.0.1)</option>
<option value="0.0.0.0" selected="true">Everyone (0.0.0.0)</option>
<option value="other">LAN Hosts (Please specify your LAN address)</option>

</select>
&nbsp;&nbsp;
<b>others:</b>
<input type="text" name="reachableByOther" size="20" />
<% } else { %>
<option value="127.0.0.1">Locally (127.0.0.1)</option>
<option value="0.0.0.0">Everyone (0.0.0.0)</option>
<option value="other" selected="true">LAN Hosts (Please specify your LAN address)</option>

</select>
&nbsp;&nbsp;
<b>others:</b>
<input type="text" name="reachableByOther" size="20" value="<%=clientInterface%>" />
<% } %>

</td>
</tr>
<tr>
<% if ("httpclient".equals(editBean.getInternalType(curTunnel))) { %>
<td><b>Outproxies:</b>
<% } else { %>
<td><b>Target:</b>
<% } %>
</td>
<td>
<% if ("httpclient".equals(editBean.getInternalType(curTunnel))) { %>
<input type="text" name="proxyList" value="<%=editBean.getClientDestination(curTunnel)%>" />
<% } else { %>
<input type="text" name="targetDestination" value="<%=editBean.getClientDestination(curTunnel)%>" />
<% } %>
<i>(name or destination)</i>
</td>
</tr>
<tr>
<td>
<b>Delayed connect?</b>
</td>
<td>
<% if (editBean.shouldDelay(curTunnel)) { %>
<input type="checkbox" value="1000" name="connectDelay" checked="true" />
<% } else { %>
<input type="checkbox" value="1000" name="connectDelay" />
<% } %>
<i>(for request/response connections)</i>
</td>
</tr>
<tr>
<td><b>Profile:</b>
</td>
<td>
<select name="profile">
<% if (editBean.isInteractive(curTunnel)) { %>
<option value="interactive" selected="true">interactive connection </option>
<option value="bulk">bulk connection (downloads/websites/BT) </option>
<% } else { %>
<option value="interactive">interactive connection </option>
<option value="bulk" selected="true">bulk connection (downloads/websites/BT) </option>
<% } %>
</select>
</td>
</tr>
<tr>
<td>
<b>Shared Client</b>
</td>
<td>
<% if (editBean.isSharedClient(curTunnel)) { %>
<input type="checkbox" value="true" name="shared" checked="true" />
<% } else { %>
<input type="checkbox" value="true" name="shared" />
<% } %>
<i>(Share tunnels with other clients and irc/httpclients? Change requires restart of client proxy)</i>
</td>
</tr>
<tr>
<td colspan="2" align="center">
<b><hr size="1">
Advanced networking options<br />
<span style="color:#dd0000;">(NOTE: when this client proxy is configured to share tunnels, then these options are for all the shared proxy clients!)</span></b>
</td>
</tr>
<tr>
<td>
<b>Tunnel depth:</b>
</td>
<td><select name="tunnelDepth">
<% int tunnelDepth = editBean.getTunnelDepth(curTunnel, 2);
   switch (tunnelDepth) { 
     case 0: %>
<option value="0" selected="true">0 hop tunnel (low anonymity, low latency)</option>
<option value="1" >1 hop tunnel (medium anonymity, medium latency)</option>
<option value="2" >2 hop tunnel (high anonymity, high latency)</option>
<%     break;
     case 1: %>
<option value="0" >0 hop tunnel (low anonymity, low latency)</option>
<option value="1" selected="true">1 hop tunnel (medium anonymity, medium latency)</option>
<option value="2" >2 hop tunnel (high anonymity, high latency)</option>
<%     break;
     case 2: %>
<option value="0" >0 hop tunnel (low anonymity, low latency)</option>
<option value="1" >1 hop tunnel (medium anonymity, medium latency)</option>
<option value="2" selected="true">2 hop tunnel (high anonymity, high latency)</option>
<%     break;
     default: %>
<option value="0" >0 hop tunnel (low anonymity, low latency)</option>
<option value="1" >1 hop tunnel (medium anonymity, medium latency)</option>
<option value="2" >2 hop tunnel (high anonymity, high latency)</option>
<option value="<%=tunnelDepth%>" ><%=tunnelDepth%> hop tunnel</option>
<% } %>
</select>
</td>
</tr>
<tr>
<td><b>Tunnel count:</b> 
</td>
<td>
<select name="tunnelCount">
<% int tunnelCount = editBean.getTunnelCount(curTunnel, 2);
   switch (tunnelCount) { 
     case 1: %>
<option value="1" selected="true" >1 inbound tunnel (low bandwidth usage, less reliability)</option>
<option value="2" >2 inbound tunnels (standard bandwidth usage, standard reliability)</option>
<option value="3" >3 inbound tunnels (higher bandwidth usage, higher reliability)</option>
<%     break;
     case 2: %>
<option value="1" >1 inbound tunnel (low bandwidth usage, less reliability)</option>
<option value="2"  selected="true" >2 inbound tunnels (standard bandwidth usage, standard reliability)</option>
<option value="3" >3 inbound tunnels (higher bandwidth usage, higher reliability)</option>
<%     break;
     case 3: %>
<option value="1" >1 inbound tunnel (low bandwidth usage, less reliability)</option>
<option value="2" >2 inbound tunnels (standard bandwidth usage, standard reliability)</option>
<option value="3" selected="true" >3 inbound tunnels (higher bandwidth usage, higher reliability)</option>
<%     break;
     default: %>
<option value="1" >1 inbound tunnel (low bandwidth usage, less reliability)</option>
<option value="2" >2 inbound tunnels (standard bandwidth usage, standard reliability)</option>
<option value="3" >3 inbound tunnels (higher bandwidth usage, higher reliability)</option>
<option value="<%=tunnelDepth%>" ><%=tunnelDepth%> inbound tunnels</option>
<% } %>
</select>
</td>
<tr>
<td><b>I2CP host:</b> 
</td>
<td>
<input type="text" name="clientHost" size="20" value="<%=editBean.getI2CPHost(curTunnel)%>" />
</td>
</tr>
<tr>
<td><b>I2CP port:</b> 
</td>
<td>
<input type="text" name="clientPort" size="20" value="<%=editBean.getI2CPPort(curTunnel)%>" /><br />
</td>
</tr>
<tr>
<td><b>Custom options:</b>
</td>
<td>
<input type="text" name="customOptions" size="60" value="<%=editBean.getCustomOptions(curTunnel)%>" />
</td>
</tr>
<tr>
<td colspan="2">
<hr size="1">
</td>
</tr>
<tr>
<td>
<b>Save:</b>
</td>
<td>
<input type="submit" name="action" value="Save changes" />
</td>
</tr>
<tr>
<td><b>Delete?</b>
</td>
<td>
<input type="submit" name="action" value="Delete this proxy" /> &nbsp;&nbsp;
<b><span style="color:#dd0000;">confirm delete:</span></b>
<input type="checkbox" value="true" name="removeConfirm" />
</td>
</tr>
</table>
</td>
</tr>
</table>
</form>
</body>
</html>
