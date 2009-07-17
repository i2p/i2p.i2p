<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - peer connections</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>
 <h1>I2P Network Peers</h1>
<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.PeerHelper" id="peerHelper" scope="request" />
 <jsp:setProperty name="peerHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="peerHelper" property="writer" value="<%=out%>" />
 <jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp" />
 <jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter("sort") != null ? request.getParameter("sort") : ""%>" />
 <jsp:getProperty name="peerHelper" property="peerSummary" />
</div>

</body>
</html>
