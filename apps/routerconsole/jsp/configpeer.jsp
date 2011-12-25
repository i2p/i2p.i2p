<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config peers")%>
</head><body>

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Peer Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigPeerHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />



 <jsp:useBean class="net.i2p.router.web.ConfigPeerHelper" id="peerhelper" scope="request" />
 <jsp:setProperty name="peerhelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />

 <% String peer = "";
    if (request.getParameter("peer") != null)
        peer = net.i2p.data.DataHelper.stripHTML(request.getParameter("peer"));  // XSS
 %>
 <div class="configure">
 <form action="configpeer" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <a name="sh"> </a>
 <a name="unsh"> </a>
 <a name="bonus"> </a>
 <h2><%=intl._("Manual Peer Controls")%></h2>
 <div class="mediumtags"><p><%=intl._("Router Hash")%>:
<input type="text" size="55" name="peer" value="<%=peer%>" /></p></div>
 <h3><%=intl._("Manually Ban / Unban a Peer")%></h3>
 <p><%=intl._("Banning will prevent the participation of this peer in tunnels you create.")%></p>
      <div class="formaction">
        <input type="submit" name="action" class="delete" value="<%=intl._("Ban peer until restart")%>" />
        <input type="submit" name="action" class="accept" value="<%=intl._("Unban peer")%>" />
        <% if (! "".equals(peer)) { %>
        <!-- <font color="blue">&lt;---- click to verify action</font> -->
        <% } %>
      </div>

 <h3><%=intl._("Adjust Profile Bonuses")%></h3>
 <p><%=intl._("Bonuses may be positive or negative, and affect the peer's inclusion in Fast and High Capacity tiers. Fast peers are used for client tunnels, and High Capacity peers are used for some exploratory tunnels. Current bonuses are displayed on the")%> <a href="profiles"><%=intl._("profiles page")%></a>.</p>
 <% long speed = 0; long capacity = 0;
    if (! "".equals(peer)) {
        // get existing bonus values?
    }
 %>
 <div class="mediumtags"><p><%=intl._("Speed")%>:
 <input type="text" size="8" name="speed" value="<%=speed%>" />
 <%=intl._("Capacity")%>:
 <input type="text" size="8" name="capacity" value="<%=capacity%>" />
 <input type="submit" name="action" class="add" value="<%=intl._("Adjust peer bonuses")%>" /></p></div>
 </form>
 <a name="shitlist"> </a><h2><%=intl._("Banned Peers")%></h2>
 <jsp:useBean class="net.i2p.router.web.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <% profilesHelper.storeWriter(out); %>
 <jsp:getProperty name="profilesHelper" property="shitlistSummary" />
 <div class="wideload"><h2><%=intl._("Banned IPs")%></h2>
 <jsp:getProperty name="peerhelper" property="blocklistSummary" />

</div><hr></div></div></body></html>
