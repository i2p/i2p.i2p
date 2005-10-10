<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.IndexBean" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:setProperty name="indexBean" property="*" />

<html>
<head>
<title>I2PTunnel Webmanager</title>
</head>
<body style="font-family: Verdana, Tahoma, Helvetica, sans-serif;font-size:12px;">
<table width="90%" align="center" border="0" cellpadding="1" cellspacing="1">
<tr>
<td style="background-color:#000">
<div style="background-color:#ffffed">
<table width="100%" align="center" border="0" cellpadding="4" cellspacing="1">
<tr>
<td nowrap="true"><b>New Messages: </b><br />
<a href="index.jsp">refresh</a>
</td>
<td>
<textarea rows="3" cols="60" readonly="true"><jsp:getProperty name="indexBean" property="messages" /></textarea>
</td>
</tr>
</table>
</td>
</tr>
</table>
<br />
<table width="90%" align="center" border="0" cellpadding="1" cellspacing="1">
<tr>
<td style="background-color:#000">
<div style="background-color:#ffffed">

<table width="100%" align="center" border="0" cellpadding="4" cellspacing="1">
<tr>
<td colspan="7" align="center" valign="middle" style="font-size:14px;">
<b>Your Client Tunnels:</b><br />
<hr size="1" />
</td>
</tr>
<tr>
<td width="15%"><b>Name:</b></td>
<td><b>Port:</b></td>
<td><b>Type:</b></td>
<td><b>Interface:</b></td>
<td><b>Status:</b></td>
</tr>
<% for (int curClient = 0; curClient < indexBean.getTunnelCount(); curClient++) {
     if (!indexBean.isClient(curClient)) continue; %>
<tr>
<td valign="top" align="left">
<b><a href="edit.jsp?tunnel=<%=curClient%>"><%=indexBean.getTunnelName(curClient) %></a></b></td>
<td valign="top" align="left" nowrap="true"><%=indexBean.getClientPort(curClient) %></td>
<td valign="top" align="left" nowrap="true"><%=indexBean.getTunnelType(curClient) %></td>
<td valign="top" align="left" nowrap="true"><%=indexBean.getClientInterface(curClient) %></td>
<td valign="top" align="left" nowrap="true"><%
 switch (indexBean.getTunnelStatus(curClient)) {
     case IndexBean.STARTING:
%><b><span style="color:#339933">Starting...</span></b>
<a href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&action=stop&tunnel=<%=curClient%>">[STOP]</a><%
         break;
     case IndexBean.RUNNING:
%><b><span style="color:#00dd00">Running</span></b>
<a href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&action=stop&tunnel=<%=curClient%>">[STOP]</a><%
         break;
     case IndexBean.NOT_RUNNING:
%><b><span style="color:#dd0000">Not Running</span></b>
<a href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&action=start&tunnel=<%=curClient%>">[START]</a><%
         break;
    }
%></td>
</tr>
<tr><td align="right" valign="top">Destination:</td>
    <td colspan="4"><input align="left" size="40" valign="top" style="overflow: hidden" readonly="true" value="<%=indexBean.getClientDestination(curClient) %>" /></td></tr>
<tr>
  <td valign="top" align="right">Description:</td>
  <td valign="top" align="left" colspan="4"><%=indexBean.getTunnelDescription(curClient) %></td>
</tr>
<% } %>
</table>
</td>
</tr>
</table>
<br />

<table width="90%" align="center" border="0" cellpadding="1" cellspacing="1">
<tr>
<td style="background-color:#000">
<div style="background-color:#ffffed">
<table width="100%" align="center" border="0" cellpadding="4" cellspacing="1">
<tr>
<td colspan="5" align="center" valign="middle" style="font-size:14px;">
<b>Your Server Tunnels:</b><br />
<hr size="1" />
</td>
</tr>
<tr>
<td width="15%"><b>Name: </b>
</td>
<td>
<b>Points at:</b>
</td>
<td>
<b>Status:</b>
</td>
</tr>

<% for (int curServer = 0; curServer < indexBean.getTunnelCount(); curServer++) {
     if (indexBean.isClient(curServer)) continue; %>

<tr>
<td valign="top">
<b><a href="edit.jsp?tunnel=<%=curServer%>"><%=indexBean.getTunnelName(curServer)%></a></b>
</td>
<td valign="top"><%=indexBean.getServerTarget(curServer)%></td>
<td valign="top" nowrap="true"><%
 switch (indexBean.getTunnelStatus(curServer)) {
     case IndexBean.RUNNING:
%><b><span style="color:#00dd00">Running</span></b>
<a href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&action=stop&tunnel=<%=curServer%>">[STOP]</a><%
         if ("httpserver".equals(indexBean.getInternalType(curServer))) { 
%> (<a href="http://<%=(new java.util.Random()).nextLong()%>.i2p/?i2paddresshelper=<%=indexBean.getDestinationBase64(curServer)%>">preview</a>)<%
         }
         break;
     case IndexBean.NOT_RUNNING:
%><b><span style="color:#dd0000">Not Running</span></b>
<a href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&action=start&tunnel=<%=curServer%>">[START]</a><%
         break;
     case IndexBean.STARTING:
%>
<b><span style="color:#339933">Starting...</span></b>
<a href="index.jsp?nonce=<%=indexBean.getNextNonce()%>&action=stop&tunnel=<%=curServer%>">[STOP]</a><%
        break;
    }
%>
</td>
</tr>
<tr><td valign="top" align="right">Description:</td>
    <td valign="top" align="left" colspan="2"><%=indexBean.getTunnelDescription(curServer)%></td></tr>
<% } %>

</table>
</td>
</tr>
</table>
<br />
<table width="90%" align="center" border="0" cellpadding="1" cellspacing="1">
<tr>
<td style="background-color:#000">
<div style="background-color:#ffffed">
<table width="100%" align="center" border="0" cellpadding="4" cellspacing="1">
<tr>
<td colspan="2" align="center" valign="middle">
<b>Operations Menu - Please chose from below!</b><br /><br />
</td>
</tr>
<tr>
<form action="index.jsp" method="GET">
<td >
<input type="hidden" name="nonce" value="<%=indexBean.getNextNonce()%>" />
<input type="submit" name="action" value="Stop all tunnels" />
<input type="submit" name="action" value="Start all tunnels" />
<input type="submit" name="action" value="Restart all" />
<input type="submit" name="action" value="Reload config" />
</td>
</form>
<form action="edit.jsp">
<td >
<b>Add new:</b> 
<select name="type">
  <option value="httpclient">HTTP proxy</option>
  <option value="ircclient">IRC proxy</option>
  <option value="client">Client tunnel</option>
  <option value="server">Server tunnel</option>
   <option value="httpserver">HTTP server tunnel</option>
</select> <input type="submit" value="Create" />
</td>
</form>
</tr>
</table>
</td>
</tr>
</table>
</body>
</html>
