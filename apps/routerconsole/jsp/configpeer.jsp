<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config peers")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Peer Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigPeerHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.ConfigPeerHelper" id="peerhelper" scope="request" />
 <jsp:setProperty name="peerhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

 <% String peer = "";
    if (request.getParameter("peer") != null)
        peer = net.i2p.data.DataHelper.stripHTML(request.getParameter("peer"));  // XSS
 %>
 <div class="configure">
 <form action="configpeer" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <a name="sh"> </a>
 <a name="unsh"> </a>
 <a name="bonus"> </a>
 <h2><%=intl._t("Manual Peer Controls")%></h2>
 <div class="mediumtags"><p><%=intl._t("Router Hash")%>:
<input type="text" size="55" name="peer" value="<%=peer%>" /></p></div>
 <h3><%=intl._t("Manually Ban / Unban a Peer")%></h3>
 <p><%=intl._t("Banning will prevent the participation of this peer in tunnels you create.")%></p>
      <div class="formaction">
        <input type="submit" name="action" class="delete" value="<%=intl._t("Ban peer until restart")%>" />
        <input type="submit" name="action" class="accept" value="<%=intl._t("Unban peer")%>" />
        <% if (! "".equals(peer)) { %>
        <!-- <font color="blue">&lt;---- click to verify action</font> -->
        <% } %>
      </div>

 <h3><%=intl._t("Adjust Profile Bonuses")%></h3>
 <p><%=intl._t("Bonuses may be positive or negative, and affect the peer's inclusion in Fast and High Capacity tiers. Fast peers are used for client tunnels, and High Capacity peers are used for some exploratory tunnels. Current bonuses are displayed on the")%> <a href="profiles"><%=intl._t("profiles page")%></a>.</p>
 <% long speed = 0; long capacity = 0;
    if (! "".equals(peer)) {
        // get existing bonus values?
    }
 %>
 <div class="mediumtags"><p><%=intl._t("Speed")%>:
 <input type="text" size="8" name="speed" value="<%=speed%>" />
 <%=intl._t("Capacity")%>:
 <input type="text" size="8" name="capacity" value="<%=capacity%>" />
 <input type="submit" name="action" class="add" value="<%=intl._t("Adjust peer bonuses")%>" /></p></div>
 </form>
 <a name="banlist"> </a><h2><%=intl._t("Banned Peers")%></h2>
 <jsp:useBean class="net.i2p.router.web.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <% profilesHelper.storeWriter(out); %>
 <jsp:getProperty name="profilesHelper" property="banlistSummary" />
 <div class="wideload"><h2><%=intl._t("Banned IPs")%></h2>
 <jsp:getProperty name="peerhelper" property="blocklistSummary" />

</div><hr></div></div></body></html>
