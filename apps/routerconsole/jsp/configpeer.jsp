<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config peers</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>
<h1>I2P Peer Configuration</h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigPeerHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 


 <jsp:useBean class="net.i2p.router.web.ConfigPeerHelper" id="peerhelper" scope="request" />
 <jsp:setProperty name="peerhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

 <% String peer = "";
    if (request.getParameter("peer") != null)     
        peer = request.getParameter("peer");
 %>
 <div class="configure">
 <form action="configpeer.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigPeerHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigPeerHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigPeerHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigPeerHandler.nonce")%>" />
<p>
 <a name="sh"> </a>
 <a name="unsh"> </a>
 <a name="bonus"> </a>
 <h2>Manual Peer Controls</h2>
 <div class="mediumtags">Router Hash:
 <input type="text" size="55" name="peer" value="<%=peer%>" /></div>
 <h3>Manually Ban / Unban a Peer</h3>
 Shitlisting will prevent the participation of this peer in tunnels you create. 
      <hr />      
      <div class="formaction"> 
        <input type="submit" name="action" value="Ban peer until restart" />
        <input type="submit" name="action" value="Unban peer" />
        <% if (! "".equals(peer)) { %>
        <!-- <font color="blue">&lt;---- click to verify action</font> -->
        <% } %>
      </div>

 <h3>Adjust Profile Bonuses</h3>
 Bonuses may be positive or negative, and affect the peer's inclusion in Fast 
      and High Capacity tiers. Fast peers are used for client tunnels, and High 
      Capacity peers are used for some exploratory tunnels. Current bonuses are 
      displayed on the <a href="profiles.jsp">profiles page</a>. 
      <p>
 <% long speed = 0; long capacity = 0;
    if (! "".equals(peer)) {
        // get existing bonus values?
    }
 %> 
 <hr />
 <div class="mediumtags">Speed:
 <input type="text" size="8" name="speed" value="<%=speed%>" />
 Capacity:
 <input type="text" size="8" name="capacity" value="<%=capacity%>" />
 <input type="submit" name="action" value="Adjust peer bonuses" /></div>
 </p>
 </form>
 <a name="shitlist"> </a>
 <jsp:useBean class="net.i2p.router.web.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="profilesHelper" property="shitlistSummary" />
 <hr />
 <div class="wideload">
 <jsp:getProperty name="peerhelper" property="blocklistSummary" />

</div>
</div>
</div>
</body>
</html>
