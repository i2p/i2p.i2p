<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("tunnel summary")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %><h1><%=intl._t("I2P Tunnel Summary")%></h1>
<div class="main" id="tunnels">
 <jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request" />
 <jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>" />
 <% tunnelHelper.storeWriter(out); %>
<p id="gatherstats">
<%=intl._t("This page shows tunnels built by and routed through your router.")%>
<ul>
<li>
<b><%=intl._t("Exploratory tunnels")%>:</b>
<%=intl._t("Tunnels built by your router and used for communication with the floodfill peers, building new tunnels, and testing existing tunnels.")%>
</li>
<li>
<b><%=intl._t("Client tunnels")%>:</b>
<%=intl._t("Tunnels built by your router for each client's use.")%>
</li>
<li>
<b><%=intl._t("Participating tunnels")%>:</b>
<%=intl._t("Tunnels built by other routers through your router.")%>
<%=intl._t("The quantity may vary widely depending on network demand, your shared bandwidth, and amount of locally-generated traffic.")%>
<%=intl._t("The recommended method for limiting participating tunnels is to change your share percentage on the <a href=\"config\">Bandwidth Configuration page</a>.")%>
<%=intl._t("You may also limit the total number by setting <code>router.maxParticipatingTunnels=nnn</code> on the <a href=\"configadvanced\">Advanced configuration page</a>.")%>
</li>
<li>
<b><%=intl._t("Share ratio")%>:</b>
<%=intl._t("The number of participating tunnels you route for others, divided by the total number of hops in all your exploratory and client tunnels.")%>
<%=intl._t("A number greater than 1.00 means you are contributing more tunnels to the network than you are using.")%>
</li>
</ul>
</p>
 <jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div></body></html>
