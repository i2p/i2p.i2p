<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config peers")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Configuration")%></h1>
<div class="main" id="config_peers">
 <%@include file="confignav.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigPeerHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigPeerHelper" id="peerhelper" scope="request" />
 <jsp:setProperty name="peerhelper" property="contextId" value="<%=i2pcontextId%>" />
 <% String peer = "";
    net.i2p.data.Hash peerHash = null;
    boolean isBanned = false;
    if (request.getParameter("peer") != null) {
        // don't redisplay after POST, we don't do P-R-G
        if (!"POST".equals(request.getMethod())) {
            peer = net.i2p.data.DataHelper.stripHTML(request.getParameter("peer"));  // XSS
            if (peer.length() == 44) {
                byte[] h = net.i2p.data.Base64.decode(peer);
                if (h != null) {
                    try {
                        peerHash = net.i2p.data.Hash.create(h);
                        isBanned = peerhelper.isBanned(peerHash);
                    } catch (Exception e) {}
                }
            }
        }
    }
 %>
 <form action="configpeer" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <a name="sh"> </a>
 <a name="unsh"> </a>
 <a name="bonus"> </a>
 <h3 class="tabletitle"><%=intl._t("Manual Peer Controls")%></h3>
 <table class="configtable">
   <tr><td colspan="2"><b><%=intl._t("Router Hash")%>:</b> <input type="text" size="44" name="peer" value="<%=peer%>" /></td></tr>
   <tr><th colspan="2"><%=intl._t("Manually Ban / Unban a Peer")%></th></tr>
   <tr><td class="infohelp" colspan="2"><%=intl._t("Banning will prevent the participation of this peer in tunnels you create.")%></td></tr>
   <tr>
     <td class="optionsave" colspan="2">
<%
    if (peerHash == null || !isBanned) {
 %>
        <input type="submit" name="action" class="delete" value="<%=intl._t("Ban peer until restart")%>" />
<%
    }
    if (peerHash == null || isBanned) {
 %>
        <input type="submit" name="action" class="accept" value="<%=intl._t("Unban peer")%>" />
<%
    }
 %>
     </td>
   </tr>
   <tr><th colspan="2"><%=intl._t("Adjust Profile Bonuses")%></th></tr>
   <tr>
     <td class="infohelp" colspan="2">
     <%=intl._t("Bonuses may be positive or negative, and affect the peer's inclusion in Fast and High Capacity tiers. Fast peers are used for client tunnels, and High Capacity peers are used for some exploratory tunnels. Current bonuses are displayed on the")%> <a href="profiles"><%=intl._t("profiles page")%></a>.
     </td>
   </tr>
   <tr>
 <% long speed = 0; long capacity = 0;
    if (! "".equals(peer)) {
        // get existing bonus values?
    }
 %>
     <td><b><%=intl._t("Speed")%>:</b>
 <input type="text" size="8" name="speed" value="<%=speed%>" />
 <b><%=intl._t("Capacity")%>:</b>
 <input type="text" size="8" name="capacity" value="<%=capacity%>" />
     </td>
     <td class="optionsave">
       <input type="submit" name="action" class="add" value="<%=intl._t("Adjust peer bonuses")%>" />
     </td>
   </tr>
 </table>
 </form>
<%-- h3 in BanlistRenderer --%>
 <jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>" />
 <% profilesHelper.storeWriter(out); %>
 <jsp:getProperty name="profilesHelper" property="banlistSummary" />
 <h3 class="tabletitle"><%=intl._t("Banned IPs")%></h3>
 <jsp:getProperty name="peerhelper" property="blocklistSummary" />
 </div></body></html>
